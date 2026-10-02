package cn.edu.fudan.dayu.discovery.infrastructure;

import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.discovery.application.DiscoveryCachePort;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Discovery 的 JSON Redis 适配器，只存公开 URI 和元数据，不存文件路径或内容。 */
@Component
@Profile("!skeleton")
public class RedisDiscoveryCache implements DiscoveryCachePort {
    private static final Logger LOG = LoggerFactory.getLogger(RedisDiscoveryCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Duration previewTtl;
    private final Duration cycleTtl;
    private final Set<ProductCode> invalidationPending = ConcurrentHashMap.newKeySet();

    public RedisDiscoveryCache(StringRedisTemplate redis, ObjectMapper json,
            @Value("${dayu.cache.enabled:false}") boolean enabled,
            @Value("${dayu.cache.preview-ttl:2m}") Duration previewTtl,
            @Value("${dayu.cache.forecast-cycle-ttl:2m}") Duration cycleTtl) {
        if (!positive(previewTtl) || !positive(cycleTtl)) throw new IllegalArgumentException("Discovery cache TTLs must be positive");
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        this.previewTtl = previewTtl;
        this.cycleTtl = cycleTtl;
    }

    @Override
    public Read<PreviewFrame> readPreviewFrames(PreviewQuery query) {
        if (!enabled || query == null || query.productCode() == null) return miss();
        return read(query.productCode(), previewKey(query), PreviewPayload.class, PreviewPayload::frames);
    }

    @Override
    public void writePreviewFrames(PreviewQuery query, long generation, List<PreviewFrame> frames) {
        if (query != null && query.productCode() != null)
            write(query.productCode(), previewKey(query), generation, new PreviewPayload(frames), previewTtl);
    }

    @Override
    public Read<ForecastCycleSummary> readForecastCycles(ForecastCycleQuery query) {
        if (!enabled || query == null || query.productCode() == null || query.assetType() != AssetType.WEBP) return miss();
        return read(query.productCode(), cycleKey(query), CyclePayload.class, CyclePayload::cycles);
    }

    @Override
    public void writeForecastCycles(ForecastCycleQuery query, long generation, List<ForecastCycleSummary> cycles) {
        if (query != null && query.productCode() != null && query.assetType() == AssetType.WEBP)
            write(query.productCode(), cycleKey(query), generation, new CyclePayload(cycles), cycleTtl);
    }

    @Override
    public synchronized void invalidate(ProductCode productCode) {
        if (!enabled || productCode == null) return;
        try {
            redis.opsForValue().set(generationKey(productCode), Long.toString(randomGeneration()));
            invalidationPending.remove(productCode);
        } catch (RuntimeException unavailable) {
            // An offline write leaves old Redis values behind. Bypass them locally until the generation switch succeeds.
            invalidationPending.add(productCode);
            LOG.warn("Discovery cache invalidation deferred: {}", unavailable.getClass().getSimpleName());
        }
    }

    private <T, P> Read<T> read(ProductCode code, String suffix, Class<P> payloadType,
                                 java.util.function.Function<P, List<T>> values) {
        try {
            long generation = currentGeneration(code);
            if (generation == 0) return miss();
            String key = dataKey(code, generation, suffix);
            String value = redis.opsForValue().get(key);
            if (value == null) return new Read<>(generation, Optional.empty());
            try {
                return new Read<>(generation, Optional.of(values.apply(json.readValue(value, payloadType))));
            } catch (Exception malformed) {
                redis.delete(key);
                LOG.warn("Discarded malformed Discovery cache value");
                return new Read<>(generation, Optional.empty());
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("Discovery cache read unavailable: {}", unavailable.getClass().getSimpleName());
            return miss();
        }
    }

    private void write(ProductCode code, String suffix, long generation, Object payload, Duration ttl) {
        if (!enabled || generation < 1) return;
        try {
            if (currentGeneration(code) != generation) return;
            redis.opsForValue().set(dataKey(code, generation, suffix), json.writeValueAsString(payload), ttl);
        } catch (Exception unavailable) {
            LOG.warn("Discovery cache write unavailable: {}", unavailable.getClass().getSimpleName());
        }
    }

    private synchronized long currentGeneration(ProductCode code) {
        String key = generationKey(code);
        if (invalidationPending.contains(code)) {
            redis.opsForValue().set(key, Long.toString(randomGeneration()));
            invalidationPending.remove(code);
        }
        String value = redis.opsForValue().get(key);
        if (value == null) {
            // A random seed prevents an evicted generation key from making a still-live old payload reachable again.
            redis.opsForValue().setIfAbsent(key, Long.toString(randomGeneration()));
            value = redis.opsForValue().get(key);
        }
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String previewKey(PreviewQuery q) {
        return "timeline:assetType=WEBP:mode=" + value(q.dataMode()) + ":from=" + value(q.from())
                + ":to=" + value(q.to()) + ":cycle=" + value(q.cycleTime())
                + ":lead=" + value(q.leadMinutes()) + ":limit=" + q.limit();
    }

    private static String cycleKey(ForecastCycleQuery q) {
        return "latest-cycles:assetType=" + value(q.assetType()) + ":mode=FORECAST:from=" + value(q.from())
                + ":to=" + value(q.to()) + ":cycle=ALL:lead=ALL:limit=ALL";
    }

    private static String generationKey(ProductCode code) { return "dayu:discovery:" + code.value() + ":generation"; }
    private static String dataKey(ProductCode code, long generation, String suffix) {
        return "dayu:discovery:" + code.value() + ":v" + generation + ":" + suffix;
    }
    private static String value(Object value) { return value == null ? "NONE" : value.toString(); }
    private static boolean positive(Duration duration) { return duration != null && !duration.isZero() && !duration.isNegative(); }
    private static long randomGeneration() { return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2); }
    private static <T> Read<T> miss() { return new Read<>(0, Optional.empty()); }
    private record PreviewPayload(List<PreviewFrame> frames) {
        private PreviewPayload { frames = List.copyOf(frames); }
    }
    private record CyclePayload(List<ForecastCycleSummary> cycles) {
        private CyclePayload { cycles = List.copyOf(cycles); }
    }
}

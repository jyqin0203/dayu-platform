package cn.edu.fudan.dayu.catalog.infrastructure;

import cn.edu.fudan.dayu.catalog.api.ProductDetail;
import cn.edu.fudan.dayu.catalog.application.CatalogCachePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** JSON Redis 适配器。代际键避免通配扫描，并阻止失效期间的旧值回填。 */
@Component
@Profile("!skeleton")
public class RedisCatalogCache implements CatalogCachePort {
    private static final Logger LOG = LoggerFactory.getLogger(RedisCatalogCache.class);
    private static final String GENERATION_KEY = "dayu:catalog:generation";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Duration ttl;
    private final AtomicBoolean invalidationPending = new AtomicBoolean();

    public RedisCatalogCache(StringRedisTemplate redis, ObjectMapper json,
            @Value("${dayu.cache.enabled:false}") boolean enabled,
            @Value("${dayu.cache.catalog-ttl:5m}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Catalog cache TTL must be positive");
        this.redis = redis;
        this.json = json;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    @Override
    public Read readPublishedProducts() {
        if (!enabled) return miss(0);
        try {
            long generation = currentGeneration();
            if (generation == 0) return miss(0);
            String key = dataKey(generation);
            String value = redis.opsForValue().get(key);
            if (value == null) return miss(generation);
            try {
                return new Read(generation, Optional.of(json.readValue(value, CatalogPayload.class).products()));
            } catch (Exception malformed) {
                redis.delete(key);
                LOG.warn("Discarded malformed Catalog cache value");
                return miss(generation);
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("Catalog cache read unavailable: {}", unavailable.getClass().getSimpleName());
            return miss(0);
        }
    }

    @Override
    public void writePublishedProducts(long generation, List<ProductDetail> products) {
        if (!enabled || generation < 1) return;
        try {
            // An invalidation between this check and SET only creates an unreachable old-generation key.
            if (currentGeneration() != generation) return;
            redis.opsForValue().set(dataKey(generation), json.writeValueAsString(new CatalogPayload(products)), ttl);
        } catch (Exception unavailable) {
            LOG.warn("Catalog cache write unavailable: {}", unavailable.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void invalidatePublishedProducts() {
        if (!enabled) return;
        try {
            redis.opsForValue().set(GENERATION_KEY, Long.toString(randomGeneration()));
            invalidationPending.set(false);
        } catch (RuntimeException unavailable) {
            // Until a later read retries this generation switch, this process bypasses Redis rather than serving an old value.
            invalidationPending.set(true);
            LOG.warn("Catalog cache invalidation deferred: {}", unavailable.getClass().getSimpleName());
        }
    }

    private synchronized long currentGeneration() {
        if (invalidationPending.get()) {
            redis.opsForValue().set(GENERATION_KEY, Long.toString(randomGeneration()));
            invalidationPending.set(false);
        }
        String value = redis.opsForValue().get(GENERATION_KEY);
        if (value == null) {
            // A random seed prevents an evicted generation key from reusing an older payload namespace.
            redis.opsForValue().setIfAbsent(GENERATION_KEY, Long.toString(randomGeneration()));
            value = redis.opsForValue().get(GENERATION_KEY);
        }
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String dataKey(long generation) { return "dayu:catalog:v" + generation + ":published"; }
    private static long randomGeneration() { return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2); }
    private static Read miss(long generation) { return new Read(generation, Optional.empty()); }
    private record CatalogPayload(List<ProductDetail> products) {
        private CatalogPayload { products = List.copyOf(products); }
    }
}

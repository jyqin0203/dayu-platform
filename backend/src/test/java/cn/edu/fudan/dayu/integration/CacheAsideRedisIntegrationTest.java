package cn.edu.fudan.dayu.integration;

import cn.edu.fudan.dayu.assetindex.api.AssetIndexChanged;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.catalog.infrastructure.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.discovery.infrastructure.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class CacheAsideRedisIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(128L * 1024 * 1024));

    private final List<LettuceConnectionFactory> connections = new ArrayList<>();
    private StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ProductCode product = new ProductCode("PRECIP");
    private final Instant now = Instant.parse("2026-10-02T00:00:00Z");

    @BeforeEach
    void connect() {
        redis = template(REDIS.getHost(), REDIS.getMappedPort(6379), Duration.ofSeconds(1));
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void close() { connections.forEach(LettuceConnectionFactory::destroy); connections.clear(); }

    @Test
    void catalogMissHitJsonRoundTripAndAfterCommitListenerInvalidation() {
        FakeCatalog delegate = new FakeCatalog(List.of(productDetail()));
        RedisCatalogCache store = new RedisCatalogCache(redis, json, true, Duration.ofMinutes(5));
        CachedCatalogQueryService cached = new CachedCatalogQueryService(delegate, store);

        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());
        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());
        assertThat(delegate.publishedReads.get()).isEqualTo(1);

        cached.catalogChanged(new CatalogChanged(product));
        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());
        assertThat(delegate.publishedReads.get()).isEqualTo(2);
    }

    @Test
    void previewKeysIsolateAllQueryDimensionsAndAssetEventsInvalidateOnlyAffectedProduct() {
        FakeDiscovery delegate = new FakeDiscovery(previewFrame(), cycle());
        RedisDiscoveryCache store = new RedisDiscoveryCache(redis, json, true,
                Duration.ofMinutes(2), Duration.ofMinutes(2));
        CachedDiscoveryQueryService cached = new CachedDiscoveryQueryService(delegate, store);
        PreviewQuery first = previewQuery(product, DataMode.FORECAST, now.minusSeconds(3600), now, now.minusSeconds(7200), 60, 48);

        assertThat(cached.listPreviewFrames(first)).containsExactly(previewFrame());
        assertThat(cached.listPreviewFrames(first)).containsExactly(previewFrame());
        assertThat(delegate.previewReads.get()).isEqualTo(1);

        // A different limit must not collide even though all other fields match.
        cached.listPreviewFrames(previewQuery(product, DataMode.FORECAST, now.minusSeconds(3600), now,
                now.minusSeconds(7200), 60, 49));
        assertThat(delegate.previewReads.get()).isEqualTo(2);

        cached.assetIndexChanged(new AssetIndexChanged(Set.of(new ProductCode("BT855"))));
        cached.listPreviewFrames(first);
        assertThat(delegate.previewReads.get()).isEqualTo(2);

        cached.assetIndexChanged(new AssetIndexChanged(Set.of(product)));
        cached.listPreviewFrames(first);
        assertThat(delegate.previewReads.get()).isEqualTo(3);
    }

    @Test
    void onlyWebpForecastCyclesAreCachedAndHistoricalNcAlwaysBypassesRedis() {
        FakeDiscovery delegate = new FakeDiscovery(previewFrame(), cycle());
        CachedDiscoveryQueryService cached = new CachedDiscoveryQueryService(delegate,
                new RedisDiscoveryCache(redis, json, true, Duration.ofMinutes(2), Duration.ofMinutes(2)));
        ForecastCycleQuery webp = new ForecastCycleQuery(product, AssetType.WEBP, now.minus(Duration.ofDays(3)), now);
        ForecastCycleQuery netcdf = new ForecastCycleQuery(product, AssetType.NETCDF, now.minus(Duration.ofDays(3)), now);

        assertThat(cached.listForecastCycles(webp)).containsExactly(cycle());
        assertThat(cached.listForecastCycles(webp)).containsExactly(cycle());
        cached.listForecastCycles(netcdf);
        cached.listForecastCycles(netcdf);
        assertThat(delegate.cycleReads.get()).isEqualTo(3);

        ScientificAssetQuery nc = new ScientificAssetQuery(product, DataMode.REALTIME,
                now.minus(Duration.ofDays(30)), now, null, null, new PageRequest(1, 20));
        cached.searchScientificAssets(nc);
        cached.searchScientificAssets(nc);
        assertThat(delegate.ncReads.get()).isEqualTo(2);
    }

    @Test
    void malformedCacheValueFallsBackAndRepairsIt() {
        FakeCatalog delegate = new FakeCatalog(List.of(productDetail()));
        CachedCatalogQueryService cached = new CachedCatalogQueryService(delegate,
                new RedisCatalogCache(redis, json, true, Duration.ofMinutes(5)));
        cached.listPublishedProductDetails();
        String dataKey = redis.keys("dayu:catalog:v*:published").iterator().next();
        redis.opsForValue().set(dataKey, "{missing-json");

        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());
        assertThat(delegate.publishedReads.get()).isEqualTo(2);
    }

    @Test
    void evictedGenerationKeysNeverMakeOldPayloadsReachableAgain() {
        FakeCatalog catalogDelegate = new FakeCatalog(List.of(productDetail()));
        CachedCatalogQueryService catalog = new CachedCatalogQueryService(catalogDelegate,
                new RedisCatalogCache(redis, json, true, Duration.ofMinutes(5)));
        catalog.listPublishedProductDetails();
        redis.delete("dayu:catalog:generation");
        catalog.catalogChanged(new CatalogChanged(product));
        catalogDelegate.products = List.of();
        assertThat(catalog.listPublishedProductDetails()).isEmpty();
        assertThat(catalogDelegate.publishedReads.get()).isEqualTo(2);

        FakeDiscovery discoveryDelegate = new FakeDiscovery(previewFrame(), cycle());
        CachedDiscoveryQueryService discovery = new CachedDiscoveryQueryService(discoveryDelegate,
                new RedisDiscoveryCache(redis, json, true, Duration.ofMinutes(2), Duration.ofMinutes(2)));
        PreviewQuery query = previewQuery(product, DataMode.REALTIME, now.minusSeconds(120), now, null, null, 48);
        discovery.listPreviewFrames(query);
        redis.delete("dayu:discovery:PRECIP:generation");
        discovery.assetIndexChanged(new AssetIndexChanged(Set.of(product)));
        discoveryDelegate.preview = null;
        assertThat(discovery.listPreviewFrames(query)).isEmpty();
        assertThat(discoveryDelegate.previewReads.get()).isEqualTo(2);
    }

    @Test
    void invalidationDuringDelegateReadRejectsOldGenerationBackfill() {
        RedisCatalogCache store = new RedisCatalogCache(redis, json, true, Duration.ofMinutes(5));
        long generationBeforeWrite = store.readPublishedProducts().generation();

        store.invalidatePublishedProducts();
        store.writePublishedProducts(generationBeforeWrite, List.of(productDetail()));

        assertThat(store.readPublishedProducts().value()).isEmpty();
        assertThat(redis.hasKey("dayu:catalog:v" + generationBeforeWrite + ":published")).isFalse();
    }

    @Test
    void failedInvalidationIsRetriedBeforeOldPayloadCanBeRead() {
        StringRedisTemplate selectiveFailure = spy(redis);
        ValueOperations<String, String> values = spy(redis.opsForValue());
        doReturn(values).when(selectiveFailure).opsForValue();
        AtomicBoolean failNextInvalidation = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.equals("dayu:catalog:generation") && failNextInvalidation.compareAndSet(true, false))
                throw new RedisConnectionFailureException("offline during invalidation");
            return invocation.callRealMethod();
        }).when(values).set(eq("dayu:catalog:generation"), anyString());

        FakeCatalog delegate = new FakeCatalog(List.of(productDetail()));
        CachedCatalogQueryService cached = new CachedCatalogQueryService(delegate,
                new RedisCatalogCache(selectiveFailure, json, true, Duration.ofMinutes(5)));
        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());

        cached.catalogChanged(new CatalogChanged(product));
        delegate.products = List.of();

        assertThat(cached.listPublishedProductDetails()).isEmpty();
        assertThat(delegate.publishedReads.get()).isEqualTo(2);
        assertThat(failNextInvalidation).isFalse();
    }

    @Test
    void disabledCacheDoesNotTouchRedisClient() {
        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        FakeCatalog delegate = new FakeCatalog(List.of(productDetail()));
        CachedCatalogQueryService cached = new CachedCatalogQueryService(delegate,
                new RedisCatalogCache(untouched, json, false, Duration.ofMinutes(5)));

        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());
        cached.catalogChanged(new CatalogChanged(product));
        verifyNoInteractions(untouched);
    }

    @Test
    void redisConnectionFailureDuringReadFallsBackToDelegate() {
        StringRedisTemplate unavailable = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(unavailable.opsForValue()).thenReturn(values);
        when(values.get("dayu:catalog:generation"))
                .thenThrow(new RedisConnectionFailureException("offline"));
        FakeCatalog delegate = new FakeCatalog(List.of(productDetail()));
        CachedCatalogQueryService cached = new CachedCatalogQueryService(delegate,
                new RedisCatalogCache(unavailable, json, true, Duration.ofMinutes(5)));

        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());
        assertThat(delegate.publishedReads.get()).isEqualTo(1);
    }

    @Test
    void redisFailureDuringCacheFillDoesNotChangeDelegateResult() {
        FakeCatalog delegate = new FakeCatalog(List.of(productDetail()));
        StringRedisTemplate failingWrite = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(failingWrite.opsForValue()).thenReturn(values);
        when(values.get("dayu:catalog:generation")).thenReturn("123", "123");
        doThrow(new RedisConnectionFailureException("offline")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
        CachedCatalogQueryService cached = new CachedCatalogQueryService(delegate,
                new RedisCatalogCache(failingWrite, json, true, Duration.ofMinutes(5)));

        assertThat(cached.listPublishedProductDetails()).containsExactly(productDetail());
        assertThat(delegate.publishedReads.get()).isEqualTo(1);
    }

    private StringRedisTemplate template(String host, int port, Duration timeout) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(timeout).shutdownTimeout(Duration.ZERO).build();
        LettuceConnectionFactory connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        connection.afterPropertiesSet();
        connection.start();
        connections.add(connection);
        StringRedisTemplate result = new StringRedisTemplate(connection);
        result.afterPropertiesSet();
        return result;
    }

    private ProductDetail productDetail() {
        ProductSummary summary = new ProductSummary(new ProductId(7), product, "降水", "Precipitation",
                "REPPIC_PRECIP", "mm/h", "lab", "RePPIC", "AGRI", URI.create("https://example.test/source"),
                ProductStatus.PUBLISHED, 2);
        return new ProductDetail(summary, "说明", "description", false, null,
                List.of(new ProductModePolicy(product, DataMode.REALTIME, true, Duration.ofMinutes(30))),
                now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(2)), now);
    }

    private PreviewFrame previewFrame() {
        return new PreviewFrame(new AssetId(11), URI.create("/media/webp/PRECIP/frame.webp"), now.minusSeconds(60),
                now.minusSeconds(3600), 59, true, 4096);
    }

    private ForecastCycleSummary cycle() {
        return new ForecastCycleSummary(now.minusSeconds(3600), now.minusSeconds(3600), now, Set.of(0, 60), true);
    }

    private static PreviewQuery previewQuery(ProductCode code, DataMode mode, Instant from, Instant to,
                                             Instant cycle, Integer lead, int limit) {
        return new PreviewQuery(code, mode, from, to, cycle, lead, limit);
    }

    private static final class FakeCatalog implements CatalogQueryService {
        private volatile List<ProductDetail> products;
        private final AtomicInteger publishedReads = new AtomicInteger();
        private FakeCatalog(List<ProductDetail> products) { this.products = products; }
        @Override public List<ProductSummary> listPublishedProducts() { return listPublishedProductDetails().stream().map(ProductDetail::summary).toList(); }
        @Override public List<ProductDetail> listPublishedProductDetails() {
            publishedReads.incrementAndGet();
            return products;
        }
        @Override public Optional<ProductDetail> findProduct(ProductCode code) { return products.stream().filter(p -> p.summary().code().equals(code)).findFirst(); }
        @Override public List<ProductSummary> listManagedProducts(ManagedProductQuery query) { return products.stream().map(ProductDetail::summary).toList(); }
        @Override public List<ProductDetail> listManagedProductDetails(ManagedProductQuery query) { return products; }
        @Override public AssetFamilyProductMapping resolveProductsForAssetFamily(String familyCode) { return new AssetFamilyProductMapping(familyCode, Set.of()); }
    }

    private static final class FakeDiscovery implements DiscoveryQueryService {
        private volatile PreviewFrame preview;
        private final ForecastCycleSummary cycle;
        private final AtomicInteger previewReads = new AtomicInteger();
        private final AtomicInteger cycleReads = new AtomicInteger();
        private final AtomicInteger ncReads = new AtomicInteger();
        private FakeDiscovery(PreviewFrame preview, ForecastCycleSummary cycle) { this.preview = preview; this.cycle = cycle; }
        @Override public List<PreviewFrame> listPreviewFrames(PreviewQuery query) { previewReads.incrementAndGet(); return preview == null ? List.of() : List.of(preview); }
        @Override public List<ForecastCycleSummary> listForecastCycles(ForecastCycleQuery query) { cycleReads.incrementAndGet(); return List.of(cycle); }
        @Override public PageResult<ScientificAssetSummary> searchScientificAssets(ScientificAssetQuery query) { ncReads.incrementAndGet(); return new PageResult<>(List.of(), 1, 20, 0); }
        @Override public List<DownloadCandidate> findDownloadCandidatesForPreview(PreviewDownloadQuery query) { return List.of(); }
        @Override public ProductAvailability getProductAvailability(ProductAvailabilityQuery query) { return null; }
        @Override public List<ProductAvailability> listProductAvailability(ProductAvailabilityListQuery query) { return List.of(); }
    }
}

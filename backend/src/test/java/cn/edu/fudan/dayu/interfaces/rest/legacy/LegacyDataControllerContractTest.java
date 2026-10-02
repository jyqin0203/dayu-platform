package cn.edu.fudan.dayu.interfaces.rest.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.fudan.dayu.assetindex.api.DownloadAssetLookup;
import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyDataControllerContractTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final ActorContext ACTOR = new ActorContext(new UserId(7), "复旦大学", UserRole.USER);
    private DiscoveryQueryService discovery;
    private CatalogQueryService catalog;
    private DownloadAssetLookup assets;
    private DownloadAuthorizationService downloads;
    private DownloadContentService contents;
    private CurrentActorProvider actors;
    private LegacyDataController controller;

    @BeforeEach
    void setUp() {
        discovery = mock(DiscoveryQueryService.class);
        catalog = mock(CatalogQueryService.class);
        assets = mock(DownloadAssetLookup.class);
        downloads = mock(DownloadAuthorizationService.class);
        contents = mock(DownloadContentService.class);
        actors = mock(CurrentActorProvider.class);
        controller = new LegacyDataController(discovery, catalog, assets, downloads, contents, actors,
                new LegacyPathParser("WebP/WebP_V2_Dpi500_4KM", "netcdf"),
                "/media/webp/", Duration.ofDays(3), 500, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void invalidPathsAndTimesReturnLegacyEmptyShapesWithoutCallingModules() {
        assertThat(controller.files("WebP/wrong/realtime/BT855", 48)).isEqualTo(Map.of("files", List.of()));
        assertThat(controller.search("multi", "netcdf/%252e%252e", "202610010000", "202610020000", null))
                .isEqualTo(emptySearch());
        assertThat(controller.search("multi", "netcdf/realtime", "202602290000", "202610020000", null))
                .isEqualTo(emptySearch());
        assertThat(controller.latest("WebP/wrong/forecast", null)).isEqualTo(Map.of("latest", ""));
        assertThatThrownBy(() -> controller.download("netcdf/%252e%252e/a.nc",
                "用于研究区域降水变化", new MockHttpServletRequest()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(discovery, catalog, assets, downloads, contents, actors);
    }

    @Test
    void filesUsesRecentWindowAndReturnsLatestFramesInAscendingLegacyPaths() {
        enable("BT855", DataMode.REALTIME);
        when(discovery.listPreviewFrames(any())).thenReturn(List.of(
                frame(1, "realtime/BT855/old.webp", NOW.minusSeconds(120), 1024),
                frame(2, "realtime/BT855/new.webp", NOW.minusSeconds(60), 2048)));

        assertThat(controller.files("WebP/WebP_V2_Dpi500_4KM/realtime/BT855", 2).get("files"))
                .isEqualTo(List.of("WebP/WebP_V2_Dpi500_4KM/realtime/BT855/old.webp",
                        "WebP/WebP_V2_Dpi500_4KM/realtime/BT855/new.webp"));
        var query = org.mockito.ArgumentCaptor.forClass(PreviewQuery.class);
        verify(discovery).listPreviewFrames(query.capture());
        assertThat(query.getValue().from()).isEqualTo(NOW.minus(Duration.ofDays(3)));
        assertThat(query.getValue().to()).isEqualTo(NOW);
        assertThat(query.getValue().limit()).isEqualTo(2);
    }

    @Test
    void forecastFilesUsesRealBatchRangeAndKeepsFutureValidFrames() {
        enable("BT855", DataMode.FORECAST);
        Instant future = NOW.plus(Duration.ofHours(1));
        when(discovery.listForecastCycles(any())).thenReturn(List.of(
                new ForecastCycleSummary(NOW, NOW, future, Set.of(0, 60), true)));
        when(discovery.listPreviewFrames(any())).thenReturn(List.of(
                new PreviewFrame(new AssetId(3), URI.create("/media/webp/forecast/202610021200/BT855/future.webp"),
                        future, NOW, 60, false, 1024)));

        assertThat(controller.files("WebP/WebP_V2_Dpi500_4KM/forecast/202610021200/BT855", 48).get("files"))
                .isEqualTo(List.of("WebP/WebP_V2_Dpi500_4KM/forecast/202610021200/BT855/future.webp"));
        var query = org.mockito.ArgumentCaptor.forClass(PreviewQuery.class);
        verify(discovery).listPreviewFrames(query.capture());
        assertThat(query.getValue().from()).isEqualTo(NOW);
        assertThat(query.getValue().to()).isEqualTo(future);
        assertThat(query.getValue().cycleTime()).isEqualTo(NOW);
    }

    @Test
    void latestWithoutProductUsesNewestRealForecastCycleAcrossPublishedProducts() {
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(
                product(1, "BT855", DataMode.FORECAST), product(2, "CTH", DataMode.FORECAST)));
        when(discovery.listForecastCycles(any())).thenAnswer(invocation -> {
            ForecastCycleQuery query = invocation.getArgument(0);
            return List.of(cycle(query.productCode().value().equals("BT855")
                    ? "2026-10-01T06:00:00Z" : "2026-10-02T06:00:00Z"));
        });

        assertThat(controller.latest("WebP/WebP_V2_Dpi500_4KM/forecast", null))
                .isEqualTo(Map.of("latest", "202610020600"));
    }

    @Test
    void ncSearchReadsEveryPageDeduplicatesSharedAssetsAndUsesIndexedRelativePaths() {
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(
                product(1, "PLP", DataMode.REALTIME), product(2, "PRECIP", DataMode.REALTIME)));
        ScientificAssetSummary shared = scientific(9, "misleading-name.nc", NOW.minusSeconds(60), 20_971_520);
        ScientificAssetSummary older = scientific(8, "also-misleading.nc", NOW.minusSeconds(120), 1_048_576);
        when(discovery.searchScientificAssets(any())).thenAnswer(invocation -> {
            ScientificAssetQuery query = invocation.getArgument(0);
            if (query.productCode().value().equals("PLP") && query.pageRequest().page() == 1)
                return new PageResult<>(List.of(shared), 1, 200, 2);
            if (query.productCode().value().equals("PLP"))
                return new PageResult<>(List.of(older), 2, 200, 2);
            return new PageResult<>(List.of(shared), 1, 200, 1);
        });
        when(assets.findDownloadableAsset(new AssetId(9))).thenReturn(Optional.of(downloadable(
                9, "realtime/20261002/actual-shared.nc", 20_971_520)));
        when(assets.findDownloadableAsset(new AssetId(8))).thenReturn(Optional.of(downloadable(
                8, "realtime/20261002/actual-older.nc", 1_048_576)));

        Map<String, Object> result = controller.search(
                "multi", "netcdf/realtime", "202610010000", "202610021200", null);
        assertThat(result.get("files")).isEqualTo(List.of(
                "netcdf/realtime/20261002/actual-shared.nc",
                "netcdf/realtime/20261002/actual-older.nc"));
        assertThat(result.get("sizes")).isEqualTo(List.of("20.00 MB", "1.00 MB"));
        assertThat(result.get("times")).isEqualTo(List.of("2026-10-02 11:59", "2026-10-02 11:58"));
        verify(discovery, times(3)).searchScientificAssets(any());
        verify(assets, times(1)).findDownloadableAsset(new AssetId(9));
    }

    @Test
    void webpSearchReturnsRealSizesAndSplitsFullDiscoveryWindowsWithoutDuplicates() {
        enable("BT855", DataMode.REALTIME);
        List<PreviewFrame> saturated = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> frame(1, "realtime/BT855/shared.webp", NOW.minusSeconds(60), 1_572_864))
                .toList();
        when(discovery.listPreviewFrames(any())).thenAnswer(invocation -> {
            PreviewQuery query = invocation.getArgument(0);
            if (Duration.between(query.from(), query.to()).toHours() > 24) return saturated;
            long id = query.from().isBefore(NOW.minus(Duration.ofDays(1))) ? 1 : 2;
            return List.of(frame(id, "realtime/BT855/" + id + ".webp", query.to(), id * 1_048_576));
        });

        Map<String, Object> result = controller.search("multi",
                "WebP/WebP_V2_Dpi500_4KM/realtime/BT855", "202609291200", "202610021200", null);
        assertThat(result.get("files")).isEqualTo(List.of(
                "WebP/WebP_V2_Dpi500_4KM/realtime/BT855/2.webp",
                "WebP/WebP_V2_Dpi500_4KM/realtime/BT855/1.webp"));
        assertThat(result.get("sizes")).isEqualTo(List.of("2.00 MB", "1.00 MB"));
        verify(discovery, atLeast(3)).listPreviewFrames(any());
    }

    @Test
    void forecastSearchPreservesFutureEndAndSegmentsByConfiguredRetention() {
        LegacyDataController oneDay = new LegacyDataController(
                discovery, catalog, assets, downloads, contents, actors,
                new LegacyPathParser("WebP/WebP_V2_Dpi500_4KM", "netcdf"),
                "/media/webp/", Duration.ofDays(1), 500, Clock.fixed(NOW, ZoneOffset.UTC));
        enable("BT855", DataMode.FORECAST);
        Instant future = NOW.plus(Duration.ofHours(1));
        when(discovery.listPreviewFrames(any())).thenAnswer(invocation -> {
            PreviewQuery query = invocation.getArgument(0);
            assertThat(Duration.between(query.from(), query.to())).isLessThanOrEqualTo(Duration.ofDays(1));
            if (query.to().equals(future)) {
                return List.of(new PreviewFrame(new AssetId(4),
                        URI.create("/media/webp/forecast/202610021200/BT855/future.webp"),
                        future, NOW, 60, false, 2_097_152));
            }
            return List.of();
        });

        Map<String, Object> result = oneDay.search("multi",
                "WebP/WebP_V2_Dpi500_4KM/forecast/202610021200/BT855",
                "202609291200", "202610021300", null);
        assertThat(result.get("times")).isEqualTo(List.of("2026-10-02 13:00"));
        assertThat(result.get("sizes")).isEqualTo(List.of("2.00 MB"));
        verify(discovery, atLeast(2)).listPreviewFrames(any());
    }

    @Test
    void legacyDownloadReturnsPreparedLocalStreamAndUsesCanonicalStorageKey() throws Exception {
        DownloadableAsset asset = downloadable(9, "forecast/202610020600/sample.nc", 4);
        when(assets.findByStoragePath("netcdf-data", "forecast/202610020600/sample.nc"))
                .thenReturn(Optional.of(asset));
        when(actors.required()).thenReturn(ACTOR);
        Instant authorized = NOW.minusSeconds(1);
        DownloadGrant grant = new DownloadGrant(new DownloadEventId(22), "sample.nc",
                "application/x-netcdf", 4, "/internal-netcdf/sample.nc", authorized, NOW.plusSeconds(60));
        when(downloads.authorizeDownload(any(), eq(ACTOR), any())).thenReturn(grant);
        when(contents.prepareContent(new DownloadEventId(22), ACTOR))
                .thenReturn(new DownloadContent(grant, new ByteArrayInputStream(new byte[]{1, 2, 3, 4})));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "test");
        var response = controller.download("netcdf/forecast/202610020600/sample.nc",
                "用于研究区域降水变化", request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInputStream().readAllBytes()).containsExactly(1, 2, 3, 4);
        assertThat(response.getHeaders().getFirst("X-Accel-Redirect")).isNull();
        verify(contents).prepareContent(new DownloadEventId(22), ACTOR);
    }

    @Test
    void configuredSearchLimitFailsExplicitlyInsteadOfTruncating() {
        LegacyDataController limited = new LegacyDataController(
                discovery, catalog, assets, downloads, contents, actors,
                new LegacyPathParser("WebP/WebP_V2_Dpi500_4KM", "netcdf"),
                "/media/webp/", Duration.ofDays(3), 1, Clock.fixed(NOW, ZoneOffset.UTC));
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(product(1, "PLP", DataMode.REALTIME)));
        when(discovery.searchScientificAssets(any())).thenReturn(new PageResult<>(List.of(
                scientific(1, "one.nc", NOW, 1), scientific(2, "two.nc", NOW.minusSeconds(60), 1)),
                1, 200, 2));

        assertThatThrownBy(() -> limited.search(
                "multi", "netcdf/realtime", "202610010000", "202610021200", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(assets);
    }

    @Test
    void unexpectedModuleFailureIsARealLegacy500RatherThanAnEmptySuccess() throws Exception {
        enable("BT855", DataMode.REALTIME);
        when(discovery.listPreviewFrames(any())).thenThrow(new IllegalStateException("database unavailable"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new LegacyExceptionHandler()).build();
        mvc.perform(get("/api/files.php")
                        .param("path", "WebP/WebP_V2_Dpi500_4KM/realtime/BT855"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    private void enable(String code, DataMode mode) {
        when(catalog.findProduct(new ProductCode(code))).thenReturn(Optional.of(product(1, code, mode)));
    }

    private static ProductDetail product(long id, String value, DataMode mode) {
        ProductCode code = new ProductCode(value);
        ProductSummary summary = new ProductSummary(new ProductId(id), code, value, value, "TEST", null,
                "lab", null, "test", null, ProductStatus.PUBLISHED, (int) id);
        return new ProductDetail(summary, "", "", false, null,
                List.of(new ProductModePolicy(code, mode, true, Duration.ofHours(1))), NOW, NOW, NOW);
    }

    private static PreviewFrame frame(long id, String relativePath, Instant validTime, long size) {
        return new PreviewFrame(new AssetId(id), URI.create("/media/webp/" + relativePath), validTime,
                null, null, false, size);
    }

    private static ForecastCycleSummary cycle(String time) {
        Instant cycle = Instant.parse(time);
        return new ForecastCycleSummary(cycle, cycle, cycle.plusSeconds(3600), Set.of(60), true);
    }

    private static ScientificAssetSummary scientific(long id, String fileName, Instant time, long size) {
        return new ScientificAssetSummary(new AssetId(id), fileName, size, Set.of(new ProductCode("PLP")),
                DataMode.REALTIME, null, time, null, AssetStatus.AVAILABLE);
    }

    private static DownloadableAsset downloadable(long id, String path, long size) {
        return new DownloadableAsset(new AssetId(id), AssetType.NETCDF, AssetStatus.AVAILABLE,
                Set.of(new ProductCode("PLP")), "netcdf-data", path,
                path.substring(path.lastIndexOf('/') + 1), size);
    }

    private static Map<String, Object> emptySearch() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", List.of());
        result.put("sizes", List.of());
        result.put("times", List.of());
        return result;
    }
}

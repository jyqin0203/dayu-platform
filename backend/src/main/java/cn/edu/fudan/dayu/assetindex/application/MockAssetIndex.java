package cn.edu.fudan.dayu.assetindex.application;

import cn.edu.fudan.dayu.assetindex.api.AssetAdminQueryService;
import cn.edu.fudan.dayu.assetindex.api.AssetForecastCycleCriteria;
import cn.edu.fudan.dayu.assetindex.api.AssetIndexCommandService;
import cn.edu.fudan.dayu.assetindex.api.AssetMatchCriteria;
import cn.edu.fudan.dayu.assetindex.api.AssetPreviewCriteria;
import cn.edu.fudan.dayu.assetindex.api.AssetQueryService;
import cn.edu.fudan.dayu.assetindex.api.AssetScanResult;
import cn.edu.fudan.dayu.assetindex.api.AssetSearchCriteria;
import cn.edu.fudan.dayu.assetindex.api.DownloadAssetLookup;
import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.assetindex.api.ForecastCycleSummary;
import cn.edu.fudan.dayu.assetindex.api.IndexedAssetView;
import cn.edu.fudan.dayu.assetindex.api.ScanHistoryQuery;
import cn.edu.fudan.dayu.assetindex.api.ScanHistorySummary;
import cn.edu.fudan.dayu.assetindex.api.ScanTrigger;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * AssetIndex 在 skeleton Profile 下使用的内存模拟实现。
 *
 * <p>它用固定 WebP 和 NetCDF 元数据模拟扫描与查询结果，不访问真实文件系统、
 * 数据库或缓存，只用于验证四个公开接口及跨模块协作。</p>
 */
@Service
@Profile("skeleton")
class MockAssetIndex implements AssetIndexCommandService, AssetQueryService, DownloadAssetLookup, AssetAdminQueryService {
    private static final ProductCode BT855 = new ProductCode("BT855");
    private static final ProductCode PRECIP = new ProductCode("PRECIP");
    private static final ProductCode PLP = new ProductCode("PLP");
    private static final Instant CYCLE = Instant.parse("2026-09-02T06:00:00Z");
    private static final Instant FORECAST_VALID = Instant.parse("2026-09-02T08:00:00Z");
    private final CatalogQueryService catalog;
    private final List<IndexedAssetView> assets;
    private AssetScanResult latestScan;

    MockAssetIndex(CatalogQueryService catalog) {
        this.catalog = catalog;
        this.assets = List.of(
                new IndexedAssetView(new AssetId(81002), AssetType.WEBP, Set.of(BT855), DataMode.REALTIME,
                        null, Instant.parse("2026-09-28T02:00:00Z"), null, "BT855_202609280200.webp",
                        512_000, 500, AssetStatus.AVAILABLE),
                new IndexedAssetView(new AssetId(92002), AssetType.NETCDF, Set.of(PLP, PRECIP), DataMode.FORECAST,
                        CYCLE, FORECAST_VALID, 120,
                        "FY4B_AGRI_REPPIC_PRECIP_2H_202609020600_202609020800.nc",
                        20_349_278, null, AssetStatus.AVAILABLE));
        this.latestScan = scanResult(ScanTrigger.STARTUP);
    }

    @Override
    public AssetScanResult runInitialFullScan() {
        latestScan = scanResult(ScanTrigger.STARTUP);
        return latestScan;
    }

    @Override
    public AssetScanResult runIncrementalScan(ScanTrigger trigger) {
        catalog.resolveProductsForAssetFamily("PRECIP");
        latestScan = scanResult(trigger);
        return latestScan;
    }

    private AssetScanResult scanResult(ScanTrigger trigger) {
        Instant started = Instant.parse("2026-09-29T02:15:00Z");
        return new AssetScanResult(trigger, started, started.plusSeconds(2), 2, 0, 0, 0, 0,
                Set.of(BT855, PLP, PRECIP), List.of());
    }

    @Override
    public List<IndexedAssetView> listPreviewAssets(AssetPreviewCriteria criteria) {
        return assets.stream().filter(a -> a.assetType() == AssetType.WEBP)
                .filter(a -> a.products().contains(criteria.productCode()))
                .filter(a -> a.dataMode() == criteria.dataMode()).limit(criteria.limit()).toList();
    }

    @Override
    public List<ForecastCycleSummary> listForecastCycles(AssetForecastCycleCriteria criteria) {
        boolean exists = assets.stream().anyMatch(a -> a.assetType() == AssetType.NETCDF
                && a.products().contains(criteria.productCode()) && a.dataMode() == DataMode.FORECAST);
        return exists ? List.of(new ForecastCycleSummary(CYCLE, FORECAST_VALID, FORECAST_VALID, Set.of(120), true))
                : List.of();
    }

    @Override
    public PageResult<IndexedAssetView> searchNetcdfAssets(AssetSearchCriteria criteria) {
        List<IndexedAssetView> found = assets.stream().filter(a -> a.assetType() == AssetType.NETCDF)
                .filter(a -> a.products().contains(criteria.productCode()))
                .filter(a -> a.dataMode() == criteria.dataMode()).toList();
        PageRequest page = criteria.pageRequest();
        return new PageResult<>(found, page.page(), page.size(), found.size());
    }

    @Override
    public List<IndexedAssetView> findNetcdfCandidates(AssetMatchCriteria criteria) {
        return assets.stream().filter(a -> a.assetType() == AssetType.NETCDF)
                .filter(a -> a.products().contains(criteria.productCode()))
                .filter(a -> a.validTime().equals(criteria.validTime())).toList();
    }

    @Override
    public Optional<DownloadableAsset> findDownloadableAsset(AssetId assetId) {
        return assets.stream().filter(a -> a.assetId().equals(assetId) && a.assetType() == AssetType.NETCDF)
                .findFirst().map(a -> new DownloadableAsset(a.assetId(), a.assetType(), a.status(), a.products(),
                        "netcdf-science", "forecast/202609020600/" + a.fileName(), a.fileName(), a.fileSize()));
    }

    @Override
    public Optional<AssetScanResult> getLatestScanResult() {
        return Optional.of(latestScan);
    }

    @Override
    public PageResult<ScanHistorySummary> searchScanHistory(ScanHistoryQuery query) {
        ScanHistorySummary summary = new ScanHistorySummary(latestScan.trigger(), latestScan.startedAt(),
                latestScan.finishedAt(), latestScan.scannedFiles(),
                latestScan.createdAssets() + latestScan.updatedAssets(), latestScan.errors().size());
        return new PageResult<>(List.of(summary), query.pageRequest().page(), query.pageRequest().size(), 1);
    }
}

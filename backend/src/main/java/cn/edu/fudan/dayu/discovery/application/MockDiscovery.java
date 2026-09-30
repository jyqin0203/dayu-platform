package cn.edu.fudan.dayu.discovery.application;

import cn.edu.fudan.dayu.assetindex.api.AssetForecastCycleCriteria;
import cn.edu.fudan.dayu.assetindex.api.AssetMatchCriteria;
import cn.edu.fudan.dayu.assetindex.api.AssetPreviewCriteria;
import cn.edu.fudan.dayu.assetindex.api.AssetQueryService;
import cn.edu.fudan.dayu.assetindex.api.AssetSearchCriteria;
import cn.edu.fudan.dayu.assetindex.api.IndexedAssetView;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.discovery.api.DownloadCandidate;
import cn.edu.fudan.dayu.discovery.api.ForecastCycleQuery;
import cn.edu.fudan.dayu.discovery.api.ForecastCycleSummary;
import cn.edu.fudan.dayu.discovery.api.PreviewDownloadQuery;
import cn.edu.fudan.dayu.discovery.api.PreviewFrame;
import cn.edu.fudan.dayu.discovery.api.PreviewQuery;
import cn.edu.fudan.dayu.discovery.api.ProductAvailability;
import cn.edu.fudan.dayu.discovery.api.ProductAvailabilityListQuery;
import cn.edu.fudan.dayu.discovery.api.ProductAvailabilityQuery;
import cn.edu.fudan.dayu.discovery.api.ProductHealthStatus;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetQuery;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetSummary;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.net.URI;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Discovery 在 skeleton Profile 下使用的查询编排实现。
 *
 * <p>它先通过 Catalog 确认产品状态，再通过 AssetIndex 的公开查询接口获取
 * 固定索引数据，最后转换为 Discovery 对外 DTO。</p>
 */
@Service
@Profile("skeleton")
class MockDiscovery implements DiscoveryQueryService {
    private final CatalogQueryService catalog;
    private final AssetQueryService assets;

    MockDiscovery(CatalogQueryService catalog, AssetQueryService assets) {
        this.catalog = catalog;
        this.assets = assets;
    }

    @Override
    public List<PreviewFrame> listPreviewFrames(PreviewQuery query) {
        requirePublished(query.productCode());
        return assets.listPreviewAssets(new AssetPreviewCriteria(query.productCode(), query.dataMode(), query.from(),
                        query.to(), query.cycleTime(), query.leadMinutes(), query.limit())).stream()
                .map(a -> new PreviewFrame(a.assetId(), URI.create("/media/webp/" + a.fileName()), a.validTime(),
                        a.cycleTime(), a.leadMinutes(), false)).toList();
    }

    @Override
    public List<ForecastCycleSummary> listForecastCycles(ForecastCycleQuery query) {
        requirePublished(query.productCode());
        return assets.listForecastCycles(new AssetForecastCycleCriteria(query.productCode(), query.from(), query.to()))
                .stream().map(c -> new ForecastCycleSummary(c.cycleTime(), c.firstValidTime(), c.lastValidTime(),
                        c.leadMinutes(), c.complete())).toList();
    }

    @Override
    public PageResult<ScientificAssetSummary> searchScientificAssets(ScientificAssetQuery query) {
        requirePublished(query.productCode());
        PageResult<IndexedAssetView> indexed = assets.searchNetcdfAssets(new AssetSearchCriteria(query.productCode(),
                query.dataMode(), query.from(), query.to(), query.cycleTime(), query.leadMinutes(), query.pageRequest()));
        List<ScientificAssetSummary> items = indexed.items().stream().map(this::toScientificSummary).toList();
        return new PageResult<>(items, indexed.page(), indexed.size(), indexed.total());
    }

    @Override
    public List<DownloadCandidate> findDownloadCandidatesForPreview(PreviewDownloadQuery query) {
        requirePublished(query.productCode());
        return assets.findNetcdfCandidates(new AssetMatchCriteria(query.productCode(), query.dataMode(),
                        query.validTime(), query.cycleTime(), query.leadMinutes())).stream()
                .map(a -> new DownloadCandidate(a.assetId(), a.fileName(), a.fileSize(), a.validTime())).toList();
    }

    @Override
    public ProductAvailability getProductAvailability(ProductAvailabilityQuery query) {
        var product = catalog.findProduct(query.productCode());
        if (product.isEmpty() || product.get().summary().status() != ProductStatus.PUBLISHED) {
            return new ProductAvailability(query.productCode(), query.dataMode(), false, false, null, null,
                    ProductHealthStatus.DISABLED);
        }
        boolean preview = !assets.listPreviewAssets(new AssetPreviewCriteria(query.productCode(), query.dataMode(),
                null, null, null, null, 1)).isEmpty();
        var nc = assets.searchNetcdfAssets(new AssetSearchCriteria(query.productCode(), query.dataMode(),
                null, null, null, null, new cn.edu.fudan.dayu.shared.kernel.PageRequest(1, 1)));
        if (!preview && nc.items().isEmpty()) {
            return new ProductAvailability(query.productCode(), query.dataMode(), false, false, null, null,
                    ProductHealthStatus.MISSING);
        }
        IndexedAssetView newest = preview
                ? assets.listPreviewAssets(new AssetPreviewCriteria(query.productCode(), query.dataMode(),
                        null, null, null, null, 1)).get(0)
                : nc.items().get(0);
        return new ProductAvailability(query.productCode(), query.dataMode(), preview, !nc.items().isEmpty(),
                newest.validTime(), newest.cycleTime(), ProductHealthStatus.HEALTHY);
    }

    @Override
    public List<ProductAvailability> listProductAvailability(ProductAvailabilityListQuery query) {
        return query.productCodes().stream()
                .map(code -> getProductAvailability(new ProductAvailabilityQuery(code, query.dataMode()))).toList();
    }

    private ScientificAssetSummary toScientificSummary(IndexedAssetView a) {
        if (a.assetType() != AssetType.NETCDF) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "索引返回了非NetCDF资产");
        }
        return new ScientificAssetSummary(a.assetId(), a.fileName(), a.fileSize(), a.products(), a.dataMode(),
                a.cycleTime(), a.validTime(), a.leadMinutes(), a.status());
    }

    private void requirePublished(ProductCode code) {
        var product = catalog.findProduct(code);
        if (product.isEmpty() || product.get().summary().status() != ProductStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在或尚未发布");
        }
    }
}

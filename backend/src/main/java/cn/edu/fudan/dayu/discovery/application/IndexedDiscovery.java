package cn.edu.fudan.dayu.discovery.application;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 数据发现的真实编排：仅调用 Catalog 和 AssetIndex API，不访问磁盘或跨模块 SQL。
 * UTC 时间窗口与文件可用状态分别由本层及索引查询共同约束。
 */
@Service
@Profile("!skeleton")
public class IndexedDiscovery implements DiscoveryQueryService {
    private final CatalogQueryService catalog;
    private final AssetQueryService assets;
    private final Clock clock;
    private final Duration window;
    private final String previewPrefix;

    @Autowired
    public IndexedDiscovery(CatalogQueryService catalog, AssetQueryService assets,
            @Value("${dayu.preview.retention:3d}") Duration window,
            @Value("${dayu.preview.public-prefix:/media/webp/}") String previewPrefix) {
        this(catalog, assets, Clock.systemUTC(), window, previewPrefix);
    }

    IndexedDiscovery(CatalogQueryService catalog, AssetQueryService assets, Clock clock,
                     Duration window, String prefix) {
        this.catalog = catalog;
        this.assets = assets;
        this.clock = clock;
        this.window = window;
        if (window.isNegative() || window.isZero() || !prefix.startsWith("/") || prefix.startsWith("//")
                || prefix.contains("..") || prefix.contains("?") || prefix.contains("#")) {
            throw new IllegalArgumentException("Invalid preview configuration");
        }
        this.previewPrefix = prefix.endsWith("/") ? prefix : prefix + "/";
    }

    @Override
    public List<PreviewFrame> listPreviewFrames(PreviewQuery q) {
        requireProduct(q.productCode(), q.dataMode());
        validate(q.dataMode(), q.from(), q.to(), q.cycleTime(), q.leadMinutes());
        if (q.limit() < 1 || q.limit() > 200 || Duration.between(q.from(), q.to()).compareTo(window) > 0)
            throw invalid("预览窗口或帧数超出配置范围");
        Instant from = q.from().isBefore(clock.instant().minus(window))
                ? clock.instant().minus(window) : q.from();
        if (from.isAfter(q.to())) return List.of();
        return assets.listPreviewAssets(new AssetPreviewCriteria(q.productCode(), q.dataMode(),
                from, q.to(), q.cycleTime(), q.leadMinutes(), q.limit())).stream()
                .filter(a -> a.status() == AssetStatus.AVAILABLE)
                .sorted(Comparator.comparing(IndexedAssetView::validTime)
                        .thenComparing(a -> a.assetId().value()))
                .map(a -> new PreviewFrame(a.assetId(), previewUrl(a), a.validTime(),
                        a.cycleTime(), a.leadMinutes(),
                        !assets.findNetcdfCandidates(new AssetMatchCriteria(q.productCode(), q.dataMode(),
                                a.validTime(), a.cycleTime(), a.leadMinutes())).isEmpty(),
                        a.fileSize()))
                .toList();
    }

    @Override
    public List<cn.edu.fudan.dayu.discovery.api.ForecastCycleSummary> listForecastCycles(ForecastCycleQuery q) {
        requireProduct(q.productCode(), DataMode.FORECAST);
        range(q.from(), q.to());
        if (q.assetType() == null) throw invalid("assetType不能为空");
        return assets.listForecastCycles(new AssetForecastCycleCriteria(q.productCode(), q.assetType(),
                q.from(), q.to())).stream()
                .sorted(Comparator.comparing(cn.edu.fudan.dayu.assetindex.api.ForecastCycleSummary::cycleTime).reversed())
                .map(c -> new cn.edu.fudan.dayu.discovery.api.ForecastCycleSummary(c.cycleTime(),
                        c.firstValidTime(), c.lastValidTime(), c.leadMinutes(), c.complete())).toList();
    }

    @Override
    public PageResult<ScientificAssetSummary> searchScientificAssets(ScientificAssetQuery q) {
        requireProduct(q.productCode(), q.dataMode());
        validate(q.dataMode(), q.from(), q.to(), q.cycleTime(), q.leadMinutes());
        if (q.pageRequest() == null) throw invalid("分页条件不能为空");
        var result = assets.searchNetcdfAssets(new AssetSearchCriteria(q.productCode(), q.dataMode(),
                q.from(), q.to(), q.cycleTime(), q.leadMinutes(), q.pageRequest()));
        return new PageResult<>(result.items().stream().map(this::scientific).toList(),
                result.page(), result.size(), result.total());
    }

    @Override
    public List<DownloadCandidate> findDownloadCandidatesForPreview(PreviewDownloadQuery q) {
        requireProduct(q.productCode(), q.dataMode());
        validate(q.dataMode(), q.validTime(), q.validTime(), q.cycleTime(), q.leadMinutes());
        return assets.findNetcdfCandidates(new AssetMatchCriteria(q.productCode(), q.dataMode(),
                q.validTime(), q.cycleTime(), q.leadMinutes())).stream()
                .filter(a -> a.status() == AssetStatus.AVAILABLE)
                .map(a -> new DownloadCandidate(a.assetId(), a.fileName(), a.fileSize(), a.validTime())).toList();
    }

    @Override
    public ProductAvailability getProductAvailability(ProductAvailabilityQuery q) {
        var product = catalog.findProduct(q.productCode());
        return availability(q.productCode(), q.dataMode(), product.orElse(null));
    }

    @Override
    public List<ProductAvailability> listProductAvailability(ProductAvailabilityListQuery q) {
        // 一次批量读取产品；资产查询仍按产品进行，后续可在索引端扩展批量汇总。
        Map<ProductCode, ProductDetail> products = new HashMap<>();
        catalog.listPublishedProductDetails().forEach(p -> products.put(p.summary().code(), p));
        return q.productCodes().stream().sorted(Comparator.comparing(ProductCode::value))
                .map(code -> availability(code, q.dataMode(), products.get(code))).toList();
    }

    private ProductAvailability availability(ProductCode code, DataMode mode, ProductDetail product) {
        var policy = product == null ? Optional.<ProductModePolicy>empty()
                : product.modePolicies().stream().filter(p -> p.dataMode() == mode && p.enabled()).findFirst();
        if (product == null || product.summary().status() != ProductStatus.PUBLISHED || policy.isEmpty())
            return new ProductAvailability(code, mode, false, false, null, null, ProductHealthStatus.DISABLED);
        var preview = assets.listPreviewAssets(new AssetPreviewCriteria(code, mode,
                clock.instant().minus(window), null, null, null, 200));
        var nc = assets.searchNetcdfAssets(new AssetSearchCriteria(code, mode, null, null,
                null, null, new PageRequest(1, 1))).items();
        var newest = java.util.stream.Stream.concat(preview.stream(), nc.stream())
                .filter(a -> a.status() == AssetStatus.AVAILABLE)
                .max(Comparator.comparing(a -> mode == DataMode.FORECAST ? a.cycleTime() : a.validTime()));
        if (newest.isEmpty())
            return new ProductAvailability(code, mode, false, false, null, null, ProductHealthStatus.MISSING);
        var a = newest.get();
        Instant freshnessTime = mode == DataMode.FORECAST ? a.cycleTime() : a.validTime();
        boolean stale = freshnessTime.plus(policy.get().staleAfter()).isBefore(clock.instant());
        return new ProductAvailability(code, mode, !preview.isEmpty(), !nc.isEmpty(),
                a.validTime(), a.cycleTime(), stale ? ProductHealthStatus.STALE : ProductHealthStatus.HEALTHY);
    }

    private void requireProduct(ProductCode code, DataMode mode) {
        var product = catalog.findProduct(code).filter(p -> p.summary().status() == ProductStatus.PUBLISHED)
                .filter(p -> p.modePolicies().stream().anyMatch(m -> m.dataMode() == mode && m.enabled()));
        if (product.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "产品或数据模式不可用");
    }

    private ScientificAssetSummary scientific(IndexedAssetView a) {
        if (a.assetType() != AssetType.NETCDF || a.status() != AssetStatus.AVAILABLE)
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "索引返回了不可用科学资产");
        return new ScientificAssetSummary(a.assetId(), a.fileName(), a.fileSize(), a.products(),
                a.dataMode(), a.cycleTime(), a.validTime(), a.leadMinutes(), a.status());
    }

    private URI previewUrl(IndexedAssetView a) {
        String path = a.previewRelativePath();
        if (a.assetType() != AssetType.WEBP || path == null || path.startsWith("/") || path.contains("\\")
                || Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isBlank() || s.equals("..") || s.equals("."))
                || path.chars().anyMatch(c -> c < 32 || c == 127) || path.contains(":"))
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "预览资源配置不可用");
        try { return new URI(null, null, previewPrefix + path, null); }
        catch (URISyntaxException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "预览资源配置不可用"); }
    }

    private static void range(Instant from, Instant to) {
        if (from == null || to == null || from.isAfter(to)) throw invalid("时间范围不合法");
    }

    private static void validate(DataMode mode, Instant from, Instant to, Instant cycle, Integer lead) {
        range(from, to);
        if (mode == null || (mode == DataMode.REALTIME && (cycle != null || lead != null))
                || (lead != null && lead < 0)) throw invalid("数据模式与预报参数不匹配");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}

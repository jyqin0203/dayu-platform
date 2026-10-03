package cn.edu.fudan.dayu.assetindex.application;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.assetindex.domain.AssetFilenameParser;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 实际索引查询：Catalog API 解析产品身份，仓储只查询自身资产与关联表。 */
@Service
@Profile("!skeleton")
public class IndexedAssetQueries implements AssetQueryService, DownloadAssetLookup {
    private final AssetIndexStore store;
    private final CatalogQueryService catalog;
    private final IndexSettings settings;

    public IndexedAssetQueries(AssetIndexStore store, CatalogQueryService catalog, IndexSettings settings) {
        this.store = store; this.catalog = catalog; this.settings = settings;
    }

    @Override
    public List<IndexedAssetView> listPreviewAssets(AssetPreviewCriteria c) {
        validate(c.productCode(),c.dataMode(),c.from(),c.to(),c.cycleTime(),c.leadMinutes());
        if (c.limit() < 1 || c.limit() > 200) throw invalid();
        var products = productMap();
        long id = productId(products,c.productCode());
        if (id == 0) return List.of();
        // 先取最新 N 帧，再正序交付播放器，不能先截取最早 N 帧。
        var rows = new ArrayList<>(store.query(new AssetIndexStore.Filter(id,AssetType.WEBP,c.dataMode(),c.from(),c.to(),c.cycleTime(),c.leadMinutes()),c.limit(),0,false));
        Collections.reverse(rows);
        return rows.stream().map(r -> view(r,products)).toList();
    }

    @Override
    public List<ForecastCycleSummary> listForecastCycles(AssetForecastCycleCriteria c) {
        if (c.productCode()==null || c.assetType()==null || c.from()!=null && c.to()!=null && c.from().isAfter(c.to())) throw invalid();
        long id = productId(productMap(),c.productCode());
        if (id == 0) return List.of();
        Set<Integer> expected = settings.getExpectedLeads().getOrDefault(c.productCode().value(),
                c.assetType()==AssetType.WEBP && c.productCode().value().equals("PRECIP") ? Set.of(60,120,180) : Set.of());
        return store.cycles(id,c.assetType(),c.from(),c.to()).stream().map(row -> new ForecastCycleSummary(row.cycleTime(),
                row.firstValidTime(),row.lastValidTime(),row.leadMinutes(),!expected.isEmpty() && row.leadMinutes().containsAll(expected))).toList();
    }

    @Override
    public PageResult<IndexedAssetView> searchNetcdfAssets(AssetSearchCriteria c) {
        validate(c.productCode(),c.dataMode(),c.from(),c.to(),c.cycleTime(),c.leadMinutes());
        if (c.pageRequest() == null) throw invalid();
        var products = productMap();
        long id = productId(products,c.productCode());
        var p = c.pageRequest();
        if (id == 0) return new PageResult<>(List.of(),p.page(),p.size(),0);
        var filter = new AssetIndexStore.Filter(id,AssetType.NETCDF,c.dataMode(),c.from(),c.to(),c.cycleTime(),c.leadMinutes());
        var rows = store.query(filter,p.size(),(p.page()-1L)*p.size(),false);
        return new PageResult<>(rows.stream().map(r -> view(r,products)).toList(),p.page(),p.size(),store.count(filter));
    }

    @Override
    public List<IndexedAssetView> findNetcdfCandidates(AssetMatchCriteria c) {
        if (c.validTime()==null) throw invalid();
        validate(c.productCode(),c.dataMode(),c.validTime(),c.validTime(),c.cycleTime(),c.leadMinutes());
        var products = productMap();
        long id = productId(products,c.productCode());
        if (id == 0) return List.of();
        return store.query(new AssetIndexStore.Filter(id,AssetType.NETCDF,c.dataMode(),c.validTime(),c.validTime(),c.cycleTime(),c.leadMinutes()),
                0,0,false).stream().map(r -> view(r,products)).toList();
    }

    @Override
    public Optional<DownloadableAsset> findDownloadableAsset(AssetId id) {
        if (id == null || id.value() < 1) throw invalid();
        return store.find(id.value()).filter(r -> r.asset().assetType()==AssetType.NETCDF).map(this::download);
    }

    @Override
    public Optional<DownloadableAsset> findByStoragePath(String key, String path) {
        try { AssetFilenameParser.validateRelativePath(path); }
        catch (IllegalArgumentException e) { throw invalid(); }
        // 不把旧 Mock 名称 netcdf-science 静默映射到其他空间，避免忽略 storageKey。
        if (!"netcdf-data".equals(key)) return Optional.empty();
        return store.findByPath(key,path).filter(r -> r.asset().assetType()==AssetType.NETCDF).map(this::download);
    }

    private DownloadableAsset download(AssetIndexStore.Row row) {
        var a = row.asset();
        return new DownloadableAsset(a.id(),a.assetType(),a.status(),codes(row,productMap()),a.storageKey(),a.relativePath(),a.fileName(),a.fileSize());
    }

    private Map<Long,ProductCode> productMap() {
        return catalog.listManagedProducts(new ManagedProductQuery(null,null,null)).stream()
                .collect(Collectors.toMap(p -> p.id().value(),ProductSummary::code));
    }
    private long productId(Map<Long,ProductCode> products, ProductCode code) {
        return products.entrySet().stream().filter(e -> e.getValue().equals(code)).mapToLong(Map.Entry::getKey).findFirst().orElse(0);
    }
    private Set<ProductCode> codes(AssetIndexStore.Row row, Map<Long,ProductCode> products) {
        return row.productIds().stream().map(products::get).filter(Objects::nonNull).collect(Collectors.toSet());
    }
    private IndexedAssetView view(AssetIndexStore.Row row, Map<Long,ProductCode> products) {
        var a = row.asset();
        return new IndexedAssetView(a.id(),a.assetType(),codes(row,products),a.dataMode(),a.cycleTime(),a.validTime(),a.leadMinutes(),
                a.fileName(),a.fileSize(),a.dpi(),a.status(),a.assetType()==AssetType.WEBP ? a.relativePath() : null);
    }
    private void validate(ProductCode product,DataMode mode,Instant from,Instant to,Instant cycle,Integer lead) {
        if (product==null || mode==null || from!=null && to!=null && from.isAfter(to)
                || lead!=null && lead<0 || mode==DataMode.REALTIME && (cycle!=null || lead!=null)) throw invalid();
    }
    private BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED,"Invalid asset query"); }
}

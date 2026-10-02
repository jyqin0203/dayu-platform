package cn.edu.fudan.dayu.discovery.infrastructure;

import cn.edu.fudan.dayu.assetindex.api.AssetIndexChanged;
import cn.edu.fudan.dayu.catalog.api.CatalogChanged;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.discovery.application.DiscoveryCachePort;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Cache-Aside 装饰器，只覆盖近期 WebP 时间轴及 WebP 预报批次。 */
@Service
@Primary
@Profile("!skeleton")
public class CachedDiscoveryQueryService implements DiscoveryQueryService {
    private final DiscoveryQueryService delegate;
    private final DiscoveryCachePort cache;

    public CachedDiscoveryQueryService(@Qualifier("indexedDiscovery") DiscoveryQueryService delegate,
                                       DiscoveryCachePort cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public List<PreviewFrame> listPreviewFrames(PreviewQuery query) {
        DiscoveryCachePort.Read<PreviewFrame> read;
        try { read = cache.readPreviewFrames(query); }
        catch (RuntimeException ignored) { return delegate.listPreviewFrames(query); }
        if (read.value().isPresent()) return read.value().orElseThrow();
        List<PreviewFrame> result = delegate.listPreviewFrames(query);
        try { cache.writePreviewFrames(query, read.generation(), result); }
        catch (RuntimeException ignored) { /* cache failure must not change the query result */ }
        return result;
    }

    @Override
    public List<ForecastCycleSummary> listForecastCycles(ForecastCycleQuery query) {
        DiscoveryCachePort.Read<ForecastCycleSummary> read;
        try { read = cache.readForecastCycles(query); }
        catch (RuntimeException ignored) { return delegate.listForecastCycles(query); }
        if (read.value().isPresent()) return read.value().orElseThrow();
        List<ForecastCycleSummary> result = delegate.listForecastCycles(query);
        try { cache.writeForecastCycles(query, read.generation(), result); }
        catch (RuntimeException ignored) { /* cache failure must not change the query result */ }
        return result;
    }

    // Historical NC, candidates and health aggregation deliberately bypass Redis.
    @Override public PageResult<ScientificAssetSummary> searchScientificAssets(ScientificAssetQuery query) { return delegate.searchScientificAssets(query); }
    @Override public List<DownloadCandidate> findDownloadCandidatesForPreview(PreviewDownloadQuery query) { return delegate.findDownloadCandidatesForPreview(query); }
    @Override public ProductAvailability getProductAvailability(ProductAvailabilityQuery query) { return delegate.getProductAvailability(query); }
    @Override public List<ProductAvailability> listProductAvailability(ProductAvailabilityListQuery query) { return delegate.listProductAvailability(query); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void catalogChanged(CatalogChanged event) { safeInvalidate(event.productCode()); }

    @EventListener
    public void assetIndexChanged(AssetIndexChanged event) { event.products().forEach(this::safeInvalidate); }

    private void safeInvalidate(cn.edu.fudan.dayu.shared.kernel.ProductCode code) {
        try { cache.invalidate(code); }
        catch (RuntimeException ignored) { /* Redis failure is never a business-query failure */ }
    }
}

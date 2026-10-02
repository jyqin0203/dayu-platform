package cn.edu.fudan.dayu.catalog.infrastructure;

import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.catalog.application.CatalogCachePort;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Cache-Aside 装饰器；仅缓存公开目录，管理员查询始终访问 Catalog 事实来源。 */
@Service
@Primary
@Profile("!skeleton")
public class CachedCatalogQueryService implements CatalogQueryService {
    private final CatalogQueryService delegate;
    private final CatalogCachePort cache;

    public CachedCatalogQueryService(@Qualifier("catalogService") CatalogQueryService delegate, CatalogCachePort cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public List<ProductSummary> listPublishedProducts() {
        return listPublishedProductDetails().stream().map(ProductDetail::summary).toList();
    }

    @Override
    public List<ProductDetail> listPublishedProductDetails() {
        CatalogCachePort.Read read;
        try { read = cache.readPublishedProducts(); }
        catch (RuntimeException ignored) { return delegate.listPublishedProductDetails(); }
        if (read.value().isPresent()) return read.value().orElseThrow();
        List<ProductDetail> result = delegate.listPublishedProductDetails();
        try { cache.writePublishedProducts(read.generation(), result); }
        catch (RuntimeException ignored) { /* cache failure must not change the query result */ }
        return result;
    }

    @Override public Optional<ProductDetail> findProduct(ProductCode code) { return delegate.findProduct(code); }
    @Override public List<ProductSummary> listManagedProducts(ManagedProductQuery query) { return delegate.listManagedProducts(query); }
    @Override public List<ProductDetail> listManagedProductDetails(ManagedProductQuery query) { return delegate.listManagedProductDetails(query); }
    @Override public AssetFamilyProductMapping resolveProductsForAssetFamily(String familyCode) { return delegate.resolveProductsForAssetFamily(familyCode); }

    /** 回滚事务不会触发此监听，避免尚未提交的管理操作误失效缓存。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void catalogChanged(CatalogChanged ignored) {
        try { cache.invalidatePublishedProducts(); }
        catch (RuntimeException ignoredFailure) { /* the cache adapter will bypass stale data while retrying */ }
    }
}

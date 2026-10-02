package cn.edu.fudan.dayu.catalog.infrastructure;

import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.catalog.application.CatalogCachePort;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import static org.assertj.core.api.Assertions.*;

class CatalogCacheTransactionEventTest {
    @Test
    void invalidatesAfterCommitButNotAfterRollback() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            ApplicationEventPublisher events = context;
            CountingCache cache = context.getBean(CountingCache.class);

            beginTransaction();
            try {
                events.publishEvent(new CatalogChanged(new ProductCode("PRECIP")));
                complete(TransactionSynchronization.STATUS_ROLLED_BACK);
            } finally { clearTransaction(); }
            assertThat(cache.invalidations.get()).isZero();

            beginTransaction();
            try {
                events.publishEvent(new CatalogChanged(new ProductCode("PRECIP")));
                TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
                complete(TransactionSynchronization.STATUS_COMMITTED);
            } finally { clearTransaction(); }
            assertThat(cache.invalidations.get()).isEqualTo(1);
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean(name = "catalogService") CatalogQueryService delegate() { return new EmptyCatalog(); }
        @Bean CountingCache cache() { return new CountingCache(); }
        @Bean CachedCatalogQueryService cached(CatalogQueryService delegate, CountingCache cache) {
            return new CachedCatalogQueryService(delegate, cache);
        }
        @Bean PlatformTransactionManager transactionManager() { return new StubTransactionManager(); }
    }

    static final class CountingCache implements CatalogCachePort {
        final AtomicInteger invalidations = new AtomicInteger();
        @Override public Read readPublishedProducts() { return new Read(0, Optional.empty()); }
        @Override public void writePublishedProducts(long generation, List<ProductDetail> products) {}
        @Override public void invalidatePublishedProducts() { invalidations.incrementAndGet(); }
    }

    static final class StubTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    static final class EmptyCatalog implements CatalogQueryService {
        @Override public List<ProductSummary> listPublishedProducts() { return List.of(); }
        @Override public List<ProductDetail> listPublishedProductDetails() { return List.of(); }
        @Override public Optional<ProductDetail> findProduct(ProductCode code) { return Optional.empty(); }
        @Override public List<ProductSummary> listManagedProducts(ManagedProductQuery query) { return List.of(); }
        @Override public List<ProductDetail> listManagedProductDetails(ManagedProductQuery query) { return List.of(); }
        @Override public AssetFamilyProductMapping resolveProductsForAssetFamily(String familyCode) { return new AssetFamilyProductMapping(familyCode, Set.of()); }
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
    }

    private static void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}

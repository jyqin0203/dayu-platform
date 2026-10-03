package cn.edu.fudan.dayu.catalog.application;

import cn.edu.fudan.dayu.catalog.api.ProductDetail;
import java.util.List;
import java.util.Optional;

/** Catalog 应用层的可替换缓存端口；缓存不是产品事实来源。 */
public interface CatalogCachePort {
    record Read(long generation, Optional<List<ProductDetail>> value) {
        public Read {
            value = value.map(List::copyOf);
        }
    }

    Read readPublishedProducts();

    void writePublishedProducts(long generation, List<ProductDetail> products);

    void invalidatePublishedProducts();
}

package cn.edu.fudan.dayu.catalog.application;

import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Catalog 持久化端口。写方法必须参加调用方的产品事务。 */
public interface CatalogRepository {
    List<ProductDetail> findAll(ManagedProductQuery query);
    Optional<ProductDetail> findByCode(ProductCode code);
    /** 锁定产品父行后读取模式；所有模式写入使用同一锁，避免最后模式被并发关闭。 */
    Optional<ProductDetail> lock(ProductId id);
    ProductId insert(CreateProductCommand command, UserId actor, Instant now);
    void update(ProductDetail product, UserId actor);
    void saveMode(ProductId id, ProductModePolicy policy, Instant now);
    /** 与产品变更共同提交或回滚；快照包含模式配置。 */
    void audit(ProductDetail before, ProductDetail after, String action, UserId actor, Instant now);
}

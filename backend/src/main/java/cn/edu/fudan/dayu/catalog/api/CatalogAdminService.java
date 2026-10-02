package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.ProductId;

/**
 * Catalog 模块对管理员公开的产品管理接口。
 *
 * <p>这些操作会改变产品数据或生命周期状态，因此调用时必须传入
 * 由认证接入层创建的可信 {@link ActorContext}，实现类负责检查管理员权限。</p>
 */
public interface CatalogAdminService {
    /**
     * 创建一个新的草稿产品。
     *
     * @param command 创建产品所需的编码、名称、来源和展示配置
     * @param actor 当前已认证的操作人
     * @return 创建后的产品详情，初始状态为 DRAFT
     */
    ProductDetail createProduct(CreateProductCommand command, ActorContext actor);

    /**
     * 修改已有产品的可变资料，不通过该操作改变产品编码和生命周期状态。
     *
     * @param command 产品编号及需要更新的资料
     * @param actor 当前已认证的操作人
     * @return 更新后的产品详情
     */
    ProductDetail updateProduct(UpdateProductCommand command, ActorContext actor);

    /**
     * 发布草稿产品或重新发布已停用产品，使其可以出现在普通用户的产品目录中。
     * 重新发布不会覆盖产品的首次发布时间。
     *
     * @param productId 要发布的产品编号
     * @param actor 当前已认证的操作人
     * @return 发布后的产品详情
     */
    ProductDetail publishProduct(ProductId productId, ActorContext actor);

    /**
     * 停用一个产品，使其停止公开展示，同时保留历史资产和审计记录。
     *
     * @param productId 要停用的产品编号
     * @param actor 当前已认证的操作人
     * @return 停用后的产品详情
     */
    ProductDetail disableProduct(ProductId productId, ActorContext actor);
}

package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.List;
import java.util.Optional;

/**
 * Catalog 模块对其他模块公开的产品目录查询接口。
 *
 * <p>这里只查询“系统定义了哪些产品”，不负责判断硬盘上是否存在对应的
 * WebP 或 NetCDF 文件。文件是否存在由 AssetIndex 模块负责。</p>
 */
public interface CatalogQueryService {
    /**
     * 查询所有已经发布、可以向普通用户展示的产品。
     *
     * @return 按显示顺序排列的产品列表；没有结果时返回空列表
     */
    // 返回多个ProductSummary
    List<ProductSummary> listPublishedProducts();

    /**
     * 根据稳定的产品编码查找产品详情。
     * 调用方仍需根据产品状态判断它是否已经发布。
     *
     * @param code 产品编码，例如 BT855、PRECIP
     * @return 找到时返回产品详情，否则返回空 Optional
     */
    Optional<ProductDetail> findProduct(ProductCode code);

    /**
     * 查询管理员可管理的产品，包括草稿、已发布和已停用产品。
     *
     * @param query 产品族、状态和编码等可选筛选条件
     * @return 符合条件的产品列表；没有结果时返回空列表
     */
    List<ProductSummary> listManagedProducts(ManagedProductQuery query);

    /**
     * 查询一个文件族应关联哪些产品，供 AssetIndex 建立文件与产品的关系。
     * 例如 BT 文件族可以关联多个亮温产品。
     *
     * @param familyCode 文件族编码，例如 BT、PRECIP
     * @return 文件族及其关联产品编码集合
     */
    AssetFamilyProductMapping resolveProductsForAssetFamily(String familyCode);
}

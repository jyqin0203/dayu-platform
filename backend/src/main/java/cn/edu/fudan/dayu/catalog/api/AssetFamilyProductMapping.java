package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.Set;

/**
 * 表示一个文件族与其包含的产品编码之间的映射。
 * 例如 BT 文件族可以同时关联多个亮温产品。
 */
public record AssetFamilyProductMapping(String familyCode, Set<ProductCode> products) {
    public AssetFamilyProductMapping {
        products = Set.copyOf(products);
    }
}

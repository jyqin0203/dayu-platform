package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.Set;

/** 索引事务提交后发布的进程内通知，供查询缓存失效；不引入反向模块依赖。 */
public record AssetIndexChanged(Set<ProductCode> products) {
    public AssetIndexChanged { products = Set.copyOf(products); }
}

package cn.edu.fudan.dayu.operations.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.Set;

/**
 * 批量查询产品健康状态时使用的产品集合和数据模式。
 */
public record ProductHealthQuery(Set<ProductCode> productCodes, DataMode dataMode) {
    public ProductHealthQuery {
        productCodes = Set.copyOf(productCodes);
    }
}

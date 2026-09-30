package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.Set;

/**
 * 批量查询多个产品可用状态时使用的产品集合和数据模式。
 */
public record ProductAvailabilityListQuery(Set<ProductCode> productCodes, DataMode dataMode) {
    public ProductAvailabilityListQuery {
        productCodes = Set.copyOf(productCodes);
    }
}

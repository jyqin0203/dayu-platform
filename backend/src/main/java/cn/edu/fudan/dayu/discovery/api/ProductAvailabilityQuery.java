package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;

/**
 * 查询单个产品可用状态时使用的产品编码和数据模式。
 */
public record ProductAvailabilityQuery(ProductCode productCode, DataMode dataMode) {}

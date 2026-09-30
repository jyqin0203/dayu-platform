package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Duration;

/**
 * 一个产品在实况或预报模式下的启用状态与数据延迟阈值。
 */
public record ProductModePolicy(ProductCode productCode, DataMode dataMode, boolean enabled, Duration staleAfter) {}

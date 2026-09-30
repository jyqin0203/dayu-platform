package cn.edu.fudan.dayu.operations.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 管理员仪表盘使用的时间、产品和数据模式筛选条件。
 */
public record OperationsQuery(Instant from, Instant to, ProductCode productCode, DataMode dataMode) {}

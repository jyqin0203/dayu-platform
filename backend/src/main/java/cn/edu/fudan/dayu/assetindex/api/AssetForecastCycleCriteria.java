package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 查询可用预报批次时使用的产品和时间范围条件。
 */
public record AssetForecastCycleCriteria(ProductCode productCode, Instant from, Instant to) {}

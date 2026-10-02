package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import java.time.Instant;

/**
 * 查询可用预报批次时使用的产品和时间范围条件。
 */
public record AssetForecastCycleCriteria(
        ProductCode productCode, AssetType assetType, Instant from, Instant to) {}

package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import java.time.Instant;

/**
 * 查询某产品可用预报批次时使用的时间范围条件。
 */
public record ForecastCycleQuery(ProductCode productCode, AssetType assetType, Instant from, Instant to) {}

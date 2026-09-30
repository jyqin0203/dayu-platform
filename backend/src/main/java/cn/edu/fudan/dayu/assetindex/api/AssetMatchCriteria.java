package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 根据产品、数据模式和时间匹配 NetCDF 候选资产的条件。
 */
public record AssetMatchCriteria(
        ProductCode productCode, DataMode dataMode, Instant validTime,
        Instant cycleTime, Integer leadMinutes
) {}

package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 分页查询 NetCDF 科学数据索引时使用的产品、模式和时间条件。
 */
public record AssetSearchCriteria(
        ProductCode productCode, DataMode dataMode, Instant from, Instant to,
        Instant cycleTime, Integer leadMinutes, PageRequest pageRequest
) {}

package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 分页检索历史 NetCDF 科学数据时使用的查询条件。
 */
public record ScientificAssetQuery(
        ProductCode productCode, DataMode dataMode, Instant from, Instant to,
        Instant cycleTime, Integer leadMinutes, PageRequest pageRequest
) {}

package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 为当前 WebP 预览查找 NetCDF 候选时使用的产品和时间条件。
 */
public record PreviewDownloadQuery(
        ProductCode productCode, DataMode dataMode, Instant validTime,
        Instant cycleTime, Integer leadMinutes
) {}

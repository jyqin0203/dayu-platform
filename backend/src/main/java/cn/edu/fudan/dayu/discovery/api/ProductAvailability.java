package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 一个产品在指定数据模式下的预览、下载和数据健康状态。
 */
public record ProductAvailability(
        ProductCode productCode, DataMode dataMode, boolean previewAvailable,
        boolean downloadAvailable, Instant latestValidTime,
        Instant latestCycleTime, ProductHealthStatus health
) {}

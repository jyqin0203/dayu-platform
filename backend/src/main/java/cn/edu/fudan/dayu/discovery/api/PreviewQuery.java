package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 查询近期 WebP 预览帧时使用的产品、模式、时间和数量限制。
 */
public record PreviewQuery(
        ProductCode productCode, DataMode dataMode, Instant from, Instant to,
        Instant cycleTime, Integer leadMinutes, int limit
) {}

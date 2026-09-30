package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 查询近期 WebP 预览资产时使用的筛选条件和最大返回数量。
 */
public record AssetPreviewCriteria(
        ProductCode productCode, DataMode dataMode, Instant from, Instant to,
        Instant cycleTime, Integer leadMinutes, int limit
) {}

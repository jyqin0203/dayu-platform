package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 查询下载统计时使用的时间范围和可选产品条件。
 */
public record DownloadStatisticsQuery(Instant from, Instant to, ProductCode productCode) {}

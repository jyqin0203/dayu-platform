package cn.edu.fudan.dayu.operations.api;

import cn.edu.fudan.dayu.assetindex.api.ScanRunView;
import cn.edu.fudan.dayu.download.api.DownloadStatistics;

/**
 * 管理员仪表盘的产品可用性、扫描状态和下载统计汇总。
 */
public record DashboardSummary(
        long publishedProducts, long previewAvailableProducts,
        long downloadAvailableProducts, long missingProducts, long staleProducts,
        ScanRunView latestScan, DownloadStatistics downloads
) {}

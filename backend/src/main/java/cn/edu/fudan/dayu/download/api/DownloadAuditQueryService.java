package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.PageResult;

/**
 * Download 模块提供给 Operations 的下载审计和统计查询接口。
 */
public interface DownloadAuditQueryService {
    /** 分页查询符合条件的下载审计记录。 */
    PageResult<DownloadAuditSummary> searchDownloadAudits(DownloadAuditQuery query);

    /** 汇总指定范围内的下载申请、授权和拒绝数量。 */
    DownloadStatistics getDownloadStatistics(DownloadStatisticsQuery query);
}

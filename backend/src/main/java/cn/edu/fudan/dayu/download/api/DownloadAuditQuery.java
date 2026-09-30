package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.UserId;
import java.time.Instant;

/**
 * 管理员按时间、用户、单位、产品和状态筛选下载审计的分页条件。
 */
public record DownloadAuditQuery(
        Instant from, Instant to, ProductCode productCode, UserId userId,
        String organization, DownloadStatus status, PageRequest pageRequest
) {}

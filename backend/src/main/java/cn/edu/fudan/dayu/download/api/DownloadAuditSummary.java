package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.DownloadEventId;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.UserId;
import java.time.Instant;
import java.util.Set;

/**
 * 管理后台展示的一次下载申请与授权审计摘要。
 * 用户单位、产品和文件信息使用事件发生时的快照。
 */
public record DownloadAuditSummary(
        DownloadEventId eventId, UserId userId, String organizationSnapshot,
        AssetId assetId, Set<ProductCode> productSnapshot, String fileName,
        long expectedBytes, String purpose, Instant authorizedAt, DownloadStatus status
) {
    public DownloadAuditSummary {
        productSnapshot = Set.copyOf(productSnapshot);
    }
}

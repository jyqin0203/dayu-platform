package cn.edu.fudan.dayu.download.application;

import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.Optional;

/** Download 拥有的两张审计表端口；不查询 AssetIndex 或 Identity 的表。 */
public interface DownloadRepository {
    /** 插入事件和全部产品快照，必须参加调用方事务。 */
    DownloadEventId insert(DownloadableAsset asset, ActorContext actor, String purpose, ClientContext client,
                           DownloadStatus status, String denialReason, Instant time);
    Optional<StoredDownload> find(DownloadEventId id);
    PageResult<DownloadAuditSummary> search(DownloadAuditQuery query);
    DownloadStatistics statistics(DownloadStatisticsQuery query);
}

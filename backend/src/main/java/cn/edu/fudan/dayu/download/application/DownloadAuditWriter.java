package cn.edu.fudan.dayu.download.application;

import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 独立事务边界：返回前提交事件及产品快照；拒绝审计也不会被随后抛出的业务错误回滚。 */
@Service
@Profile("!skeleton")
public class DownloadAuditWriter {
    private final DownloadRepository repository;
    public DownloadAuditWriter(DownloadRepository repository) { this.repository = repository; }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public DownloadEventId record(DownloadableAsset asset, ActorContext actor, String purpose, ClientContext client,
                                  DownloadStatus status, String reason, Instant time) {
        return repository.insert(asset, actor, purpose, client, status, reason, time);
    }
}

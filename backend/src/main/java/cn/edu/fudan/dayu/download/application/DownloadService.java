package cn.edu.fudan.dayu.download.application;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 授权/持久审计/短期取件用例；身份由接入层提供，资产只通过 AssetIndex API 读取。 */
@Service
@Profile("!skeleton")
public class DownloadService implements DownloadAuthorizationService, DownloadGrantAccess,
        DownloadContentService, DownloadAuditQueryService {
    private final DownloadAssetLookup assets;
    private final DownloadRepository repository;
    private final DownloadAuditWriter writer;
    private final DownloadFiles files;
    private final DownloadPolicy policy;
    private final Clock clock;

    public DownloadService(DownloadAssetLookup assets, DownloadRepository repository, DownloadAuditWriter writer,
                           DownloadFiles files, DownloadPolicy policy, @Qualifier("downloadClock") Clock clock) {
        this.assets = assets; this.repository = repository; this.writer = writer;
        this.files = files; this.policy = policy; this.clock = clock;
    }

    @Override public DownloadGrant authorizeDownload(DownloadCommand command, ActorContext actor, ClientContext client) {
        requireActor(actor);
        if (command == null || command.assetId() == null || command.purpose() == null)
            throw invalid("资产和用途不能为空");
        String purpose = command.purpose().trim();
        int characters = purpose.codePointCount(0, purpose.length());
        if (characters < 10 || characters > 2000) throw invalid("用途必须为10到2000字符");
        DownloadableAsset asset = asset(command.assetId());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            requireAvailable(asset);
            files.verify(asset, asset.fileSize(), null);
        } catch (BusinessException e) {
            // 只有已认证、格式正确、能关联真实资产的请求进入业务拒绝审计。
            writer.record(asset, actor, purpose, client, DownloadStatus.DENIED, e.errorCode().name(), now);
            throw e;
        }
        DownloadEventId id = writer.record(asset, actor, purpose, client, DownloadStatus.AUTHORIZED, null, now);
        return grant(id, asset, asset.fileSize(), now);
    }

    @Override public DownloadGrant getAuthorizedGrant(DownloadEventId id, ActorContext actor) {
        Access access = access(id, actor);
        return access.grant();
    }
    @Override public DownloadContent prepareContent(DownloadEventId id, ActorContext actor) {
        Access access = access(id, actor);
        return new DownloadContent(access.grant(), policy.nginxTransfer() ? null
                : files.open(access.asset(), access.grant().expectedBytes(), access.grant().authorizedAt()));
    }
    private Access access(DownloadEventId id, ActorContext actor) {
        requireActor(actor);
        if (id == null) throw invalid("下载事件编号不能为空");
        DownloadAuditSummary event = repository.find(id).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "下载授权不存在")).summary();
        if (!event.userId().equals(actor.userId())) throw new BusinessException(ErrorCode.FORBIDDEN, "不能使用其他用户的授权");
        if (event.status() != DownloadStatus.AUTHORIZED || event.authorizedAt() == null)
            throw new BusinessException(ErrorCode.FORBIDDEN, "该事件不是有效下载授权");
        if (!clock.instant().isBefore(event.authorizedAt().plus(policy.grantTtl())))
            throw gone("下载授权已过期");
        DownloadableAsset asset = assets.findDownloadableAsset(event.assetId())
                .orElseThrow(() -> gone("科学数据资产已不可用"));
        requireAvailable(asset);
        if (asset.fileSize() != event.expectedBytes() || !asset.fileName().equals(event.fileName()))
            throw gone("文件已变化，请重新申请下载");
        files.verify(asset, event.expectedBytes(), event.authorizedAt());
        return new Access(asset, grant(id, asset, event.expectedBytes(), event.authorizedAt()));
    }
    private DownloadGrant grant(DownloadEventId id, DownloadableAsset asset, long bytes, Instant time) {
        return new DownloadGrant(id, asset.fileName(), "application/x-netcdf", bytes,
                files.internalLocation(asset), time, time.plus(policy.grantTtl()));
    }
    private DownloadableAsset asset(AssetId id) {
        return assets.findDownloadableAsset(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "科学数据资产不存在"));
    }
    private static void requireAvailable(DownloadableAsset a) {
        if (a.assetType() != AssetType.NETCDF || a.status() != AssetStatus.AVAILABLE) throw gone("该科学数据文件当前不可用");
    }
    private static void requireActor(ActorContext actor) {
        if (actor == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED, "请先登录");
    }
    @Override @Transactional(readOnly = true)
    public PageResult<DownloadAuditSummary> searchDownloadAudits(DownloadAuditQuery q) {
        if (q == null || q.pageRequest() == null) throw invalid("查询及分页不能为空");
        validateRange(q.from(), q.to());
        return repository.search(q);
    }
    @Override @Transactional(readOnly = true)
    public DownloadStatistics getDownloadStatistics(DownloadStatisticsQuery q) {
        if (q == null) throw invalid("查询不能为空");
        validateRange(q.from(), q.to());
        return repository.statistics(q);
    }
    private static void validateRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) throw invalid("起始时间不能晚于结束时间");
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
    private static BusinessException gone(String message) { return new BusinessException(ErrorCode.ASSET_GONE, message); }
    private record Access(DownloadableAsset asset, DownloadGrant grant) {}
}

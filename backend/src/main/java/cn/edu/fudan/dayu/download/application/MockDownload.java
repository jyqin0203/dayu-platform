package cn.edu.fudan.dayu.download.application;

import cn.edu.fudan.dayu.assetindex.api.DownloadAssetLookup;
import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.download.api.ClientContext;
import cn.edu.fudan.dayu.download.api.DeliveryResultCommand;
import cn.edu.fudan.dayu.download.api.DeliveryResultRecorder;
import cn.edu.fudan.dayu.download.api.DownloadAuditQuery;
import cn.edu.fudan.dayu.download.api.DownloadAuditQueryService;
import cn.edu.fudan.dayu.download.api.DownloadAuditSummary;
import cn.edu.fudan.dayu.download.api.DownloadAuthorizationService;
import cn.edu.fudan.dayu.download.api.DownloadCommand;
import cn.edu.fudan.dayu.download.api.DownloadGrant;
import cn.edu.fudan.dayu.download.api.DownloadGrantAccess;
import cn.edu.fudan.dayu.download.api.DownloadStatistics;
import cn.edu.fudan.dayu.download.api.DownloadStatisticsQuery;
import cn.edu.fudan.dayu.download.api.DownloadStatus;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DownloadEventId;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import cn.edu.fudan.dayu.shared.kernel.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Download 在 skeleton Profile 下使用的内存模拟实现。
 *
 * <p>它校验固定资产和下载用途并在内存记录审计，只生成内部授权结果，
 * 不校验真实文件路径，也不传输 NetCDF 文件。</p>
 */
@Service
@Profile("skeleton")
class MockDownload implements DownloadAuthorizationService, DownloadGrantAccess,
        DownloadAuditQueryService, DeliveryResultRecorder {
    private static final Instant AUTHORIZED_AT = Instant.parse("2026-09-29T02:20:00Z");
    private final DownloadAssetLookup assets;
    private final AtomicLong eventSequence = new AtomicLong(50_000);
    private final List<DownloadAuditSummary> audits = new ArrayList<>();
    private final Map<DownloadEventId, DownloadGrant> grants = new HashMap<>();
    private final Map<DownloadEventId, UserId> grantOwners = new HashMap<>();

    MockDownload(DownloadAssetLookup assets) {
        this.assets = assets;
        assets.findDownloadableAsset(new cn.edu.fudan.dayu.shared.kernel.AssetId(92002)).ifPresent(asset ->
                audits.add(new DownloadAuditSummary(new DownloadEventId(50_000), new UserId(1), "复旦大学",
                        asset.assetId(), asset.products(), asset.fileName(), asset.fileSize(),
                        "用于空骨架流程验证", AUTHORIZED_AT, DownloadStatus.AUTHORIZED)));
    }

    @Override
    public DownloadGrant authorizeDownload(DownloadCommand command, ActorContext actor, ClientContext client) {
        if (command.purpose() == null || command.purpose().length() < 10 || command.purpose().length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "下载用途长度必须为10到2000字符");
        }
        DownloadableAsset asset = assets.findDownloadableAsset(command.assetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "科学数据资产不存在"));
        if (asset.assetType() != AssetType.NETCDF || asset.status() != AssetStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.ASSET_GONE, "该科学数据文件当前不可用");
        }
        DownloadEventId eventId = new DownloadEventId(eventSequence.incrementAndGet());
        audits.add(new DownloadAuditSummary(eventId, actor.userId(), actor.organization(), asset.assetId(),
                asset.products(), asset.fileName(), asset.fileSize(), command.purpose(), AUTHORIZED_AT,
                DownloadStatus.AUTHORIZED));
        DownloadGrant grant = new DownloadGrant(eventId, asset.fileName(), "application/x-netcdf", asset.fileSize(),
                "/internal-netcdf/" + asset.relativePath().replace('\\', '/'), Instant.now());
        grants.put(eventId, grant);
        grantOwners.put(eventId, actor.userId());
        return grant;
    }

    @Override
    public DownloadGrant getAuthorizedGrant(DownloadEventId eventId, ActorContext actor) {
        DownloadGrant grant = grants.get(eventId);
        if (grant == null) throw new BusinessException(ErrorCode.NOT_FOUND, "下载授权不存在");
        if (!actor.userId().equals(grantOwners.get(eventId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能使用其他用户的下载授权");
        }
        return grant;
    }

    @Override
    public PageResult<DownloadAuditSummary> searchDownloadAudits(DownloadAuditQuery query) {
        List<DownloadAuditSummary> result = audits.stream()
                .filter(a -> query.status() == null || query.status() == a.status())
                .filter(a -> query.userId() == null || query.userId().equals(a.userId()))
                .filter(a -> query.organization() == null || query.organization().equals(a.organizationSnapshot()))
                .filter(a -> query.productCode() == null || a.productSnapshot().contains(query.productCode()))
                .toList();
        return new PageResult<>(result, query.pageRequest().page(), query.pageRequest().size(), result.size());
    }

    @Override
    public DownloadStatistics getDownloadStatistics(DownloadStatisticsQuery query) {
        long authorized = audits.stream().filter(a -> a.status() == DownloadStatus.AUTHORIZED).count();
        long denied = audits.stream().filter(a -> a.status() == DownloadStatus.DENIED).count();
        long uniqueAssets = audits.stream().map(DownloadAuditSummary::assetId).distinct().count();
        return new DownloadStatistics(audits.size(), authorized, denied, uniqueAssets,
                Map.of("PRECIP", authorized), Map.of("复旦大学", authorized), Map.of("1", authorized));
    }

    @Override
    public void recordDeliveryResult(DeliveryResultCommand command) {
        // Extension seam only. Production delivery reconciliation is intentionally not implemented.
    }
}

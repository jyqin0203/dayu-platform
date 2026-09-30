package cn.edu.fudan.dayu.operations.application;

import cn.edu.fudan.dayu.assetindex.api.AssetAdminQueryService;
import cn.edu.fudan.dayu.assetindex.api.AssetIndexCommandService;
import cn.edu.fudan.dayu.assetindex.api.AssetScanResult;
import cn.edu.fudan.dayu.assetindex.api.ScanTrigger;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.discovery.api.ProductAvailability;
import cn.edu.fudan.dayu.discovery.api.ProductAvailabilityListQuery;
import cn.edu.fudan.dayu.discovery.api.ProductHealthStatus;
import cn.edu.fudan.dayu.download.api.DownloadAuditQuery;
import cn.edu.fudan.dayu.download.api.DownloadAuditQueryService;
import cn.edu.fudan.dayu.download.api.DownloadAuditSummary;
import cn.edu.fudan.dayu.download.api.DownloadStatistics;
import cn.edu.fudan.dayu.download.api.DownloadStatisticsQuery;
import cn.edu.fudan.dayu.identity.api.ChangeUserRoleCommand;
import cn.edu.fudan.dayu.identity.api.ChangeUserStatusCommand;
import cn.edu.fudan.dayu.identity.api.UserAdminService;
import cn.edu.fudan.dayu.identity.api.UserSummary;
import cn.edu.fudan.dayu.operations.api.DashboardSummary;
import cn.edu.fudan.dayu.operations.api.OperationsQuery;
import cn.edu.fudan.dayu.operations.api.OperationsService;
import cn.edu.fudan.dayu.operations.api.ProductHealth;
import cn.edu.fudan.dayu.operations.api.ProductHealthQuery;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import cn.edu.fudan.dayu.shared.kernel.UserRole;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Operations 在 skeleton Profile 下使用的管理员编排实现。
 *
 * <p>它检查管理员角色并调用 Catalog、AssetIndex、Discovery、Identity 和
 * Download 的公开 API，不直接访问任何模块的内部存储。</p>
 */
@Service
@Profile("skeleton")
class MockOperations implements OperationsService {
    private final CatalogQueryService catalog;
    private final AssetIndexCommandService assetCommands;
    private final AssetAdminQueryService assetAdmin;
    private final DiscoveryQueryService discovery;
    private final DownloadAuditQueryService downloads;
    private final UserAdminService users;

    MockOperations(CatalogQueryService catalog, AssetIndexCommandService assetCommands,
                   AssetAdminQueryService assetAdmin, DiscoveryQueryService discovery,
                   DownloadAuditQueryService downloads, UserAdminService users) {
        this.catalog = catalog;
        this.assetCommands = assetCommands;
        this.assetAdmin = assetAdmin;
        this.discovery = discovery;
        this.downloads = downloads;
        this.users = users;
    }

    @Override
    public DashboardSummary getDashboard(OperationsQuery query, ActorContext actor) {
        requireAdmin(actor);
        var products = catalog.listPublishedProducts();
        Set<cn.edu.fudan.dayu.shared.kernel.ProductCode> codes = products.stream()
                .map(p -> p.code()).collect(Collectors.toUnmodifiableSet());
        DataMode mode = query.dataMode() == null ? DataMode.REALTIME : query.dataMode();
        List<ProductAvailability> availability = discovery.listProductAvailability(
                new ProductAvailabilityListQuery(codes, mode));
        DownloadStatistics statistics = downloads.getDownloadStatistics(
                new DownloadStatisticsQuery(query.from(), query.to(), query.productCode()));
        return new DashboardSummary(products.size(),
                availability.stream().filter(ProductAvailability::previewAvailable).count(),
                availability.stream().filter(ProductAvailability::downloadAvailable).count(),
                availability.stream().filter(a -> a.health() == ProductHealthStatus.MISSING).count(),
                availability.stream().filter(a -> a.health() == ProductHealthStatus.STALE).count(),
                assetAdmin.getLatestScanResult().orElse(null), statistics);
    }

    @Override
    public List<ProductHealth> getProductHealth(ProductHealthQuery query, ActorContext actor) {
        requireAdmin(actor);
        return discovery.listProductAvailability(new ProductAvailabilityListQuery(query.productCodes(), query.dataMode()))
                .stream().map(a -> new ProductHealth(a.productCode(), a.dataMode(),
                        a.previewAvailable() ? 1 : 0, a.downloadAvailable() ? 1 : 0,
                        a.latestValidTime(), a.health())).toList();
    }

    @Override
    public AssetScanResult triggerIncrementalScan(ActorContext actor) {
        requireAdmin(actor);
        return assetCommands.runIncrementalScan(ScanTrigger.MANUAL);
    }

    @Override
    public PageResult<DownloadAuditSummary> searchDownloadAudits(DownloadAuditQuery query, ActorContext actor) {
        requireAdmin(actor);
        return downloads.searchDownloadAudits(query);
    }

    @Override
    public DownloadStatistics getDownloadStatistics(DownloadStatisticsQuery query, ActorContext actor) {
        requireAdmin(actor);
        return downloads.getDownloadStatistics(query);
    }

    @Override
    public UserSummary changeUserStatus(ChangeUserStatusCommand command, ActorContext actor) {
        requireAdmin(actor);
        return users.changeUserStatus(command, actor);
    }

    @Override
    public UserSummary changeUserRole(ChangeUserRoleCommand command, ActorContext actor) {
        requireAdmin(actor);
        return users.changeUserRole(command, actor);
    }

    private static void requireAdmin(ActorContext actor) {
        if (actor.role() != UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
    }
}

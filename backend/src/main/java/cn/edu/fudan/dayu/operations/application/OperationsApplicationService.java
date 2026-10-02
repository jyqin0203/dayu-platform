package cn.edu.fudan.dayu.operations.application;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.operations.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 管理员用例编排。所有事实来自模块公开 API，不访问数据库或复制产品、用户状态机。 */
@Service
@Profile("!skeleton")
public class OperationsApplicationService implements OperationsService {
    private final CatalogQueryService catalog;
    private final AssetIndexCommandService commands;
    private final AssetScanTaskService scans;
    private final DiscoveryQueryService discovery;
    private final DownloadAuditQueryService downloads;
    private final UserAdminService users;
    private final Clock clock;

    @Autowired
    public OperationsApplicationService(CatalogQueryService catalog, AssetIndexCommandService commands,
            AssetScanTaskService scans, DiscoveryQueryService discovery, DownloadAuditQueryService downloads,
            UserAdminService users) {
        this(catalog, commands, scans, discovery, downloads, users, Clock.systemUTC());
    }

    OperationsApplicationService(CatalogQueryService catalog, AssetIndexCommandService commands,
            AssetScanTaskService scans, DiscoveryQueryService discovery, DownloadAuditQueryService downloads,
            UserAdminService users, Clock clock) {
        this.catalog=catalog; this.commands=commands; this.scans=scans;
        this.discovery=discovery; this.downloads=downloads; this.users=users;
        this.clock=clock;
    }

    @Override
    public DashboardSummary getDashboard(OperationsQuery query, ActorContext actor) {
        requireAdmin(actor);
        if (query==null) throw invalid("Dashboard query required");
        Instant to=query.to()==null ? clock.instant() : query.to();
        Instant from=query.from()==null ? to.minus(Duration.ofDays(30)) : query.from();
        validateRange(from,to);
        if (Duration.between(from,to).compareTo(Duration.ofDays(365))>0) throw invalid("Dashboard range exceeds 365 days");
        var products=catalog.listPublishedProductDetails().stream()
                .filter(p -> query.productCode()==null || p.summary().code().equals(query.productCode()))
                .toList();
        List<ProductAvailability> availability=new ArrayList<>();
        for (DataMode mode : DataMode.values()) {
            if (query.dataMode()!=null && mode!=query.dataMode()) continue;
            Set<ProductCode> codes=products.stream().filter(p -> p.modePolicies().stream().anyMatch(m -> m.enabled() && m.dataMode()==mode))
                    .map(p -> p.summary().code()).collect(Collectors.toSet());
            if (!codes.isEmpty()) availability.addAll(discovery.listProductAvailability(new ProductAvailabilityListQuery(codes,mode)));
        }
        List<ProductHealth> health=availability.stream().map(a -> toHealth(a, policy(products,a.productCode(),a.dataMode()))).toList();
        // dataMode 只收窄当前可用性与健康；产品总数仍表示产品筛选下的全部已发布产品。
        var latest=scans.searchScanRuns(new ScanRunQuery(null,null,null,null,new PageRequest(1,1))).items().stream().findFirst().orElse(null);
        var stats=downloads.getDownloadStatistics(new DownloadStatisticsQuery(from,to,query.productCode()));
        return new DashboardSummary(products.size(),count(availability,ProductAvailability::previewAvailable),
                count(availability,ProductAvailability::downloadAvailable),countHealth(health,ProductHealthStatus.MISSING),
                countHealth(health,ProductHealthStatus.STALE),latest,stats);
    }

    @Override
    public List<ProductHealth> getProductHealth(ProductHealthQuery query, ActorContext actor) {
        requireAdmin(actor);
        if (query==null) throw invalid("Product health query required");
        List<ProductDetail> products=catalog.listManagedProductDetails(new ManagedProductQuery(null,null,null)).stream()
                .filter(p -> query.productCodes().isEmpty() || query.productCodes().contains(p.summary().code()))
                .toList();
        List<ProductHealth> result=new ArrayList<>();
        for (DataMode mode : DataMode.values()) {
            if (query.dataMode()!=null && query.dataMode()!=mode) continue;
            Set<ProductCode> enabled=products.stream().filter(p -> p.summary().status()==ProductStatus.PUBLISHED)
                    .filter(p -> p.modePolicies().stream().anyMatch(m -> m.dataMode()==mode && m.enabled()))
                    .map(p -> p.summary().code()).collect(Collectors.toSet());
            Map<ProductCode,ProductAvailability> availability=enabled.isEmpty() ? Map.of()
                    : discovery.listProductAvailability(new ProductAvailabilityListQuery(enabled,mode)).stream()
                    .collect(Collectors.toMap(ProductAvailability::productCode,a -> a));
            for (var product : products) for (var policy : product.modePolicies()) {
                if (policy.dataMode()!=mode) continue;
                boolean active=enabled.contains(product.summary().code());
                var data=availability.get(product.summary().code());
                if (active && data==null) throw new BusinessException(ErrorCode.INTERNAL_ERROR,"Product availability response is incomplete");
                result.add(active ? toHealth(data,policy)
                        : new ProductHealth(product.summary().code(),mode,null,null,null,
                                ProductHealthStatus.DISABLED,policy.staleAfter().toMinutes()));
            }
        }
        result.sort(Comparator.comparing((ProductHealth h) -> h.productCode().value()).thenComparing(ProductHealth::dataMode));
        return List.copyOf(result);
    }

    /** 旧内部同步契约保留；新的管理员 HTTP 仅调用 AssetScanTaskService。 */
    @Override public AssetScanResult triggerIncrementalScan(ActorContext actor) {
        requireAdmin(actor); return commands.runIncrementalScan(ScanTrigger.MANUAL);
    }
    @Override public PageResult<DownloadAuditSummary> searchDownloadAudits(DownloadAuditQuery query,ActorContext actor) {
        requireAdmin(actor);
        if (query==null || query.pageRequest()==null) throw invalid("Audit query and page required");
        validateRange(query.from(),query.to());
        return downloads.searchDownloadAudits(query);
    }
    @Override public DownloadStatistics getDownloadStatistics(DownloadStatisticsQuery query,ActorContext actor) {
        requireAdmin(actor);
        if (query==null) throw invalid("Statistics query required");
        validateRange(query.from(),query.to());
        return downloads.getDownloadStatistics(query);
    }
    @Override public UserSummary changeUserStatus(ChangeUserStatusCommand command,ActorContext actor) {
        requireAdmin(actor);
        if (command==null || command.userId()==null || command.status()==null) throw invalid("User and status required");
        return users.changeUserStatus(command,actor);
    }
    @Override public UserSummary changeUserRole(ChangeUserRoleCommand command,ActorContext actor) {
        requireAdmin(actor);
        if (command==null || command.userId()==null || command.role()==null) throw invalid("User and role required");
        return users.changeUserRole(command,actor);
    }
    private long count(List<ProductAvailability> all,Predicate<ProductAvailability> predicate) {
        return all.stream().filter(predicate).map(ProductAvailability::productCode).distinct().count();
    }
    private long countHealth(List<ProductHealth> all,ProductHealthStatus status) {
        return all.stream().filter(health -> health.status()==status).map(ProductHealth::productCode).distinct().count();
    }
    private ProductModePolicy policy(List<ProductDetail> products,ProductCode code,DataMode mode) {
        return products.stream().filter(product -> product.summary().code().equals(code))
                .flatMap(product -> product.modePolicies().stream())
                .filter(candidate -> candidate.dataMode()==mode && candidate.enabled()).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,"Product mode policy is missing"));
    }
    private ProductHealth toHealth(ProductAvailability availability,ProductModePolicy policy) {
        return new ProductHealth(availability.productCode(),availability.dataMode(),null,null,
                availability.latestValidTime(),availability.health(),
                policy.staleAfter().toMinutes());
    }
    private void requireAdmin(ActorContext actor) {
        if (actor==null) throw new BusinessException(ErrorCode.UNAUTHENTICATED,"请先登录");
        if (actor.role()!=UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN,"需要管理员权限");
    }
    private void validateRange(Instant from,Instant to) {
        if (from!=null && to!=null && from.isAfter(to)) throw invalid("Time range is reversed");
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED,message); }
}

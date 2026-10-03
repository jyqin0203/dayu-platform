package cn.edu.fudan.dayu.interfaces.rest.v1.operations;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.interfaces.rest.v1.PageParameters;
import cn.edu.fudan.dayu.operations.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理协议适配器；安全链与可信 actor 双重校验，只调用模块公开 API。 */
@RestController
public class OperationsController {
    private static final String CODE="^[A-Z][A-Z0-9_]{1,63}$";
    private final OperationsService operations;
    private final AssetScanTaskService scans;
    private final UserAdminService users;
    private final CurrentActorProvider actors;

    public OperationsController(OperationsService operations,AssetScanTaskService scans,UserAdminService users,CurrentActorProvider actors) {
        this.operations=operations; this.scans=scans; this.users=users; this.actors=actors;
    }

    @GetMapping("/api/v1/admin/dashboard")
    public OperationsResponses.Dashboard dashboard(@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,@RequestParam(required=false) @Pattern(regexp=CODE) String productCode,
            @RequestParam(required=false) DataMode dataMode) {
        var r=operations.getDashboard(new OperationsQuery(from,to,code(productCode),dataMode),admin());
        return new OperationsResponses.Dashboard(r.publishedProducts(),r.previewAvailableProducts(),r.downloadAvailableProducts(),
                r.missingProducts(),r.staleProducts(),r.latestScan()==null ? null : OperationsResponses.scan(r.latestScan(),false),
                OperationsResponses.statistics(r.downloads()));
    }

    @GetMapping("/api/v1/admin/product-health")
    public Map<String,Object> health(@RequestParam(required=false) @Pattern(regexp=CODE) String productCode,
            @RequestParam(required=false) DataMode dataMode) {
        var query=new ProductHealthQuery(productCode==null ? Set.of() : Set.of(code(productCode)),dataMode);
        return Map.of("items",operations.getProductHealth(query,admin()).stream().map(OperationsResponses::health).toList());
    }

    @PostMapping("/api/v1/admin/index-scans")
    public ResponseEntity<OperationsResponses.Scan> scan() {
        var accepted=scans.submitScan(ScanTrigger.MANUAL,admin());
        return ResponseEntity.accepted().body(OperationsResponses.scan(accepted,false));
    }

    @GetMapping("/api/v1/admin/index-scans")
    public OperationsResponses.Page<OperationsResponses.Scan> scanHistory(@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,@RequestParam(required=false) ScanTrigger trigger,
            @RequestParam(required=false) ScanStatus status,@Valid PageParameters page) {
        admin();
        var r=scans.searchScanRuns(new ScanRunQuery(from,to,trigger,status,page.toPageRequest()));
        return new OperationsResponses.Page<>(r.items().stream().map(s -> OperationsResponses.scan(s,false)).toList(),r.page(),r.size(),r.total());
    }

    @GetMapping("/api/v1/admin/index-scans/{scanRunId}")
    public OperationsResponses.Scan scanDetail(@PathVariable @Positive long scanRunId) {
        admin();
        return OperationsResponses.scan(scans.findScanRun(scanRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,"扫描任务不存在")),true);
    }

    @GetMapping("/api/v1/admin/download-audits")
    public OperationsResponses.Page<OperationsResponses.Audit> audits(@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,@RequestParam(required=false) @Pattern(regexp=CODE) String productCode,
            @RequestParam(required=false) @Positive Long userId,@RequestParam(required=false) @Size(max=255) String organization,
            @RequestParam(required=false) DownloadStatus status,@Valid PageParameters page) {
        var r=operations.searchDownloadAudits(new DownloadAuditQuery(from,to,code(productCode),userId==null ? null : new UserId(userId),
                organization,status,page.toPageRequest()),admin());
        return new OperationsResponses.Page<>(r.items().stream().map(OperationsResponses::audit).toList(),r.page(),r.size(),r.total());
    }

    @GetMapping("/api/v1/admin/download-statistics")
    public OperationsResponses.Statistics statistics(@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,@RequestParam(required=false) @Pattern(regexp=CODE) String productCode) {
        return OperationsResponses.statistics(operations.getDownloadStatistics(new DownloadStatisticsQuery(from,to,code(productCode)),admin()));
    }

    @GetMapping("/api/v1/admin/users")
    public OperationsResponses.Page<OperationsResponses.User> users(@RequestParam(required=false) @Size(max=254) String email,
            @RequestParam(required=false) @Size(max=255) String organization,@RequestParam(required=false) UserRole role,
            @RequestParam(required=false) UserStatus status,@Valid PageParameters page) {
        var r=users.searchUsers(new UserQuery(email,organization,role,status,page.toPageRequest()),admin());
        return new OperationsResponses.Page<>(r.items().stream().map(OperationsResponses::user).toList(),r.page(),r.size(),r.total());
    }

    public record StatusRequest(@NotNull UserStatus status) {}
    public record RoleRequest(@NotNull UserRole role) {}

    @PutMapping("/api/v1/admin/users/{userId}/status")
    public OperationsResponses.User status(@PathVariable @Positive long userId,@Valid @RequestBody StatusRequest body) {
        return OperationsResponses.user(operations.changeUserStatus(new ChangeUserStatusCommand(new UserId(userId),body.status()),admin()));
    }
    @PutMapping("/api/v1/admin/users/{userId}/role")
    public OperationsResponses.User role(@PathVariable @Positive long userId,@Valid @RequestBody RoleRequest body) {
        return OperationsResponses.user(operations.changeUserRole(new ChangeUserRoleCommand(new UserId(userId),body.role()),admin()));
    }
    private ProductCode code(String value) { return value==null ? null : new ProductCode(value); }
    private ActorContext admin() {
        var actor=actors.required();
        if (actor.role()!=UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN,"需要管理员权限");
        return actor;
    }
}

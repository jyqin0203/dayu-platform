package cn.edu.fudan.dayu.interfaces.rest.v1.operations;

import cn.edu.fudan.dayu.assetindex.api.AssetAdminQueryService;
import cn.edu.fudan.dayu.assetindex.api.ScanHistoryQuery;
import cn.edu.fudan.dayu.assetindex.api.ScanTrigger;
import cn.edu.fudan.dayu.download.api.DownloadAuditQuery;
import cn.edu.fudan.dayu.download.api.DownloadStatisticsQuery;
import cn.edu.fudan.dayu.download.api.DownloadStatus;
import cn.edu.fudan.dayu.identity.api.ChangeUserRoleCommand;
import cn.edu.fudan.dayu.identity.api.ChangeUserStatusCommand;
import cn.edu.fudan.dayu.identity.api.UserAdminService;
import cn.edu.fudan.dayu.identity.api.UserQuery;
import cn.edu.fudan.dayu.identity.api.UserStatus;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.interfaces.rest.v1.PageParameters;
import cn.edu.fudan.dayu.operations.api.OperationsQuery;
import cn.edu.fudan.dayu.operations.api.OperationsService;
import cn.edu.fudan.dayu.operations.api.ProductHealthQuery;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.UserId;
import cn.edu.fudan.dayu.shared.kernel.UserRole;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理仪表盘、扫描、审计、统计和用户管理 HTTP 适配器。 */
@RestController
public class OperationsController {
    private final OperationsService operations;
    private final AssetAdminQueryService scans;
    private final UserAdminService users;
    private final CurrentActorProvider actors;

    public OperationsController(OperationsService operations, AssetAdminQueryService scans,
                                UserAdminService users, CurrentActorProvider actors) {
        this.operations = operations;
        this.scans = scans;
        this.users = users;
        this.actors = actors;
    }

    @GetMapping("/api/v1/admin/dashboard")
    public Map<String, Object> dashboard(@RequestParam(required = false) Instant from,
                            @RequestParam(required = false) Instant to,
                            @RequestParam(required = false) String productCode,
                            @RequestParam(required = false) DataMode dataMode) {
        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(30, ChronoUnit.DAYS) : from;
        var dashboard = operations.getDashboard(new OperationsQuery(effectiveFrom, effectiveTo,
                productCode == null ? null : new ProductCode(productCode), dataMode), actors.required());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publishedProducts", dashboard.publishedProducts());
        result.put("previewAvailableProducts", dashboard.previewAvailableProducts());
        result.put("downloadAvailableProducts", dashboard.downloadAvailableProducts());
        result.put("missingProducts", dashboard.missingProducts());
        result.put("staleProducts", dashboard.staleProducts());
        result.put("latestScan", dashboard.latestScan() == null ? null : scan(dashboard.latestScan()));
        result.put("downloads", statistics(dashboard.downloads()));
        return result;
    }

    @GetMapping("/api/v1/admin/product-health")
    public Map<String, Object> health(@RequestParam(required = false) String productCode,
                                      @RequestParam(defaultValue = "REALTIME") DataMode dataMode) {
        Set<ProductCode> codes = productCode == null ? Set.of() : Set.of(new ProductCode(productCode));
        return Map.of("items", operations.getProductHealth(
                new ProductHealthQuery(codes, dataMode), actors.required()).stream().map(item -> Map.of(
                        "productCode", item.productCode().value(), "dataMode", item.dataMode(),
                        "status", item.status(), "latestValidTime", item.latestValidTime() == null
                                ? "" : item.latestValidTime().toString(),
                        "staleAfterMinutes", item.staleAfterMinutes())).toList());
    }

    @PostMapping("/api/v1/admin/index-scans")
    public ResponseEntity<Map<String, Object>> scan() {
        var result = operations.triggerIncrementalScan(actors.required());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "scanRunId", result.startedAt().toEpochMilli(), "trigger", result.trigger(),
                "status", "SUCCEEDED", "startedAt", result.startedAt(),
                "finishedAt", result.finishedAt()));
    }

    @GetMapping("/api/v1/admin/index-scans")
    public Map<String, Object> scanHistory(@RequestParam(required = false) Instant from,
                              @RequestParam(required = false) Instant to,
                              @RequestParam(required = false) ScanTrigger trigger,
                              @Valid PageParameters page) {
        var result = scans.searchScanHistory(new ScanHistoryQuery(from, to, trigger, page.toPageRequest()));
        return Map.of("items", result.items(), "page", result.page(),
                "pageSize", result.size(), "total", result.total());
    }

    @GetMapping("/api/v1/admin/index-scans/{scanRunId}")
    public Map<String, Object> scanDetail(@PathVariable long scanRunId) {
        return scan(scans.getLatestScanResult().orElseThrow());
    }

    @GetMapping("/api/v1/admin/download-audits")
    public Map<String, Object> audits(@RequestParam(required = false) Instant from,
                         @RequestParam(required = false) Instant to,
                         @RequestParam(required = false) String productCode,
                         @RequestParam(required = false) Long userId,
                         @RequestParam(required = false) String organization,
                         @RequestParam(required = false) DownloadStatus status,
                         @Valid PageParameters page) {
        var result = operations.searchDownloadAudits(new DownloadAuditQuery(from, to,
                productCode == null ? null : new ProductCode(productCode),
                userId == null ? null : new UserId(userId), organization, status,
                page.toPageRequest()), actors.required());
        var items = result.items().stream().map(item -> Map.of(
                "downloadEventId", item.eventId().value(), "userId", item.userId().value(),
                "organizationSnapshot", item.organizationSnapshot(), "assetId", item.assetId().value(),
                "productSnapshot", item.productSnapshot().stream().map(ProductCode::value).toList(),
                "fileName", item.fileName(), "expectedBytes", item.expectedBytes(),
                "purpose", item.purpose(), "authorizedAt", item.authorizedAt(), "status", item.status())).toList();
        return Map.of("items", items, "page", result.page(),
                "pageSize", result.size(), "total", result.total());
    }

    @GetMapping("/api/v1/admin/download-statistics")
    public Map<String, Object> statistics(@RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to,
                             @RequestParam(required = false) String productCode) {
        return statistics(operations.getDownloadStatistics(new DownloadStatisticsQuery(
                from, to, productCode == null ? null : new ProductCode(productCode)), actors.required()));
    }

    @GetMapping("/api/v1/admin/users")
    public Map<String, Object> users(@RequestParam(required = false) String email,
                        @RequestParam(required = false) String organization,
                        @RequestParam(required = false) UserRole role,
                        @RequestParam(required = false) UserStatus status,
                        @Valid PageParameters page) {
        var result = users.searchUsers(new UserQuery(
                email, organization, role, status, page.toPageRequest()), actors.required());
        var items = result.items().stream().map(user -> Map.of(
                "userId", user.id().value(), "email", user.email(),
                "organization", user.organization(), "role", user.role(), "status", user.status())).toList();
        return Map.of("items", items, "page", result.page(),
                "pageSize", result.size(), "total", result.total());
    }

    @PutMapping("/api/v1/admin/users/{userId}/status")
    public Map<String, Object> status(@PathVariable long userId, @RequestBody Map<String, UserStatus> body) {
        return user(operations.changeUserStatus(
                new ChangeUserStatusCommand(new UserId(userId), body.get("status")), actors.required()));
    }

    @PutMapping("/api/v1/admin/users/{userId}/role")
    public Map<String, Object> role(@PathVariable long userId, @RequestBody Map<String, UserRole> body) {
        return user(operations.changeUserRole(
                new ChangeUserRoleCommand(new UserId(userId), body.get("role")), actors.required()));
    }

    private static Map<String, Object> statistics(cn.edu.fudan.dayu.download.api.DownloadStatistics value) {
        return Map.of("authorizedRequests", value.authorized(),
                "uniqueUsers", value.byUser().size(),
                "uniqueOrganizations", value.byOrganization().size(),
                "uniqueAssets", value.uniqueAssets());
    }

    private static Map<String, Object> user(cn.edu.fudan.dayu.identity.api.UserSummary value) {
        return Map.of("userId", value.id().value(), "email", value.email(),
                "organization", value.organization(), "role", value.role(), "status", value.status());
    }

    private static Map<String, Object> scan(cn.edu.fudan.dayu.assetindex.api.AssetScanResult value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanRunId", value.startedAt().toEpochMilli()); result.put("trigger", value.trigger());
        result.put("status", "SUCCEEDED"); result.put("startedAt", value.startedAt());
        result.put("finishedAt", value.finishedAt()); result.put("scannedFiles", value.scannedFiles());
        result.put("createdAssets", value.createdAssets()); result.put("updatedAssets", value.updatedAssets());
        result.put("removedWebpAssets", value.removedWebpAssets());
        result.put("missingNetcdfAssets", value.missingNetcdfAssets());
        result.put("errorCount", value.errors().size()); result.put("errors", value.errors());
        return result;
    }
}

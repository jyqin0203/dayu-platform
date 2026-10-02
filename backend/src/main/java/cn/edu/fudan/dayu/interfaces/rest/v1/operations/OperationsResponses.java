package cn.edu.fudan.dayu.interfaces.rest.v1.operations;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.discovery.api.ProductHealthStatus;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.operations.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** 显式 HTTP 响应，允许真实空时间，不通过 Map.of 或空字符串伪造缺失值。 */
final class OperationsResponses {
    private OperationsResponses() {}
    record Page<T>(List<T> items,int page,int pageSize,long total) {}
    record Statistics(long authorizedRequests,long uniqueUsers,long uniqueOrganizations,long uniqueAssets) {}
    record Dashboard(long publishedProducts,long previewAvailableProducts,long downloadAvailableProducts,
                     long missingProducts,long staleProducts,Scan latestScan,Statistics downloads) {}
    record Health(String productCode,DataMode dataMode,ProductHealthStatus status,Instant latestValidTime,long staleAfterMinutes) {}
    record Scan(long scanRunId,ScanTrigger trigger,ScanStatus status,Instant startedAt,Instant finishedAt,
                long scannedFiles,long createdAssets,long updatedAssets,long removedWebpAssets,long missingNetcdfAssets,
                int errorCount,@JsonInclude(JsonInclude.Include.NON_NULL) List<AssetScanError> errors) {}
    record Audit(long downloadEventId,long userId,String organizationSnapshot,long assetId,List<String> productSnapshot,
                 String fileName,long expectedBytes,String purpose,Instant authorizedAt,DownloadStatus status) {}
    record User(long userId,String email,String organization,UserRole role,UserStatus status) {}

    static Scan scan(ScanRunView r,boolean detail) {
        return new Scan(r.scanRunId(),r.trigger(),r.status(),r.startedAt(),r.finishedAt(),r.scannedFiles(),r.createdAssets(),
                r.updatedAssets(),r.removedWebpAssets(),r.missingNetcdfAssets(),r.errorCount(),detail ? r.errors() : null);
    }
    static User user(UserSummary r) { return new User(r.id().value(),r.email(),r.organization(),r.role(),r.status()); }
    static Statistics statistics(DownloadStatistics r) {
        return new Statistics(r.authorized(),r.uniqueUsers(),r.uniqueOrganizations(),r.uniqueAssets());
    }
    static Health health(ProductHealth r) { return new Health(r.productCode().value(),r.dataMode(),r.status(),r.latestValidTime(),r.staleAfterMinutes()); }
    static Audit audit(DownloadAuditSummary r) {
        return new Audit(r.eventId().value(),r.userId().value(),r.organizationSnapshot(),r.assetId().value(),
                r.productSnapshot().stream().map(ProductCode::value).sorted().toList(),r.fileName(),r.expectedBytes(),r.purpose(),r.authorizedAt(),r.status());
    }
}

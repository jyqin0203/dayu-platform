package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.UserId;
import java.time.Instant;
import java.util.List;

/** 真实持久化任务；errorCount 是全部错误数量，errors 仅为有界安全摘要。 */
public record ScanRunView(long scanRunId, ScanTrigger trigger, ScanStatus status,
        UserId triggeredByUserId, Instant startedAt, Instant finishedAt,
        long scannedFiles, long createdAssets, long updatedAssets,
        long removedWebpAssets, long missingNetcdfAssets, int errorCount,
        List<AssetScanError> errors) {
    public ScanRunView { errors = List.copyOf(errors); }
}

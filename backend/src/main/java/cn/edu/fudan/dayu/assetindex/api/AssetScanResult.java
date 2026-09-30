package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 一次文件索引扫描的完整结果，包含时间、统计、受影响产品和错误明细。
 */
public record AssetScanResult(
        ScanTrigger trigger,
        Instant startedAt,
        Instant finishedAt,
        long scannedFiles,
        long createdAssets,
        long updatedAssets,
        long removedWebpAssets,
        long missingNetcdfAssets,
        Set<ProductCode> affectedProducts,
        List<AssetScanError> errors
) {
    public AssetScanResult {
        affectedProducts = Set.copyOf(affectedProducts);
        errors = List.copyOf(errors);
    }
}

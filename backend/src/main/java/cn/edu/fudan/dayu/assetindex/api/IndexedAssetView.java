package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import java.util.Set;

/**
 * 提供给查询模块的安全文件索引视图。
 * 包含产品和时间等元数据，不包含受控存储路径。
 */
public record IndexedAssetView(
        AssetId assetId, AssetType assetType, Set<ProductCode> products, DataMode dataMode,
        Instant cycleTime, Instant validTime, Integer leadMinutes, String fileName,
        long fileSize, Integer dpi, AssetStatus status
) {
    public IndexedAssetView {
        products = Set.copyOf(products);
    }
}

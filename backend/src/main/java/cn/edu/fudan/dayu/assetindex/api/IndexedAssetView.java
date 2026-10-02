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
        long fileSize, Integer dpi, AssetStatus status, String previewRelativePath
) {
    public IndexedAssetView {
        products = Set.copyOf(products);
        if (assetType != AssetType.WEBP && previewRelativePath != null) {
            throw new IllegalArgumentException("Scientific asset paths must not be exposed");
        }
    }

    /** 兼容骨架调用；真实 WebP 查询增加公开媒体相对路径，NC 始终为空。 */
    public IndexedAssetView(AssetId assetId, AssetType assetType, Set<ProductCode> products,
            DataMode dataMode, Instant cycleTime, Instant validTime, Integer leadMinutes,
            String fileName, long fileSize, Integer dpi, AssetStatus status) {
        this(assetId, assetType, products, dataMode, cycleTime, validTime, leadMinutes,
                fileName, fileSize, dpi, status, null);
    }
}

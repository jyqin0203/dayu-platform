package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.util.Set;

/**
 * AssetIndex 提供给 Download 的受限文件视图。
 * 包含路径安全校验所需的逻辑存储空间和相对路径，但不包含机器绝对路径。
 */
public record DownloadableAsset(
        AssetId assetId, AssetType assetType, AssetStatus status, Set<ProductCode> products,
        String storageKey, String relativePath, String fileName, long fileSize
) {
    public DownloadableAsset {
        products = Set.copyOf(products);
    }
}

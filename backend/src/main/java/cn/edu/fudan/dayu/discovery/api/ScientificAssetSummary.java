package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;
import java.util.Set;

/**
 * 展示给用户的 NetCDF 科学数据资产摘要。
 * 不包含服务器路径，下载时只能使用 assetId 发起授权申请。
 */
public record ScientificAssetSummary(
        AssetId assetId, String fileName, long fileSize, Set<ProductCode> products,
        DataMode dataMode, Instant cycleTime, Instant validTime,
        Integer leadMinutes, AssetStatus status
) {
    public ScientificAssetSummary {
        products = Set.copyOf(products);
    }
}

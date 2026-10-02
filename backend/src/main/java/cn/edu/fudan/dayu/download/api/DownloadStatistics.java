package cn.edu.fudan.dayu.download.api;

import java.util.Map;

/**
 * 指定查询范围内的下载申请、授权、拒绝和分组统计结果。
 */
public record DownloadStatistics(
        long requested, long authorized, long denied, long uniqueAssets,
        Map<String, Long> byProduct, Map<String, Long> byOrganization,
        Map<String, Long> byUser
) {
    public DownloadStatistics {
        byProduct = Map.copyOf(byProduct);
        byOrganization = Map.copyOf(byOrganization);
        byUser = Map.copyOf(byUser);
    }
}

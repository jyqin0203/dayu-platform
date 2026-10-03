package cn.edu.fudan.dayu.download.api;

import java.util.Map;

/**
 * 指定查询范围内的下载申请、授权、拒绝和分组统计结果。
 */
public record DownloadStatistics(
        long requested, long authorized, long denied, long uniqueAssets,
        long uniqueUsers, long uniqueOrganizations,
        Map<String, Long> byProduct, Map<String, Long> byOrganization,
        Map<String, Long> byUser
) {
    /** 保留原 skeleton 构造器；真实实现由 SQL DISTINCT 提供人数和机构数。 */
    public DownloadStatistics(long requested, long authorized, long denied, long uniqueAssets,
                              Map<String, Long> byProduct, Map<String, Long> byOrganization, Map<String, Long> byUser) {
        this(requested, authorized, denied, uniqueAssets, byUser.size(), byOrganization.size(),
                byProduct, byOrganization, byUser);
    }
    public DownloadStatistics {
        byProduct = Map.copyOf(byProduct);
        byOrganization = Map.copyOf(byOrganization);
        byUser = Map.copyOf(byUser);
    }
}

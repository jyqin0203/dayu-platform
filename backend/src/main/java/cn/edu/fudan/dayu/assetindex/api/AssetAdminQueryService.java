package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.PageResult;
import java.util.Optional;

/**
 * AssetIndex 提供给 Operations 的管理员只读查询接口。
 *
 * <p>用于管理后台查看最近扫描结果和历史扫描记录，
 * 不负责触发扫描，也不允许修改或删除物理文件。</p>
 */
public interface AssetAdminQueryService {
    /**
     * 获取最近一次完成的文件索引扫描结果。
     *
     * @return 已有扫描记录时返回结果，否则返回空 Optional
     */
    Optional<AssetScanResult> getLatestScanResult();

    /**
     * 按时间、触发来源和分页条件查询历史扫描摘要。
     *
     * @param query 扫描历史筛选与分页条件
     * @return 扫描历史分页结果
     */
    PageResult<ScanHistorySummary> searchScanHistory(ScanHistoryQuery query);
}

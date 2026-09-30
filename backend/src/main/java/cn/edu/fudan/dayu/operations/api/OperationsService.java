package cn.edu.fudan.dayu.operations.api;

import cn.edu.fudan.dayu.assetindex.api.AssetScanResult;
import cn.edu.fudan.dayu.download.api.DownloadAuditQuery;
import cn.edu.fudan.dayu.download.api.DownloadAuditSummary;
import cn.edu.fudan.dayu.download.api.DownloadStatistics;
import cn.edu.fudan.dayu.download.api.DownloadStatisticsQuery;
import cn.edu.fudan.dayu.identity.api.ChangeUserRoleCommand;
import cn.edu.fudan.dayu.identity.api.ChangeUserStatusCommand;
import cn.edu.fudan.dayu.identity.api.UserSummary;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import java.util.List;

/**
 * Operations 模块对管理员公开的运行状态查询和管理编排接口。
 * 它只协调其他模块的公开 API，不复制其他模块的内部业务规则。
 */
public interface OperationsService {
    /** 汇总产品可用性、最近扫描和下载统计，生成管理仪表盘。 */
    DashboardSummary getDashboard(OperationsQuery query, ActorContext actor);

    /** 批量查询产品的数据健康状态。 */
    List<ProductHealth> getProductHealth(ProductHealthQuery query, ActorContext actor);

    /** 以管理员手动触发来源执行一次增量索引扫描。 */
    AssetScanResult triggerIncrementalScan(ActorContext actor);

    /** 以管理员身份分页查询下载审计。 */
    PageResult<DownloadAuditSummary> searchDownloadAudits(DownloadAuditQuery query, ActorContext actor);

    /** 以管理员身份查询下载统计。 */
    DownloadStatistics getDownloadStatistics(DownloadStatisticsQuery query, ActorContext actor);

    /** 编排 Identity 修改用户账号状态。 */
    UserSummary changeUserStatus(ChangeUserStatusCommand command, ActorContext actor);

    /** 编排 Identity 修改用户角色。 */
    UserSummary changeUserRole(ChangeUserRoleCommand command, ActorContext actor);
}

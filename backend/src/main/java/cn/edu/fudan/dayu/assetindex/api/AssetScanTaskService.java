package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.PageResult;
import java.util.Optional;

/** Operations 的持久化扫描任务入口；不访问或修改原始文件。 */
public interface AssetScanTaskService {
    /** 先提交 RUNNING 记录再异步扫描。外部仅允许 ADMIN 发起 MANUAL；重叠任务返回 CONFLICT。 */
    ScanRunView submitScan(ScanTrigger trigger, ActorContext actor);
    /** 按真实数据库 ID 查询任务；不存在返回空。 */
    Optional<ScanRunView> findScanRun(long scanRunId);
    /** 以开始时间和 ID 倒序分页；列表不加载错误明细。 */
    PageResult<ScanRunView> searchScanRuns(ScanRunQuery query);
}

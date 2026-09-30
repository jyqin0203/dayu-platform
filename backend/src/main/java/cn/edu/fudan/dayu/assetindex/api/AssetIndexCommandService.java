package cn.edu.fudan.dayu.assetindex.api;

/**
 * AssetIndex 模块对外提供的文件索引扫描命令接口。
 *
 * <p>扫描负责识别正式目录中的 WebP 和 NetCDF 文件并更新可查询索引，
 * 不生成、修改或删除物理科学数据文件。</p>
 */
public interface AssetIndexCommandService {
    /**
     * 首次部署或索引重建时执行全量扫描。
     *
     * @return 本次扫描的数量、时间、受影响产品和安全错误明细
     */
    AssetScanResult runInitialFullScan();

    /**
     * 执行一次增量扫描，只处理相对现有索引发生的变化。
     * 定时扫描和管理员手动扫描共用这个能力。
     *
     * @param trigger 本次扫描的触发来源
     * @return 本次扫描结果
     */
    AssetScanResult runIncrementalScan(ScanTrigger trigger);
}

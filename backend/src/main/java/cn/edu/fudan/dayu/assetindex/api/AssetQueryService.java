package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.PageResult;
import java.util.List;

/**
 * AssetIndex 提供给 Discovery 的只读文件索引查询接口。
 *
 * <p>它只返回预览和科学数据检索需要的安全元数据，
 * 不暴露服务器绝对路径，也不负责下载授权。</p>
 */
public interface AssetQueryService {
    /**
     * 查询符合产品、模式和时间条件的 WebP 预览资产。
     *
     * @param criteria WebP 预览筛选条件
     * @return 匹配的索引视图；没有结果时返回空列表
     */
    List<IndexedAssetView> listPreviewAssets(AssetPreviewCriteria criteria);

    /**
     * 查询指定产品在时间范围内可用的预报起报批次。
     *
     * @param criteria 产品和时间范围
     * @return 可用预报批次；没有结果时返回空列表
     */
    List<ForecastCycleSummary> listForecastCycles(AssetForecastCycleCriteria criteria);

    /**
     * 分页查询符合条件的 NetCDF 科学数据资产。
     *
     * @param criteria 产品、模式、时间和分页条件
     * @return NetCDF 索引分页结果
     */
    PageResult<IndexedAssetView> searchNetcdfAssets(AssetSearchCriteria criteria);

    /**
     * 根据产品和时间查找可能对应当前预览的 NetCDF 候选资产。
     * WebP 与 NetCDF 没有强制的一对一关系，因此可能返回零个、一个或多个候选。
     *
     * @param criteria 产品、模式和时间匹配条件
     * @return NetCDF 候选索引视图
     */
    List<IndexedAssetView> findNetcdfCandidates(AssetMatchCriteria criteria);
}

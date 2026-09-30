package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.PageResult;
import java.util.List;

/**
 * Discovery 模块对外公开的数据发现与查询接口。
 * 它基于 Catalog 和 AssetIndex 的真实结果组织预览、科学数据和可用性信息。
 */
public interface DiscoveryQueryService {
    /** @return 按有效时间排列的近期 WebP 预览帧。 */
    List<PreviewFrame> listPreviewFrames(PreviewQuery query);

    /** @return 指定产品和时间范围内可用的预报批次。 */
    List<ForecastCycleSummary> listForecastCycles(ForecastCycleQuery query);

    /** @return 符合条件的历史 NetCDF 科学数据分页结果。 */
    PageResult<ScientificAssetSummary> searchScientificAssets(ScientificAssetQuery query);

    /** @return 可能对应指定预览时间的零个、一个或多个 NetCDF 候选。 */
    List<DownloadCandidate> findDownloadCandidatesForPreview(PreviewDownloadQuery query);

    /** @return 单个产品在指定模式下的预览、下载和健康状态。 */
    ProductAvailability getProductAvailability(ProductAvailabilityQuery query);

    /** @return 多个产品的可用状态，供 Operations 批量汇总。 */
    List<ProductAvailability> listProductAvailability(ProductAvailabilityListQuery query);
}

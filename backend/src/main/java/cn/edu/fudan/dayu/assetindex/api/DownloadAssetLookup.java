package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import java.util.Optional;

/**
 * AssetIndex 专门提供给 Download 模块的受限资产查询接口。
 *
 * <p>与普通查询不同，该接口会返回下载授权所需的逻辑存储空间、
 * 受控相对路径、文件状态和预期大小，但仍不暴露机器绝对路径。</p>
 */
public interface DownloadAssetLookup {
    /**
     * 根据资产编号查找可供下载模块继续校验的文件资产。
     * 该方法只负责查询，不代表用户已经获得下载权限。
     *
     * @param assetId 文件资产的内部唯一编号
     * @return 找到时返回受限下载视图，否则返回空 Optional
     */
    Optional<DownloadableAsset> findDownloadableAsset(AssetId assetId);
}

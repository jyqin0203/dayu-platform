package cn.edu.fudan.dayu.download.application;

import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import java.io.InputStream;
import java.time.Instant;

/** 文件基础设施端口；只允许受信资产路径，验证不读取完整科学数组。 */
public interface DownloadFiles {
    /** 校验根目录、真实路径、类型、大小；二阶段还拒绝授权后修改的文件。 */
    void verify(DownloadableAsset asset, long expectedBytes, Instant authorizedAt);
    /** 再次校验后打开流；调用方负责关闭。 */
    InputStream open(DownloadableAsset asset, long expectedBytes, Instant authorizedAt);
    /** 返回编码后的 Nginx 内部 URI，不包含磁盘根目录。 */
    String internalLocation(DownloadableAsset asset);
}

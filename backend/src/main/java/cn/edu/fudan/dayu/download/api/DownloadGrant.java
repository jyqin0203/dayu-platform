package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.DownloadEventId;
import java.time.Instant;

/**
 * 下载授权成功后交给接入层和 Nginx 适配器的内部结果。
 * internalLocation 不得直接作为公开 JSON 返回给浏览器。
 */
public record DownloadGrant(
        DownloadEventId eventId, String downloadFileName, String contentType,
        long expectedBytes, String internalLocation, Instant authorizedAt, Instant expiresAt
) {
    /** 保持 skeleton 调用兼容；真实实现显式使用配置计算 expiresAt。 */
    public DownloadGrant(DownloadEventId eventId, String downloadFileName, String contentType,
                         long expectedBytes, String internalLocation, Instant authorizedAt) {
        this(eventId, downloadFileName, contentType, expectedBytes, internalLocation,
                authorizedAt, authorizedAt.plus(java.time.Duration.ofMinutes(5)));
    }
}

package cn.edu.fudan.dayu.discovery.api;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import java.net.URI;
import java.time.Instant;

/**
 * 前端时间轴使用的一帧 WebP 预览信息。
 * previewUrl 是公开预览地址，不是服务器物理路径。
 */
public record PreviewFrame(
        AssetId webpAssetId, URI previewUrl, Instant validTime,
        Instant cycleTime, Integer leadMinutes, boolean downloadAvailable, long fileSize
) {
    /** 兼容已有骨架调用；真实实现应提供大小供 Legacy 查询显示。 */
    public PreviewFrame(AssetId id, URI url, Instant valid, Instant cycle, Integer lead, boolean downloadable) {
        this(id, url, valid, cycle, lead, downloadable, 0);
    }
}

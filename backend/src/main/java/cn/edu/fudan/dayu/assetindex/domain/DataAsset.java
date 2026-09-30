package cn.edu.fudan.dayu.assetindex.domain;

import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import java.time.Instant;
import java.util.Objects;

/**
 * 表示硬盘上一个真实 WebP 或 NetCDF 文件的领域对象。
 * 构造时会校验实况/预报时间字段组合以及预报有效时间关系。
 */
public record DataAsset(
        AssetId id, AssetType assetType, DataMode dataMode, Instant cycleTime,
        Instant validTime, Integer leadMinutes, String storageKey, String relativePath,
        String fileName, long fileSize, String checksum, Integer dpi,
        Instant fileModifiedAt, AssetStatus status, Instant indexedAt
) {
    public DataAsset {
        Objects.requireNonNull(validTime, "validTime");
        if (dataMode == DataMode.REALTIME && (cycleTime != null || leadMinutes != null)) {
            throw new IllegalArgumentException("realtime asset cannot have cycle time or lead minutes");
        }
        if (dataMode == DataMode.FORECAST) {
            if (cycleTime == null || leadMinutes == null) {
                throw new IllegalArgumentException("forecast asset requires cycle time and lead minutes");
            }
            if (!validTime.equals(cycleTime.plusSeconds(leadMinutes * 60L))) {
                throw new IllegalArgumentException("valid time must equal cycle time plus lead minutes");
            }
        }
    }
}

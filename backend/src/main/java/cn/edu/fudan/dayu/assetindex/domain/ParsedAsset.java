package cn.edu.fudan.dayu.assetindex.domain;

import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import java.time.Instant;

/** 来自正式路径和文件名的元数据，不读取科学数组；family 或 product 二选一。 */
public record ParsedAsset(AssetType type, DataMode mode, String family, String product,
                          Instant cycleTime, Instant validTime, Integer leadMinutes, Integer dpi) {}

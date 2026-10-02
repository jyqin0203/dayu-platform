package cn.edu.fudan.dayu.assetindex.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;
import cn.edu.fudan.dayu.assetindex.api.AssetScanError;

/** 文件系统出站端口；流式枚举元数据，false 表示根未完整读取，禁止缺失清理。 */
public interface FileInventory {
    record FileEntry(String relativePath, long size, Instant modifiedAt) {}
    boolean visit(Path root, Consumer<FileEntry> files, Consumer<AssetScanError> errors);
}

package cn.edu.fudan.dayu.assetindex.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.List;
import cn.edu.fudan.dayu.assetindex.api.AssetScanError;

/** 文件系统出站端口；流式枚举元数据，false 表示根未完整读取，禁止缺失清理。 */
public interface FileInventory {
    record FileEntry(String relativePath, long size, Instant modifiedAt) {}
    enum ReleaseStatus { READY, NOT_READY, UNREADABLE }
    record ReleaseSnapshot(ReleaseStatus status, List<FileEntry> files) {
        public ReleaseSnapshot { files=List.copyOf(files); }
    }
    boolean visit(Path root, Consumer<FileEntry> files, Consumer<AssetScanError> errors);
    /** Bounded marker/three-file check. Unreadable storage must not trigger missing-file reconciliation. */
    default ReleaseSnapshot inspectReppicCycle(Path root, Instant cycle) {
        return new ReleaseSnapshot(ReleaseStatus.UNREADABLE,List.of());
    }
}

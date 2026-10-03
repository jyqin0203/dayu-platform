package cn.edu.fudan.dayu.assetindex.infrastructure;

import cn.edu.fudan.dayu.assetindex.application.FileInventory;
import cn.edu.fudan.dayu.assetindex.api.AssetScanError;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 枚举只读元数据、不跟随链接；发布检查只读小型 JSON 标记，不读取 NC/图片内容。遍历失败阻止根级缺失清理。 */
@Component
@Profile("!skeleton")
public class LocalFileInventory implements FileInventory {
    @Override public ReleaseSnapshot inspectReppicCycle(Path root,java.time.Instant cycle) {
        return new ReppicReleaseInspector().inspect(root,cycle);
    }
    @Override
    public boolean visit(Path configured, Consumer<FileEntry> files, Consumer<AssetScanError> errors) {
        boolean[] complete = {true};
        Path root = configured.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(root)
                    || !root.toRealPath().equals(root)) throw new IOException();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private String relative(Path file) { return root.relativize(file).toString().replace('\\', '/'); }
                private void failure(Path path, String code) {
                    complete[0] = false;
                    errors.accept(new AssetScanError(relative(path), code, "File or directory could not be indexed safely"));
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (!dir.equals(root) && (name.startsWith(".") || name.equalsIgnoreCase("tmp")
                            || name.equalsIgnoreCase("temp"))) return FileVisitResult.SKIP_SUBTREE;
                    if (!Files.isReadable(dir)) { failure(dir, "UNREADABLE_DIRECTORY"); return FileVisitResult.SKIP_SUBTREE; }
                    try {
                        // 也拒绝 Windows junction/reparse 目录，避免仅检测 isSymbolicLink 的平台差异。
                        if (!dir.toRealPath().equals(dir.toAbsolutePath().normalize())) {
                            failure(dir,"SYMLINK_REJECTED"); return FileVisitResult.SKIP_SUBTREE;
                        }
                    } catch (IOException e) {
                        failure(dir,"UNREADABLE_DIRECTORY"); return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) { failure(file, "SYMLINK_REJECTED"); return FileVisitResult.CONTINUE; }
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String name = file.getFileName().toString();
                    if (name.startsWith(".") || !(name.endsWith(".nc") || name.endsWith(".webp"))) return FileVisitResult.CONTINUE;
                    try {
                        if (!Files.isReadable(file)) failure(file, "UNREADABLE_FILE");
                        else if (!file.toRealPath().startsWith(root)) failure(file,"SYMLINK_REJECTED");
                        else files.accept(new FileEntry(relative(file), attrs.size(), attrs.lastModifiedTime().toInstant()));
                    } catch (IOException e) { failure(file,"UNREADABLE_FILE"); }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    failure(file, "UNREADABLE_FILE"); return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) failure(dir, "UNREADABLE_DIRECTORY");
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) {
            complete[0] = false;
            errors.accept(new AssetScanError("", "UNREADABLE_ROOT", "Configured storage root is unavailable"));
        }
        return complete[0];
    }
}

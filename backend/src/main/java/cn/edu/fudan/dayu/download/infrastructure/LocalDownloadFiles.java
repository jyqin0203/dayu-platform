package cn.edu.fudan.dayu.download.infrastructure;

import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.download.application.DownloadFiles;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 验证配置根与真实路径，文件内容通过 InputStream 逐块发送，不进入字节数组。 */
@Component
@Profile("!skeleton")
public class LocalDownloadFiles implements DownloadFiles {
    private final DownloadSettings settings;
    private final Path indexRoot;
    public LocalDownloadFiles(DownloadSettings settings,
            @Value("${dayu.indexing.roots.netcdf-data:}") String indexRoot) {
        this.settings = settings;
        this.indexRoot = indexRoot == null || indexRoot.isBlank() ? null : Path.of(indexRoot);
    }
    @Override public void verify(DownloadableAsset asset, long expectedBytes, Instant authorizedAt) {
        checked(asset, expectedBytes, authorizedAt);
    }
    private Path checked(DownloadableAsset asset, long expectedBytes, Instant authorizedAt) {
        validateLocation(asset);
        try {
            Path configured = settings.getNetcdfRoot() == null ? indexRoot : settings.getNetcdfRoot();
            if (configured == null) throw gone("科学数据存储暂不可用");
            Path root = configured.toRealPath();
            // 同时配置两个根时要求它们一致，避免查询一处却下载另一处。
            if (settings.getNetcdfRoot() != null && indexRoot != null
                    && !root.equals(indexRoot.toRealPath())) throw gone("科学数据存储配置不一致");
            Path path = root.resolve(asset.relativePath()).toRealPath();
            if (!path.startsWith(root)) throw forbidden();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || !Files.isReadable(path)) throw gone("科学数据文件当前不可读");
            if (expectedBytes < 0 || attributes.size() != expectedBytes)
                throw gone("科学数据文件大小已变化，请重新检索");
            if (authorizedAt != null && attributes.lastModifiedTime().toInstant().isAfter(authorizedAt))
                throw gone("科学数据文件已更新，请重新申请下载");
            return path;
        } catch (IOException | InvalidPathException e) { throw gone("科学数据文件当前不可用"); }
    }
    @Override public InputStream open(DownloadableAsset asset, long bytes, Instant authorizedAt) {
        Path path = checked(asset, bytes, authorizedAt);
        try {
            var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            try {
                if (channel.size() != bytes) throw gone("科学数据文件大小已变化");
                // 打开后再次检查真实路径/修改时间，减少上游替换文件的时间窗口。
                checked(asset, bytes, authorizedAt);
                return Channels.newInputStream(channel);
            } catch (RuntimeException | IOException error) {
                channel.close();
                throw error;
            }
        } catch (IOException e) { throw gone("科学数据文件当前不可读"); }
    }
    @Override public String internalLocation(DownloadableAsset asset) {
        validateLocation(asset);
        try { return new URI(null, null, settings.getInternalPrefix() + asset.relativePath(), null).toASCIIString(); }
        catch (URISyntaxException e) { throw forbidden(); }
    }
    private void validateLocation(DownloadableAsset asset) {
        if (!"netcdf-data".equals(asset.storageKey()) && !settings.getStorageAliases().contains(asset.storageKey()))
            throw forbidden();
        String path = asset.relativePath();
        String name = asset.fileName();
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")
                || path.contains("%") || path.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
            throw forbidden();
        if (name == null || name.isBlank() || name.length() > 512 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl)
                || !name.toLowerCase(Locale.ROOT).endsWith(".nc") || !path.endsWith("/" + name) && !path.equals(name))
            throw forbidden();
    }
    private static BusinessException forbidden() { return new BusinessException(ErrorCode.FORBIDDEN, "文件路径不允许下载"); }
    private static BusinessException gone(String message) { return new BusinessException(ErrorCode.ASSET_GONE, message); }
}

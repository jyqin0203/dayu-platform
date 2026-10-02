package cn.edu.fudan.dayu.catalog.infrastructure;

import cn.edu.fudan.dayu.catalog.application.ColorbarVerifier;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 检查规范路径和真实路径；符号链接不能将色标导向配置根目录之外。 */
@Component
@Profile("!skeleton")
public class FileColorbarVerifier implements ColorbarVerifier {
    private final CatalogStorageProperties properties;
    public FileColorbarVerifier(CatalogStorageProperties properties) { this.properties = properties; }

    @Override public void verify(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/")
                || relativePath.contains("\\") || relativePath.contains(":") || relativePath.contains("%")
                || relativePath.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(relativePath.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
            throw unavailable();
        try {
            if (properties.getColorbarRoot() == null) throw unavailable();
            Path root = properties.getColorbarRoot().toRealPath();
            Path candidate = root.resolve(relativePath).toRealPath();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate) || !Files.isReadable(candidate))
                throw unavailable();
        } catch (IOException | InvalidPathException e) { throw unavailable(); }
    }
    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "必要色标未配置或不可用");
    }
}

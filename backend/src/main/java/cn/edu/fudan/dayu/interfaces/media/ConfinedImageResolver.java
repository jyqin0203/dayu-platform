package cn.edu.fudan.dayu.interfaces.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** 只返回根目录内的白名单图片；真实路径复核阻止符号链接/junction 逃逸。 */
final class ConfinedImageResolver extends PathResourceResolver {
    private final Set<String> extensions;
    ConfinedImageResolver(Set<String> extensions) { this.extensions=Set.copyOf(extensions); }

    @Override protected Resource getResource(String resourcePath,Resource location) throws IOException {
        if (resourcePath==null || resourcePath.startsWith("/") || resourcePath.contains("\\")
                || resourcePath.contains(":") || resourcePath.contains("%")
                || resourcePath.chars().anyMatch(c -> c<32 || c==127)
                || Arrays.stream(resourcePath.split("/",-1)).anyMatch(s -> s.isBlank() || s.startsWith("."))) return null;
        int dot=resourcePath.lastIndexOf('.');
        if (dot<0 || !extensions.contains(resourcePath.substring(dot+1).toLowerCase(Locale.ROOT))) return null;
        Resource candidate=super.getResource(resourcePath,location);
        if (candidate==null) return null;
        try {
            Path root=location.getFile().toPath().toRealPath();
            Path real=candidate.getFile().toPath().toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real) || !Files.isReadable(real)) return null;
            return new PathResource(real);
        } catch (IOException unavailable) { return null; }
    }
}

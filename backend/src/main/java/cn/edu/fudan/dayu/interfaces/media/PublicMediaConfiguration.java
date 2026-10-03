package cn.edu.fudan.dayu.interfaces.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 可选的本地图片传输适配器；不查询业务表，不扫描目录，也不公开科学数据文件。
 * 色标根是包含 colorbars/ 或 CPP_Colorbar/ 的父目录，与 Catalog 的相对路径保持一致。
 */
@Configuration
@Profile("!skeleton")
public class PublicMediaConfiguration implements WebMvcConfigurer {
    private final boolean enabled;
    private final String webpRoot;
    private final String colorbarRoot;
    private final String ncRoot;
    private final String prefix;
    private final String legacyAlias;

    public PublicMediaConfiguration(@Value("${dayu.media.enabled:false}") boolean enabled,
            @Value("${dayu.indexing.roots.webp-preview:}") String webpRoot,
            @Value("${dayu.catalog.colorbar-root:}") String colorbarRoot,
            @Value("${dayu.indexing.roots.netcdf-data:}") String ncRoot,
            @Value("${dayu.preview.public-prefix:/media/webp/}") String prefix,
            @Value("${dayu.legacy.webp-alias:WebP/WebP_V2_Dpi500_4KM}") String legacyAlias) {
        this.enabled=enabled; this.webpRoot=webpRoot; this.colorbarRoot=colorbarRoot;
        this.ncRoot=ncRoot; this.prefix=prefix;
        this.legacyAlias=legacyAlias;
    }

    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!enabled) return;
        if (!prefix.matches("/(?:[A-Za-z0-9_-]+/)+") || prefix.startsWith("/api/"))
            throw new IllegalArgumentException("Invalid public preview prefix");
        if (!legacyAlias.matches("WebP/[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid Legacy image alias");
        Path nc=directory(ncRoot);
        Path webp=directory(webpRoot);
        Path colors=directory(colorbarRoot);
        separate(webp,nc); separate(colors,nc);
        if (webp!=null) {
            register(registry,prefix+"**",webp,Set.of("webp"));
            register(registry,"/"+legacyAlias+"/**",webp,Set.of("webp"));
        }
        if (colors!=null) {
            // Do not mount the entire parent directory or arbitrary Catalog paths.
            for (String child : new String[]{"colorbars","CPP_Colorbar"}) {
                Path location=colors.resolve(child);
                if (Files.isDirectory(location)) {
                    try {
                        Path real=location.toRealPath();
                        if (!real.startsWith(colors)) throw new IllegalArgumentException("Colorbar directory escapes its configured root");
                        separate(real,nc);
                        register(registry,"/"+child+"/**",real,Set.of("webp","png","jpg","jpeg"));
                    } catch (IOException error) { throw new IllegalArgumentException("Colorbar directory unavailable"); }
                }
            }
        }
    }

    private static void register(ResourceHandlerRegistry registry,String pattern,Path root,Set<String> extensions) {
        registry.addResourceHandler(pattern).addResourceLocations(root.toUri().toString())
                .setCacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .resourceChain(false).addResolver(new ConfinedImageResolver(extensions));
    }
    private static Path directory(String value) {
        if (value==null || value.isBlank()) return null;
        try {
            Path path=Path.of(value);
            if (!path.isAbsolute() || !Files.isDirectory(path)) throw new IllegalArgumentException("Media/data root must be an existing absolute directory");
            return path.toRealPath();
        } catch (IOException error) { throw new IllegalArgumentException("Configured media/data directory unavailable"); }
    }
    private static void separate(Path images,Path nc) {
        if (images!=null && nc!=null && (images.startsWith(nc) || nc.startsWith(images)))
            throw new IllegalArgumentException("Public image directories and NetCDF directories must not overlap");
    }
}

package cn.edu.fudan.dayu.catalog.infrastructure;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 色标相对路径以此根目录解析；例如 path=colorbars/a.png 时根目录应是静态资源根。 */
@Component
@ConfigurationProperties(prefix = "dayu.catalog")
public class CatalogStorageProperties {
    private Path colorbarRoot;
    public Path getColorbarRoot() { return colorbarRoot; }
    public void setColorbarRoot(Path colorbarRoot) { this.colorbarRoot = colorbarRoot; }
}

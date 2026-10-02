package cn.edu.fudan.dayu.assetindex.application;

import cn.edu.fudan.dayu.shared.kernel.AssetType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 部署提供 roots，键是逻辑存储空间。默认不调度、不扫描任何机器目录。 */
@Component
@Profile("!skeleton")
@ConfigurationProperties("dayu.indexing")
public class IndexSettings {
    private Map<String, Path> roots = new LinkedHashMap<>();
    private boolean enabled;
    private Duration interval = Duration.ofMinutes(15);
    private int dpi = 500;
    private int maxErrors = 100;
    private Map<String, Set<Integer>> expectedLeads = new HashMap<>();

    public Map<String, Path> getRoots() { return roots; }
    public void setRoots(Map<String, Path> roots) { this.roots = new LinkedHashMap<>(roots); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getInterval() { return interval; }
    public void setInterval(Duration interval) { this.interval = interval; }
    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }
    public int getMaxErrors() { return maxErrors; }
    public void setMaxErrors(int maxErrors) { this.maxErrors = maxErrors; }
    public Map<String, Set<Integer>> getExpectedLeads() { return expectedLeads; }
    public void setExpectedLeads(Map<String, Set<Integer>> leads) { this.expectedLeads = leads; }

    public AssetType typeOf(String storageKey) {
        return switch (storageKey) {
            case "webp-preview" -> AssetType.WEBP;
            case "netcdf-data" -> AssetType.NETCDF;
            default -> throw new IllegalArgumentException("Unsupported indexing storage key");
        };
    }

    public void validate() {
        if (dpi < 1 || maxErrors < 1 || maxErrors > 10000 || interval == null
                || interval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Invalid indexing configuration");
        }
        roots.forEach((key, path) -> { typeOf(key); Objects.requireNonNull(path); });
    }
}

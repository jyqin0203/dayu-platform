package cn.edu.fudan.dayu.download.infrastructure;

import cn.edu.fudan.dayu.download.application.DownloadPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 本地默认流式传输；生产显式配置 NGINX 并部署 internal location。 */
@Component
@ConfigurationProperties(prefix = "dayu.download")
public class DownloadSettings implements DownloadPolicy {
    public enum TransferMode { LOCAL, NGINX }
    private Path netcdfRoot;
    private Duration grantTtl = Duration.ofMinutes(5);
    private TransferMode transferMode = TransferMode.LOCAL;
    private String internalPrefix = "/internal-netcdf/";
    private Set<String> storageAliases = Set.of();
    public Path getNetcdfRoot() { return netcdfRoot; }
    public void setNetcdfRoot(Path value) { netcdfRoot = value; }
    public Duration getGrantTtl() { return grantTtl; }
    public void setGrantTtl(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("download grant TTL must be positive and at most one day");
        grantTtl = value;
    }
    public TransferMode getTransferMode() { return transferMode; }
    public void setTransferMode(TransferMode value) { transferMode = java.util.Objects.requireNonNull(value); }
    public String getInternalPrefix() { return internalPrefix; }
    public void setInternalPrefix(String value) {
        if (value == null || !value.matches("/[A-Za-z0-9_-]+/"))
            throw new IllegalArgumentException("download internal prefix must be a single URI directory");
        internalPrefix = value;
    }
    public Set<String> getStorageAliases() { return storageAliases; }
    public void setStorageAliases(Set<String> value) { storageAliases = Set.copyOf(value); }
    @Override public Duration grantTtl() { return grantTtl; }
    @Override public boolean nginxTransfer() { return transferMode == TransferMode.NGINX; }
}

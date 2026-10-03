package cn.edu.fudan.dayu.interfaces.rest.legacy;

import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;

/** Parses known public URL aliases only. No filesystem access and no substring-based root checks. */
final class LegacyPathParser {
    static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("uuuuMMddHHmm")
            .withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);
    record Directory(AssetType type, DataMode mode, Instant cycle, ProductCode product, Integer leadMinutes) {}
    private final String webpRoot;
    private final String netcdfRoot;

    LegacyPathParser(String webpRoot, String netcdfRoot) {
        this.webpRoot = safe(webpRoot);
        this.netcdfRoot = safe(netcdfRoot);
        if (this.webpRoot == null || !this.webpRoot.startsWith("WebP/")
                || this.netcdfRoot == null || this.netcdfRoot.contains("/")) {
            throw new IllegalArgumentException("Invalid Legacy storage alias");
        }
    }
    Optional<Directory> directory(String value) {
        String path = safe(value);
        if (path == null) return Optional.empty();
        AssetType type;
        String suffix;
        if (path.startsWith(webpRoot + "/")) { type = AssetType.WEBP; suffix = path.substring(webpRoot.length() + 1); }
        else if (path.startsWith(netcdfRoot + "/")) {
            type = AssetType.NETCDF;
            suffix = path.substring(netcdfRoot.length() + 1);
        }
        else return Optional.empty();
        String[] parts = suffix.split("/", -1);
        try {
            if (parts[0].equals("realtime") && (parts.length == 1 || type == AssetType.WEBP && parts.length == 2)) {
                if (parts.length==2 && leadMinutes(parts[1])!=null) return Optional.empty();
                return Optional.of(new Directory(type, DataMode.REALTIME, null, parts.length == 2 ? code(parts[1]) : null,null));
            }
            if (parts[0].equals("forecast") && (parts.length == 1 || parts.length == 2 || type == AssetType.WEBP && parts.length == 3))
                return Optional.of(new Directory(type, DataMode.FORECAST, parts.length > 1 ? time(parts[1]) : null,
                        parts.length == 3 ? code(parts[2]) : null,parts.length==3 ? leadMinutes(parts[2]) : null));
        } catch (IllegalArgumentException | DateTimeException | BusinessException invalid) { return Optional.empty(); }
        return Optional.empty();
    }
    boolean forecastRoot(String value) { return (webpRoot + "/forecast").equals(safe(value)); }
    String webpAlias() { return webpRoot; }
    String netcdfAlias() { return netcdfRoot; }
    static ProductCode code(String value) {
        String code = value.startsWith("FCST_") ? value.substring(5) : value;
        if (!code.matches("[A-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid product");
        if (leadMinutes(code)!=null) return new ProductCode("PRECIP");
        return new ProductCode(code);
    }
    /** Only the three verified Legacy aliases are normalized; the domain keeps one PRECIP product. */
    static Integer leadMinutes(String value) {
        String code=value.startsWith("FCST_") ? value.substring(5) : value;
        if (code.matches("PRECIP_[123]H")) return (code.charAt(7)-'0')*60;
        if (code.matches("PRECIP_[0-9]+H")) throw new IllegalArgumentException("Unsupported precipitation lead");
        return null;
    }
    static Instant time(String value) {
        if (value == null || !value.matches("[0-9]{12}")) throw new IllegalArgumentException("Invalid time");
        return LocalDateTime.parse(value, COMPACT).toInstant(ZoneOffset.UTC);
    }
    String ncRelativePath(String value) {
        String path = safe(value);
        if (path == null || !path.startsWith(netcdfRoot + "/") || !path.toLowerCase(Locale.ROOT).endsWith(".nc"))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid file path.");
        String relative = path.substring(netcdfRoot.length() + 1);
        if (!(relative.startsWith("realtime/") || relative.startsWith("forecast/"))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid file path.");
        }
        return relative;
    }

    String ncAlias(String relativePath) {
        String relative = safe(relativePath);
        if (relative == null || !(relative.startsWith("realtime/") || relative.startsWith("forecast/"))
                || !relative.toLowerCase(Locale.ROOT).endsWith(".nc")) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "NetCDF asset path is invalid.");
        }
        return netcdfRoot + "/" + relative;
    }
    /** Browser decoding already happened; any remaining percent sequence is rejected, not decoded again. */
    static String safe(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024) return null;
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("//") || normalized.contains("%")
                || normalized.contains(":") || normalized.contains("?") || normalized.contains("#")
                || normalized.chars().anyMatch(Character::isISOControl)) return null;
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        for (String part : normalized.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9_.-]+")) return null;
        return normalized;
    }
}

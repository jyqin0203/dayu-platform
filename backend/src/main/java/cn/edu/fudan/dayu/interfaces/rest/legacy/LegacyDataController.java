package cn.edu.fudan.dayu.interfaces.rest.legacy;

import cn.edu.fudan.dayu.assetindex.api.DownloadAssetLookup;
import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.discovery.api.ForecastCycleQuery;
import cn.edu.fudan.dayu.discovery.api.PreviewQuery;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetQuery;
import cn.edu.fudan.dayu.download.api.ClientContext;
import cn.edu.fudan.dayu.download.api.DownloadAuthorizationService;
import cn.edu.fudan.dayu.download.api.DownloadCommand;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 旧 WebP/NC 查询和直接下载表单的兼容适配器。 */
@RestController
public class LegacyDataController {
    private static final DateTimeFormatter COMPACT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private final DiscoveryQueryService discovery;
    private final DownloadAssetLookup assets;
    private final DownloadAuthorizationService downloads;
    private final CurrentActorProvider actors;

    public LegacyDataController(DiscoveryQueryService discovery, DownloadAssetLookup assets,
                                DownloadAuthorizationService downloads, CurrentActorProvider actors) {
        this.discovery = discovery;
        this.assets = assets;
        this.downloads = downloads;
        this.actors = actors;
    }

    @GetMapping("/api/files.php")
    public Map<String, Object> files(@RequestParam String path,
                                     @RequestParam(defaultValue = "48") int number) {
        ParsedPath parsed = parseWebp(path);
        if (parsed == null || number < 1 || number > 200) return Map.of("files", List.of());
        var frames = discovery.listPreviewFrames(new PreviewQuery(parsed.product(), parsed.mode(),
                Instant.EPOCH, Instant.now(), parsed.cycle(), null, number));
        return Map.of("files", frames.stream().map(frame -> frame.previewUrl().toString()).toList());
    }

    @GetMapping("/api/fcst_latest.php")
    public Map<String, Object> latest(@RequestParam String path, @RequestParam String product) {
        if (!path.replace('\\', '/').contains("/forecast")) return Map.of("latest", "");
        String normalized = product.replaceFirst("^FCST_", "");
        var cycles = discovery.listForecastCycles(new ForecastCycleQuery(
                new ProductCode(normalized), AssetType.WEBP, Instant.EPOCH, Instant.now()));
        return Map.of("latest", cycles.isEmpty() ? "" : COMPACT.format(cycles.get(0).cycleTime()));
    }

    @GetMapping("/api/search.php")
    public Map<String, Object> search(
            @RequestParam String type, @RequestParam String dir,
            @RequestParam String start, @RequestParam String end,
            @RequestParam(required = false) String product) {
        if (!"multi".equals(type)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid parameter.");
        }
        try {
            Instant from = parseCompact(start);
            Instant to = parseCompact(end);
            String code = product == null || product.isBlank() ? lastSegment(dir)
                    : product.replaceFirst("^FCST_", "");
            DataMode mode = dir.contains("/forecast/") ? DataMode.FORECAST : DataMode.REALTIME;
            if (dir.toLowerCase().contains("netcdf")) {
                var result = discovery.searchScientificAssets(new ScientificAssetQuery(
                        new ProductCode(code), mode, from, to, cycleFrom(dir), null,
                        new PageRequest(1, 200)));
                return Map.of(
                        "files", result.items().stream().map(a -> legacyNcPath(a.fileName(), a.cycleTime())).toList(),
                        "sizes", result.items().stream().map(a -> formatSize(a.fileSize())).toList(),
                        "times", result.items().stream().map(a -> DISPLAY.format(a.validTime())).toList());
            }
            var frames = discovery.listPreviewFrames(new PreviewQuery(
                    new ProductCode(code), mode, from, to, cycleFrom(dir), null, 200));
            return Map.of(
                    "files", frames.stream().map(f -> f.previewUrl().toString()).toList(),
                    "sizes", frames.stream().map(f -> "").toList(),
                    "times", frames.stream().map(f -> DISPLAY.format(f.validTime())).toList());
        } catch (RuntimeException error) {
            return Map.of("files", List.of(), "sizes", List.of(), "times", List.of());
        }
    }

    @PostMapping("/api/download.php")
    public ResponseEntity<Void> download(
            @RequestParam("file_path") String filePath, @RequestParam String purpose,
            HttpServletRequest request) {
        String normalized = normalizeNcPath(filePath);
        var asset = assets.findByStoragePath("netcdf-science", normalized)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "数据文件不存在"));
        var grant = downloads.authorizeDownload(new DownloadCommand(asset.assetId(), purpose),
                actors.required(), new ClientContext(request.getRemoteAddr(), request.getHeader("User-Agent")));
        return ResponseEntity.ok().header("X-Accel-Redirect", grant.internalLocation())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + grant.downloadFileName() + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(grant.expectedBytes())).build();
    }

    private static ParsedPath parseWebp(String path) {
        String normalized = path.replace('\\', '/');
        String[] parts = normalized.split("/");
        if (!normalized.startsWith("WebP/") || parts.length < 4) return null;
        int forecast = java.util.Arrays.asList(parts).indexOf("forecast");
        if (forecast >= 0 && parts.length > forecast + 2) {
            return new ParsedPath(new ProductCode(parts[forecast + 2]), DataMode.FORECAST,
                    parseCompact(parts[forecast + 1]));
        }
        int realtime = java.util.Arrays.asList(parts).indexOf("realtime");
        return realtime >= 0 && parts.length > realtime + 1
                ? new ParsedPath(new ProductCode(parts[realtime + 1]), DataMode.REALTIME, null) : null;
    }

    private static Instant parseCompact(String value) {
        return java.time.LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
                .toInstant(ZoneOffset.UTC);
    }
    private static Instant cycleFrom(String dir) {
        String[] parts = dir.replace('\\', '/').split("/");
        for (String part : parts) if (part.matches("\\d{12}")) return parseCompact(part);
        return null;
    }
    private static String lastSegment(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        return parts[parts.length - 1].replaceFirst("^FCST_", "");
    }
    private static String legacyNcPath(String name, Instant cycle) {
        return cycle == null ? "netcdf/realtime/" + name
                : "netcdf/forecast/" + COMPACT.format(cycle) + "/" + name;
    }
    private static String normalizeNcPath(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..") || !normalized.startsWith("netcdf/")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid file path.");
        }
        return normalized.substring("netcdf/".length());
    }
    private static String formatSize(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1024d / 1024d);
    }
    private record ParsedPath(ProductCode product, DataMode mode, Instant cycle) {}
}

package cn.edu.fudan.dayu.interfaces.rest.legacy;

import cn.edu.fudan.dayu.assetindex.api.DownloadAssetLookup;
import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.catalog.api.ProductDetail;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.discovery.api.ForecastCycleQuery;
import cn.edu.fudan.dayu.discovery.api.PreviewFrame;
import cn.edu.fudan.dayu.discovery.api.PreviewQuery;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetQuery;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetSummary;
import cn.edu.fudan.dayu.download.api.ClientContext;
import cn.edu.fudan.dayu.download.api.DownloadAuthorizationService;
import cn.edu.fudan.dayu.download.api.DownloadCommand;
import cn.edu.fudan.dayu.download.api.DownloadContentService;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.interfaces.rest.v1.download.DownloadHttpResponse;
import cn.edu.fudan.dayu.shared.kernel.ActorContext;
import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 旧 WebP/NC 查询和直接下载表单的无文件系统兼容适配器。 */
@RestController
public class LegacyDataController {
    private static final DateTimeFormatter COMPACT =
            DateTimeFormatter.ofPattern("uuuuMMddHHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final int DISCOVERY_PAGE_SIZE = 200;

    private final DiscoveryQueryService discovery;
    private final CatalogQueryService catalog;
    private final DownloadAssetLookup assets;
    private final DownloadAuthorizationService downloads;
    private final DownloadContentService contents;
    private final CurrentActorProvider actors;
    private final LegacyPathParser paths;
    private final String previewPrefix;
    private final Duration previewWindow;
    private final int maxSearchResults;
    private final Clock clock;

    @Autowired
    public LegacyDataController(
            DiscoveryQueryService discovery, CatalogQueryService catalog, DownloadAssetLookup assets,
            DownloadAuthorizationService downloads, DownloadContentService contents, CurrentActorProvider actors,
            @Value("${dayu.legacy.webp-alias:WebP/WebP_V2_Dpi500_4KM}") String webpAlias,
            @Value("${dayu.legacy.netcdf-alias:netcdf}") String netcdfAlias,
            @Value("${dayu.preview.public-prefix:/media/webp/}") String previewPrefix,
            @Value("${dayu.preview.retention:3d}") Duration previewWindow,
            @Value("${dayu.legacy.max-search-results:2000}") int maxSearchResults) {
        this(discovery, catalog, assets, downloads, contents, actors,
                new LegacyPathParser(webpAlias, netcdfAlias), previewPrefix, previewWindow,
                maxSearchResults, Clock.systemUTC());
    }

    LegacyDataController(
            DiscoveryQueryService discovery, CatalogQueryService catalog, DownloadAssetLookup assets,
            DownloadAuthorizationService downloads, DownloadContentService contents, CurrentActorProvider actors,
            LegacyPathParser paths, String previewPrefix, Duration previewWindow,
            int maxSearchResults, Clock clock) {
        this.discovery = discovery;
        this.catalog = catalog;
        this.assets = assets;
        this.downloads = downloads;
        this.contents = contents;
        this.actors = actors;
        this.paths = paths;
        this.previewPrefix = normalizedPreviewPrefix(previewPrefix);
        if (previewWindow == null || previewWindow.isZero() || previewWindow.isNegative()) {
            throw new IllegalArgumentException("Legacy preview window must be positive");
        }
        this.previewWindow = previewWindow;
        if (maxSearchResults < 1) throw new IllegalArgumentException("Legacy search result limit must be positive");
        this.maxSearchResults = maxSearchResults;
        this.clock = clock;
    }

    /** 返回最近窗口内最新 N 帧，并保持旧播放器要求的正序。 */
    @GetMapping("/api/files.php")
    public Map<String, Object> files(@RequestParam String path,
                                     @RequestParam(defaultValue = "48") int number) {
        Optional<LegacyPathParser.Directory> parsed = paths.directory(path);
        if (number < 1 || number > 200 || parsed.isEmpty()
                || parsed.get().type() != AssetType.WEBP || parsed.get().product() == null) {
            return filesOnly(List.of());
        }
        var directory = parsed.get();
        if (!enabled(directory.product(), directory.mode())) return filesOnly(List.of());
        Instant to;
        Instant from;
        if (directory.mode() == DataMode.FORECAST) {
            var batch = discovery.listForecastCycles(new ForecastCycleQuery(
                            directory.product(), AssetType.WEBP, directory.cycle(), directory.cycle())).stream()
                    .filter(cycle -> cycle.cycleTime().equals(directory.cycle()))
                    .findFirst();
            if (batch.isEmpty()) return filesOnly(List.of());
            to = batch.get().lastValidTime();
            from = later(batch.get().firstValidTime(), to.minus(previewWindow));
        } else {
            to = clock.instant();
            from = to.minus(previewWindow);
        }
        List<String> files = discovery.listPreviewFrames(new PreviewQuery(
                        directory.product(), directory.mode(), from, to,
                        directory.cycle(), null, number)).stream()
                .map(this::legacyWebpPath)
                .toList();
        return filesOnly(files);
    }

    /** 返回实际可用 WebP 资产中的最新预报批次；product 省略时遍历所有已发布预报产品。 */
    @GetMapping("/api/fcst_latest.php")
    public Map<String, Object> latest(
            @RequestParam String path,
            @RequestParam(required = false) String product) {
        if (!paths.forecastRoot(path)) return Map.of("latest", "");
        List<ProductCode> products;
        if (product == null || product.isBlank()) {
            products = enabledProducts(DataMode.FORECAST);
        } else {
            Optional<ProductCode> code = productCode(product);
            if (code.isEmpty() || !enabled(code.get(), DataMode.FORECAST)) return Map.of("latest", "");
            products = List.of(code.get());
        }
        Instant now = clock.instant();
        Instant latest = products.stream()
                .flatMap(code -> discovery.listForecastCycles(
                        new ForecastCycleQuery(code, AssetType.WEBP, Instant.EPOCH, now)).stream())
                .map(cycle -> cycle.cycleTime())
                .max(Comparator.naturalOrder())
                .orElse(null);
        return Map.of("latest", latest == null ? "" : COMPACT.format(latest));
    }

    /** 保留旧平行数组；真实结果统一按有效时刻倒序，并明确拒绝超量结果。 */
    @GetMapping("/api/search.php")
    public Map<String, Object> search(
            @RequestParam String type, @RequestParam String dir,
            @RequestParam String start, @RequestParam String end,
            @RequestParam(required = false) String product) {
        if (!"multi".equals(type)) throw invalid("Invalid parameter.");
        Optional<LegacyPathParser.Directory> parsed = paths.directory(dir);
        Optional<Instant> from = compactTime(start);
        Optional<Instant> to = compactTime(end);
        if (parsed.isEmpty() || from.isEmpty() || to.isEmpty() || from.get().isAfter(to.get())) return emptySearch();

        LegacyPathParser.Directory directory = parsed.get();
        Optional<ProductCode> explicit = product == null || product.isBlank()
                ? Optional.empty() : productCode(product);
        if (product != null && !product.isBlank() && explicit.isEmpty()) return emptySearch();
        if (directory.product() != null && explicit.isPresent() && !directory.product().equals(explicit.get())) {
            return emptySearch();
        }

        if (directory.type() == AssetType.WEBP) {
            ProductCode code = explicit.orElse(directory.product());
            if (code == null || !enabled(code, directory.mode())) return emptySearch();
            Instant current = clock.instant();
            Instant recentFrom = later(from.get(), current.minus(previewWindow));
            Instant recentTo = to.get();
            if (recentFrom.isAfter(recentTo)) return emptySearch();
            List<PreviewFrame> frames = allWebp(code, directory.mode(), recentFrom, recentTo, directory.cycle());
            frames = frames.stream().sorted(Comparator.comparing(PreviewFrame::validTime).reversed()
                    .thenComparing(frame -> frame.webpAssetId().value(), Comparator.reverseOrder())).toList();
            return searchResponse(
                    frames.stream().map(this::legacyWebpPath).toList(),
                    frames.stream().map(frame -> formatSize(frame.fileSize())).toList(),
                    frames.stream().map(frame -> DISPLAY.format(frame.validTime())).toList());
        }

        List<ProductCode> products = explicit.map(List::of).orElseGet(() -> enabledProducts(directory.mode()));
        if (explicit.isPresent() && !enabled(explicit.get(), directory.mode())) return emptySearch();
        List<ScientificAssetSummary> scientific = allScientific(
                products, directory.mode(), from.get(), to.get(), directory.cycle());
        return searchResponse(
                scientific.stream().map(this::legacyNcPath).toList(),
                scientific.stream().map(asset -> formatSize(asset.fileSize())).toList(),
                scientific.stream().map(asset -> DISPLAY.format(asset.validTime())).toList());
    }

    /** 旧表单仍直接下载，但授权后的事件必须再次通过统一内容服务重验并生成真实响应。 */
    @PostMapping("/api/download.php")
    public ResponseEntity<Resource> download(
            @RequestParam("file_path") String filePath, @RequestParam String purpose,
            HttpServletRequest request) {
        String relativePath = paths.ncRelativePath(filePath);
        DownloadableAsset asset = assets.findByStoragePath("netcdf-data", relativePath)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "数据文件不存在"));
        ActorContext actor = actors.required();
        var grant = downloads.authorizeDownload(new DownloadCommand(asset.assetId(), purpose), actor,
                new ClientContext(request.getRemoteAddr(), request.getHeader("User-Agent")));
        return DownloadHttpResponse.from(contents.prepareContent(grant.eventId(), actor));
    }

    private List<PreviewFrame> allWebp(
            ProductCode product, DataMode mode, Instant from, Instant to, Instant cycle) {
        Map<AssetId, PreviewFrame> unique = new LinkedHashMap<>();
        collectWebp(product, mode, from, to, cycle, unique);
        return List.copyOf(unique.values());
    }

    private void collectWebp(
            ProductCode product, DataMode mode, Instant from, Instant to, Instant cycle,
            Map<AssetId, PreviewFrame> unique) {
        if (Duration.between(from, to).compareTo(previewWindow) > 0) {
            Instant middle = midpoint(from, to);
            collectWebp(product, mode, from, middle, cycle, unique);
            collectWebp(product, mode, middle, to, cycle, unique);
            return;
        }
        List<PreviewFrame> page = discovery.listPreviewFrames(
                new PreviewQuery(product, mode, from, to, cycle, null, DISCOVERY_PAGE_SIZE));
        addFrames(unique, page);
        if (page.size() < DISCOVERY_PAGE_SIZE) return;
        if (!from.isBefore(to.minus(1, ChronoUnit.MINUTES))) throw tooMany();
        Instant middle = midpoint(from, to);
        collectWebp(product, mode, from, middle, cycle, unique);
        collectWebp(product, mode, middle, to, cycle, unique);
    }

    private void addFrames(Map<AssetId, PreviewFrame> unique, List<PreviewFrame> frames) {
        for (PreviewFrame frame : frames) {
            unique.put(frame.webpAssetId(), frame);
            if (unique.size() > maxSearchResults) throw tooMany();
        }
    }

    private List<ScientificAssetSummary> allScientific(
            List<ProductCode> products, DataMode mode, Instant from, Instant to, Instant cycle) {
        Map<AssetId, ScientificAssetSummary> unique = new LinkedHashMap<>();
        for (ProductCode product : products) {
            int page = 1;
            long read = 0;
            do {
                var result = discovery.searchScientificAssets(new ScientificAssetQuery(
                        product, mode, from, to, cycle, null, new PageRequest(page, DISCOVERY_PAGE_SIZE)));
                for (ScientificAssetSummary asset : result.items()) {
                    unique.put(asset.assetId(), asset);
                    if (unique.size() > maxSearchResults) throw tooMany();
                }
                read += result.items().size();
                if (result.items().isEmpty() || read >= result.total()) break;
                page++;
            } while (true);
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(ScientificAssetSummary::validTime).reversed()
                        .thenComparing(asset -> asset.assetId().value(), Comparator.reverseOrder()))
                .toList();
    }

    private String legacyNcPath(ScientificAssetSummary summary) {
        DownloadableAsset asset = assets.findDownloadableAsset(summary.assetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "NetCDF asset lookup failed."));
        if (asset.assetType() != AssetType.NETCDF || asset.status() != AssetStatus.AVAILABLE
                || !"netcdf-data".equals(asset.storageKey())) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "NetCDF asset lookup is inconsistent.");
        }
        return paths.ncAlias(asset.relativePath());
    }

    private String legacyWebpPath(PreviewFrame frame) {
        URI uri = frame.previewUrl();
        if (uri == null || uri.isAbsolute() || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || !uri.getRawPath().startsWith(previewPrefix)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "WebP preview URL is invalid.");
        }
        String relative = uri.getRawPath().substring(previewPrefix.length());
        if (LegacyPathParser.safe(relative) == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "WebP preview URL is invalid.");
        }
        return paths.webpAlias() + "/" + relative;
    }

    private boolean enabled(ProductCode code, DataMode mode) {
        return catalog.findProduct(code).filter(product -> enabled(product, mode)).isPresent();
    }

    private List<ProductCode> enabledProducts(DataMode mode) {
        return catalog.listPublishedProductDetails().stream()
                .filter(product -> enabled(product, mode))
                .map(product -> product.summary().code())
                .distinct()
                .toList();
    }

    private static boolean enabled(ProductDetail product, DataMode mode) {
        return product.summary().status() == ProductStatus.PUBLISHED && product.modePolicies().stream()
                .anyMatch(policy -> policy.dataMode() == mode && policy.enabled());
    }

    private static Optional<ProductCode> productCode(String value) {
        try { return Optional.of(LegacyPathParser.code(value)); }
        catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private static Optional<Instant> compactTime(String value) {
        try { return Optional.of(LegacyPathParser.time(value)); }
        catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private static String normalizedPreviewPrefix(String value) {
        if (value == null || !value.startsWith("/") || value.startsWith("//")
                || value.contains("..") || value.contains("?") || value.contains("#")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid preview public prefix");
        }
        return value.endsWith("/") ? value : value + "/";
    }

    private static Instant later(Instant left, Instant right) { return left.isAfter(right) ? left : right; }
    private static Instant midpoint(Instant from, Instant to) {
        return from.plusMillis(Duration.between(from, to).toMillis() / 2);
    }
    private static Map<String, Object> filesOnly(List<String> files) { return Map.of("files", files); }
    private static Map<String, Object> emptySearch() { return searchResponse(List.of(), List.of(), List.of()); }

    private static Map<String, Object> searchResponse(
            List<String> files, List<String> sizes, List<String> times) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("files", files);
        response.put("sizes", sizes);
        response.put("times", times);
        return response;
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.2f MB", bytes / 1024d / 1024d);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static BusinessException tooMany() {
        return invalid("Search result count exceeds the configured legacy limit.");
    }
}

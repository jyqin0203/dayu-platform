package cn.edu.fudan.dayu.interfaces.rest.v1.discovery;

import cn.edu.fudan.dayu.discovery.api.DiscoveryQueryService;
import cn.edu.fudan.dayu.discovery.api.ForecastCycleQuery;
import cn.edu.fudan.dayu.discovery.api.PreviewQuery;
import cn.edu.fudan.dayu.discovery.api.ScientificAssetQuery;
import cn.edu.fudan.dayu.interfaces.rest.v1.PageParameters;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** 公开的数据预览、预报批次和科学资产检索接口。 */
@RestController
@Validated
public class DiscoveryController {
    private final DiscoveryQueryService discovery;

    public DiscoveryController(DiscoveryQueryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/api/v1/preview-frames")
    public Map<String, Object> previewFrames(
            @RequestParam @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String productCode,
            @RequestParam DataMode dataMode,
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) Instant cycleTime,
            @RequestParam(required = false) Integer leadMinutes) {
        validateRange(from, to);
        var items = discovery.listPreviewFrames(new PreviewQuery(
                new ProductCode(productCode), dataMode, from, to, cycleTime, leadMinutes, 200)).stream()
                .map(frame -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("webpAssetId", frame.webpAssetId().value());
                    item.put("previewUrl", frame.previewUrl());
                    item.put("validTime", frame.validTime());
                    item.put("cycleTime", frame.cycleTime());
                    item.put("leadMinutes", frame.leadMinutes());
                    item.put("downloadAvailable", frame.downloadAvailable());
                    return item;
                })
                .toList();
        return Map.of("items", items);
    }

    @GetMapping("/api/v1/forecast-cycles")
    public Map<String, Object> forecastCycles(
            @RequestParam @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String productCode,
            @RequestParam AssetType assetType,
            @RequestParam Instant from, @RequestParam Instant to) {
        validateRange(from, to);
        var items = discovery.listForecastCycles(new ForecastCycleQuery(
                new ProductCode(productCode), assetType, from, to)).stream()
                .map(cycle -> Map.<String, Object>of(
                        "cycleTime", cycle.cycleTime(),
                        "firstValidTime", cycle.firstValidTime(),
                        "lastValidTime", cycle.lastValidTime(),
                        "frameCount", cycle.leadMinutes().size()))
                .toList();
        return Map.of("items", items);
    }

    @GetMapping("/api/v1/scientific-assets")
    public Map<String, Object> scientificAssets(
            @RequestParam @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String productCode,
            @RequestParam DataMode dataMode,
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) Instant cycleTime,
            @RequestParam(required = false) Integer leadMinutes,
            @Valid @ModelAttribute PageParameters page) {
        validateRange(from, to);
        var result = discovery.searchScientificAssets(new ScientificAssetQuery(
                new ProductCode(productCode), dataMode, from, to, cycleTime, leadMinutes,
                page.toPageRequest()));
        List<Map<String, Object>> items = result.items().stream().map(asset -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", asset.assetId().value());
            item.put("fileName", asset.fileName());
            item.put("fileSize", asset.fileSize());
            item.put("products", asset.products().stream().map(ProductCode::value).sorted().toList());
            item.put("dataMode", asset.dataMode());
            item.put("cycleTime", asset.cycleTime());
            item.put("validTime", asset.validTime());
            item.put("leadMinutes", asset.leadMinutes());
            item.put("status", asset.status());
            return item;
        }).toList();
        return Map.of("items", items, "page", result.page(),
                "pageSize", result.size(), "total", result.total());
    }

    private static void validateRange(Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "from must not be after to");
        }
    }
}

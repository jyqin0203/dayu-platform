package cn.edu.fudan.dayu.interfaces.rest.v1.catalog;

import cn.edu.fudan.dayu.catalog.api.CatalogAdminService;
import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.catalog.api.ConfigureProductModeCommand;
import cn.edu.fudan.dayu.catalog.api.CreateProductCommand;
import cn.edu.fudan.dayu.catalog.api.ManagedProductQuery;
import cn.edu.fudan.dayu.catalog.api.ProductDetail;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.catalog.api.UpdateProductCommand;
import cn.edu.fudan.dayu.interfaces.rest.v1.CurrentActorProvider;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.ProductId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 管理员产品资料、模式和生命周期接口。 */
@RestController
@RequestMapping("/api/v1/admin/products")
public class CatalogAdminController {
    private final CatalogQueryService queries;
    private final CatalogAdminService commands;
    private final CurrentActorProvider actors;

    public CatalogAdminController(CatalogQueryService queries, CatalogAdminService commands,
                                  CurrentActorProvider actors) {
        this.queries = queries;
        this.commands = commands;
        this.actors = actors;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String family,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) String code) {
        actors.required();
        return queries.listManagedProducts(new ManagedProductQuery(
                        family, status, code == null ? null : new ProductCode(code))).stream()
                .map(summary -> queries.findProduct(summary.code()).orElseThrow())
                .map(CatalogAdminController::response)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateRequest body) {
        return response(commands.createProduct(new CreateProductCommand(
                new ProductCode(body.code()), body.nameZh(), body.nameEn(), body.family(), body.unit(),
                body.descriptionZh(), body.descriptionEn(), body.producer(), body.algorithmName(),
                body.sourceDescription(), body.officialSourceUrl(), body.colorbarRequired(),
                body.colorbarPath(), body.sortOrder()), actors.required()));
    }

    @PutMapping("/{productId}")
    public Map<String, Object> update(@PathVariable long productId, @Valid @RequestBody UpdateRequest body) {
        return response(commands.updateProduct(new UpdateProductCommand(
                new ProductId(productId), body.nameZh(), body.nameEn(), body.unit(),
                body.descriptionZh(), body.descriptionEn(), body.producer(), body.algorithmName(),
                body.sourceDescription(), body.officialSourceUrl(), body.colorbarRequired(),
                body.colorbarPath(), body.sortOrder()), actors.required()));
    }

    @PutMapping("/{productId}/modes/{dataMode}")
    public Map<String, Object> mode(@PathVariable long productId, @PathVariable DataMode dataMode,
                                    @Valid @RequestBody ModeRequest body) {
        var mode = commands.configureProductMode(new ConfigureProductModeCommand(
                new ProductId(productId), dataMode, body.enabled(),
                Duration.ofMinutes(body.staleAfterMinutes())), actors.required());
        return Map.of("dataMode", mode.dataMode(), "enabled", mode.enabled(),
                "staleAfterMinutes", mode.staleAfter().toMinutes());
    }

    @PostMapping("/{productId}/publish")
    public Map<String, Object> publish(@PathVariable long productId) {
        return response(commands.publishProduct(new ProductId(productId), actors.required()));
    }

    @PostMapping("/{productId}/disable")
    public Map<String, Object> disable(@PathVariable long productId) {
        return response(commands.disableProduct(new ProductId(productId), actors.required()));
    }

    private static Map<String, Object> response(ProductDetail detail) {
        var p = detail.summary();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", p.id().value()); result.put("code", p.code().value());
        result.put("nameZh", p.nameZh()); result.put("nameEn", p.nameEn());
        result.put("family", p.family()); result.put("unit", p.unit());
        result.put("producer", p.producer()); result.put("algorithmName", p.algorithmName());
        result.put("sourceDescription", p.sourceDescription());
        result.put("officialSourceUrl", p.officialSourceUrl());
        result.put("descriptionZh", detail.descriptionZh()); result.put("descriptionEn", detail.descriptionEn());
        result.put("colorbarRequired", detail.colorbarRequired());
        result.put("colorbarPath", detail.colorbarPath()); result.put("sortOrder", p.sortOrder());
        result.put("status", p.status()); result.put("publishedAt", detail.publishedAt());
        result.put("createdAt", detail.createdAt()); result.put("updatedAt", detail.updatedAt());
        result.put("modes", detail.modePolicies().stream().map(mode -> Map.of(
                "dataMode", mode.dataMode(), "enabled", mode.enabled(),
                "staleAfterMinutes", mode.staleAfter().toMinutes())).toList());
        return result;
    }

    public record CreateRequest(
            @NotBlank String code, @NotBlank String family,
            @Size(min = 2, max = 255) String nameZh, @Size(min = 2, max = 255) String nameEn,
            String unit, String descriptionZh, String descriptionEn,
            @NotBlank String producer, String algorithmName, @NotBlank String sourceDescription,
            URI officialSourceUrl, boolean colorbarRequired, String colorbarPath, int sortOrder) {}
    public record UpdateRequest(
            @Size(min = 2, max = 255) String nameZh, @Size(min = 2, max = 255) String nameEn,
            String unit, String descriptionZh, String descriptionEn,
            @NotBlank String producer, String algorithmName, @NotBlank String sourceDescription,
            URI officialSourceUrl, boolean colorbarRequired, String colorbarPath, int sortOrder) {}
    public record ModeRequest(
            boolean enabled,
            @Min(10) @Max(10080) long staleAfterMinutes) {}
}

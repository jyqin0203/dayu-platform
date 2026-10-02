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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.UserRole;
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
        if (actors.required().role() != UserRole.ADMIN)
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
        if (code != null && !code.matches("^[A-Z][A-Z0-9_]{1,63}$"))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "产品编码不合法");
        return queries.listManagedProductDetails(new ManagedProductQuery(
                        family, status, code == null ? null : new ProductCode(code))).stream()
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
                validatedProductId(productId), body.nameZh(), body.nameEn(), body.unit(),
                body.descriptionZh(), body.descriptionEn(), body.producer(), body.algorithmName(),
                body.sourceDescription(), body.officialSourceUrl(), body.colorbarRequired(),
                body.colorbarPath(), body.sortOrder()), actors.required()));
    }

    @PutMapping("/{productId}/modes/{dataMode}")
    public Map<String, Object> mode(@PathVariable long productId, @PathVariable DataMode dataMode,
                                    @Valid @RequestBody ModeRequest body) {
        var mode = commands.configureProductMode(new ConfigureProductModeCommand(
                validatedProductId(productId), dataMode, body.enabled(),
                Duration.ofMinutes(body.staleAfterMinutes())), actors.required());
        return Map.of("dataMode", mode.dataMode(), "enabled", mode.enabled(),
                "staleAfterMinutes", mode.staleAfter().toMinutes());
    }

    @PostMapping("/{productId}/publish")
    public Map<String, Object> publish(@PathVariable long productId) {
        return response(commands.publishProduct(validatedProductId(productId), actors.required()));
    }

    @PostMapping("/{productId}/disable")
    public Map<String, Object> disable(@PathVariable long productId) {
        return response(commands.disableProduct(validatedProductId(productId), actors.required()));
    }

    /** 在构造领域 ID 前转为明确的 HTTP 422；不让非法 path 参数泄露为 500。 */
    private static ProductId validatedProductId(long value) {
        if (value < 1) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "产品编号必须大于零");
        return new ProductId(value);
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
        result.put("colorbarUrl", CatalogController.publicColorbarUrl(detail.colorbarPath()));
        result.put("sortOrder", p.sortOrder());
        result.put("status", p.status()); result.put("publishedAt", detail.publishedAt());
        result.put("createdAt", detail.createdAt()); result.put("updatedAt", detail.updatedAt());
        result.put("modes", detail.modePolicies().stream().map(mode -> Map.of(
                "dataMode", mode.dataMode(), "enabled", mode.enabled(),
                "staleAfterMinutes", mode.staleAfter().toMinutes())).toList());
        return result;
    }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String code,
            @NotBlank @Size(max = 64) String family,
            @NotNull @Size(min = 2, max = 255) String nameZh, @NotNull @Size(min = 2, max = 255) String nameEn,
            @Size(max = 64) String unit, @NotNull String descriptionZh, @NotNull String descriptionEn,
            @NotBlank @Size(max = 255) String producer, @Size(max = 255) String algorithmName,
            @NotNull String sourceDescription, URI officialSourceUrl,
            @NotNull Boolean colorbarRequired, @Size(max = 512) String colorbarPath, @NotNull Integer sortOrder) {}
    public record UpdateRequest(
            @NotNull @Size(min = 2, max = 255) String nameZh, @NotNull @Size(min = 2, max = 255) String nameEn,
            @Size(max = 64) String unit, @NotNull String descriptionZh, @NotNull String descriptionEn,
            @NotBlank @Size(max = 255) String producer, @Size(max = 255) String algorithmName,
            @NotNull String sourceDescription, URI officialSourceUrl,
            @NotNull Boolean colorbarRequired, @Size(max = 512) String colorbarPath, @NotNull Integer sortOrder) {}
    public record ModeRequest(
            @NotNull Boolean enabled,
            @NotNull @Min(10) @Max(10080) Long staleAfterMinutes) {}
}

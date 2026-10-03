package cn.edu.fudan.dayu.interfaces.rest.v1.catalog;

import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.catalog.api.ProductDetail;
import cn.edu.fudan.dayu.catalog.api.ProductModePolicy;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog 的公开 HTTP 适配器，只暴露已发布产品和已启用模式。
 */
@Validated
@RestController
@RequestMapping("/api/v1/products")
public class CatalogController {
    private final CatalogQueryService catalog;

    public CatalogController(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    /** 返回按 sortOrder 排序的公开产品目录。 */
    @GetMapping
    public List<PublicProductSummaryResponse> listPublishedProducts() {
        return catalog.listPublishedProductDetails().stream()
                .map(CatalogController::toSummary)
                .toList();
    }

    /** 查询单个公开产品；不存在、草稿和停用产品对访客都表现为 404。 */
    @GetMapping("/{productCode}")
    public PublicProductDetailResponse getPublishedProduct(
            @PathVariable
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$", message = "invalid product code")
            String productCode) {
        ProductDetail detail = catalog.findProduct(new ProductCode(productCode))
                .filter(product -> product.summary().status() == ProductStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "产品不存在或尚未发布"));
        return toDetail(detail);
    }

    private static PublicProductSummaryResponse toSummary(ProductDetail detail) {
        var product = detail.summary();
        return new PublicProductSummaryResponse(
                product.id().value(), product.code().value(), product.nameZh(), product.nameEn(),
                product.family(), product.unit(), product.producer(), product.algorithmName(),
                detail.colorbarRequired(), product.sortOrder(), enabledModes(detail));
    }

    private static PublicProductDetailResponse toDetail(ProductDetail detail) {
        PublicProductSummaryResponse summary = toSummary(detail);
        return new PublicProductDetailResponse(
                summary.productId(), summary.code(), summary.nameZh(), summary.nameEn(),
                summary.family(), summary.unit(), summary.producer(), summary.algorithmName(),
                summary.colorbarRequired(), summary.sortOrder(), summary.modes(),
                detail.descriptionZh(), detail.descriptionEn(), detail.summary().sourceDescription(),
                detail.summary().officialSourceUrl(), publicColorbarUrl(detail.colorbarPath()));
    }

    private static List<PublicProductModeResponse> enabledModes(ProductDetail detail) {
        return detail.modePolicies().stream()
                .filter(ProductModePolicy::enabled)
                .sorted(Comparator.comparing(ProductModePolicy::dataMode))
                .map(policy -> new PublicProductModeResponse(
                        policy.dataMode(), policy.staleAfter().toMinutes()))
                .toList();
    }

    static URI publicColorbarUrl(String path) {
        if (path == null || path.isBlank()) return null;
        if (path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("%")
                || path.chars().anyMatch(Character::isISOControl)
                || java.util.Arrays.stream(path.split("/", -1))
                .anyMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."))) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "产品色标配置不可用");
        }
        try {
            return new URI(null, null, "/" + path, null);
        } catch (java.net.URISyntaxException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "产品色标配置不可用");
        }
    }
}

package cn.edu.fudan.dayu.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 防止 OpenAPI 已确认路径在 Controller 实现中遗漏。 */
@SpringBootTest
@ActiveProfiles("skeleton")
class HttpRouteCoverageTest {
    @Autowired
    RequestMappingHandlerMapping mappings;

    @Test
    void exposesEveryConfirmedV1Operation() {
        Set<String> actual = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> operations(info).stream())
                .collect(Collectors.toSet());
        assertThat(actual).contains(
                "GET /api/v1/products", "GET /api/v1/products/{productCode}",
                "GET /api/v1/preview-frames", "GET /api/v1/forecast-cycles",
                "GET /api/v1/scientific-assets",
                "GET /api/v1/session", "POST /api/v1/session", "DELETE /api/v1/session",
                "POST /api/v1/users", "POST /api/v1/downloads",
                "GET /api/v1/downloads/{downloadEventId}/content",
                "POST /api/v1/copilot/queries",
                "GET /api/v1/admin/products", "POST /api/v1/admin/products",
                "PUT /api/v1/admin/products/{productId}",
                "PUT /api/v1/admin/products/{productId}/modes/{dataMode}",
                "POST /api/v1/admin/products/{productId}/publish",
                "POST /api/v1/admin/products/{productId}/disable",
                "GET /api/v1/admin/dashboard", "GET /api/v1/admin/product-health",
                "POST /api/v1/admin/index-scans", "GET /api/v1/admin/index-scans",
                "GET /api/v1/admin/index-scans/{scanRunId}",
                "GET /api/v1/admin/download-audits", "GET /api/v1/admin/download-statistics",
                "GET /api/v1/admin/users", "PUT /api/v1/admin/users/{userId}/status",
                "PUT /api/v1/admin/users/{userId}/role");
    }

    @Test
    void exposesLegacyCompatibilityAndRetirementRoutes() {
        Set<String> actual = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> operations(info).stream())
                .collect(Collectors.toSet());
        assertThat(actual).contains(
                "GET /api/products.php", "GET /api/files.php", "GET /api/fcst_latest.php",
                "GET /api/search.php", "GET /api/auth.php", "POST /api/auth.php",
                "POST /api/download.php", "GET /api/admin.php", "POST /api/admin.php",
                "GET /api/admin_products.php", "POST /api/admin_products.php");
    }

    private static Set<String> operations(RequestMappingInfo info) {
        Set<String> paths = info.getPatternValues();
        Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                info.getMethodsCondition().getMethods();
        if (methods.isEmpty()) return Set.of();
        return paths.stream().flatMap(path -> methods.stream()
                        .map(method -> method.name() + " " + path))
                .collect(Collectors.toSet());
    }
}

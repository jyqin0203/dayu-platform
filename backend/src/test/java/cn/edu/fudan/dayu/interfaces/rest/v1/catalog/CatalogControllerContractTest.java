package cn.edu.fudan.dayu.interfaces.rest.v1.catalog;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.catalog.api.ProductDetail;
import cn.edu.fudan.dayu.catalog.api.ProductModePolicy;
import cn.edu.fudan.dayu.catalog.api.ProductStatus;
import cn.edu.fudan.dayu.catalog.api.ProductSummary;
import cn.edu.fudan.dayu.interfaces.rest.v1.TraceIdFilter;
import cn.edu.fudan.dayu.interfaces.rest.v1.V1ExceptionHandler;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import cn.edu.fudan.dayu.shared.kernel.ProductId;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证公开 Catalog Controller 与 OpenAPI 中的 JSON、过滤和错误契约一致。 */
@WebMvcTest(CatalogController.class)
@Import({
        TraceIdFilter.class,
        V1ExceptionHandler.class,
        CatalogControllerContractTest.PermitAllSecurity.class
})
class CatalogControllerContractTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CatalogQueryService catalog;

    @Test
    void listsPublishedProductsAndOnlyEnabledModes() throws Exception {
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(
                product(1, "BT855", ProductStatus.PUBLISHED, true, false)));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].code").value("BT855"))
                .andExpect(jsonPath("$[0].modes.length()").value(1))
                .andExpect(jsonPath("$[0].modes[0].dataMode").value("REALTIME"))
                .andExpect(jsonPath("$[0].modes[0].staleAfterMinutes").value(90))
                .andExpect(jsonPath("$[0].descriptionZh").doesNotExist());
    }

    @Test
    void returnsPublishedProductDetailWithPublicUrls() throws Exception {
        ProductDetail detail = product(2, "PRECIP", ProductStatus.PUBLISHED, true, true);
        when(catalog.findProduct(new ProductCode("PRECIP"))).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/api/v1/products/PRECIP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PRECIP"))
                .andExpect(jsonPath("$.descriptionZh").value("PRECIP说明"))
                .andExpect(jsonPath("$.officialSourceUrl").value("https://example.test/source"))
                .andExpect(jsonPath("$.colorbarUrl").value("/colorbars/PRECIP.webp"))
                .andExpect(jsonPath("$.modes.length()").value(2));
    }

    @Test
    void hidesDraftProductsAsNotFound() throws Exception {
        when(catalog.findProduct(new ProductCode("COT")))
                .thenReturn(Optional.of(product(3, "COT", ProductStatus.DRAFT, true, false)));

        mockMvc.perform(get("/api/v1/products/COT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsInvalidProductCodeBeforeCallingCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/products/bad-code"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static ProductDetail product(
            long id, String code, ProductStatus status, boolean realtimeEnabled, boolean forecastEnabled) {
        ProductCode productCode = new ProductCode(code);
        ProductSummary summary = new ProductSummary(
                new ProductId(id), productCode, code + "中文名", code + " English",
                "TEST", "K", "课题组", "测试算法", "测试来源",
                URI.create("https://example.test/source"), status, (int) id);
        return new ProductDetail(
                summary, code + "说明", code + " description", true,
                "colorbars/" + code + ".webp",
                List.of(
                        new ProductModePolicy(productCode, DataMode.REALTIME,
                                realtimeEnabled, Duration.ofMinutes(90)),
                        new ProductModePolicy(productCode, DataMode.FORECAST,
                                forecastEnabled, Duration.ofMinutes(360))),
                status == ProductStatus.DRAFT ? null : Instant.parse("2026-09-29T02:00:00Z"),
                Instant.parse("2026-09-29T01:00:00Z"),
                Instant.parse("2026-09-29T02:00:00Z"));
    }

    @TestConfiguration
    public static class PermitAllSecurity {
        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }
    }
}

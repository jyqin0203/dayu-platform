package cn.edu.fudan.dayu.interfaces.rest.v1.catalog;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.interfaces.rest.v1.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** HTTP 字段与权限转换测试；Session/CSRF 过滤链由 Identity 的集成测试负责。 */
class CatalogAdminControllerContractTest {
    CatalogQueryService query;
    CatalogAdminService commands;
    CurrentActorProvider actors;
    MockMvc mvc;
    LocalValidatorFactoryBean validator;

    @BeforeEach void setup() {
        query = mock(CatalogQueryService.class); commands = mock(CatalogAdminService.class);
        actors = mock(CurrentActorProvider.class);
        when(actors.required()).thenReturn(new ActorContext(new UserId(1), "lab", UserRole.ADMIN));
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CatalogAdminController(query, commands, actors))
                .setControllerAdvice(new V1ExceptionHandler()).setValidator(validator)
                .addFilters(new TraceIdFilter()).build();
    }
    @AfterEach void close() { validator.close(); }

    @Test void adminListUsesBatchReadAndFlatSafeResponse() throws Exception {
        Instant time = Instant.parse("2026-10-01T00:00:00Z");
        ProductDetail product = new ProductDetail(new ProductSummary(new ProductId(1), new ProductCode("CTH"),
                "云顶高度", "Cloud top height", "CPP", "m", "lab", null, "source", null, ProductStatus.DRAFT, 1),
                "", "", true, "colorbars/cth.png", List.of(), null, time, time);
        when(query.listManagedProductDetails(any())).thenReturn(List.of(product));
        mvc.perform(get("/api/v1/admin/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].colorbarUrl").value("/colorbars/cth.png"))
                .andExpect(jsonPath("$[0].colorbarPath").doesNotExist())
                .andExpect(jsonPath("$[0].summary").doesNotExist());
        verify(query, never()).findProduct(any());
    }
    @Test void rejectsMissingRequiredBooleansAndIntegers() throws Exception {
        mvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"CTH","family":"CPP","nameZh":"云顶高度","nameEn":"Cloud height",
                 "descriptionZh":"","descriptionEn":"","producer":"lab","sourceDescription":""}
                """)).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/admin/products/1/modes/REALTIME").contentType(MediaType.APPLICATION_JSON)
                .content("{\"staleAfterMinutes\":90}")).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(commands);
    }
    @Test void adminListingRejectsNormalUserEvenBeforeSecurityIntegration() throws Exception {
        when(actors.required()).thenReturn(new ActorContext(new UserId(2), "lab", UserRole.USER));
        mvc.perform(get("/api/v1/admin/products")).andExpect(status().isForbidden());
        verifyNoInteractions(query);
    }
    @Test void rejectsNonPositivePathIdsBeforeCallingBusinessCommands() throws Exception {
        mvc.perform(post("/api/v1/admin/products/-1/publish"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/admin/products/0/disable"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/admin/products/-1/modes/REALTIME").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"staleAfterMinutes\":90}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/products").param("code", "invalid"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(commands);
        verifyNoInteractions(query);
    }
}

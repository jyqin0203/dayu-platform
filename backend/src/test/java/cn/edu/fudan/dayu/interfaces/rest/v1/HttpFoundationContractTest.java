package cn.edu.fudan.dayu.interfaces.rest.v1;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 验证所有后续 /api/v1 Controller 将共同使用的错误、traceId 和分页契约。
 */
@WebMvcTest(HttpFoundationContractTest.TestController.class)
@Import({
        TraceIdFilter.class,
        V1ExceptionHandler.class,
        HttpFoundationContractTest.TestController.class,
        HttpFoundationContractTest.PermitAllSecurity.class
})
class HttpFoundationContractTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void mapsBusinessErrorsAndKeepsSafeTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/test/business")
                        .header(TraceIdFilter.HEADER_NAME, "client_trace_123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(TraceIdFilter.HEADER_NAME, "client_trace_123"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("测试资源不存在"))
                .andExpect(jsonPath("$.traceId").value("client_trace_123"))
                .andExpect(jsonPath("$.details.resource").value("test"));
    }

    @Test
    void generatesTraceIdWhenClientValueIsUnsafe() throws Exception {
        mockMvc.perform(get("/api/v1/test/business")
                        .header(TraceIdFilter.HEADER_NAME, "bad trace!"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(TraceIdFilter.HEADER_NAME,
                        matchesPattern("[a-f0-9]{32}")))
                .andExpect(jsonPath("$.traceId", matchesPattern("[a-f0-9]{32}")));
    }

    @Test
    void mapsBodyValidationTo422WithFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.name").value("name must not be blank"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void mapsMalformedJsonTo400WithoutLeakingParserDetails() throws Exception {
        mockMvc.perform(post("/api/v1/test/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求格式或参数类型不正确"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void appliesPaginationDefaultsAndRejectsOversizedPages() throws Exception {
        mockMvc.perform(get("/api/v1/test/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20));

        mockMvc.perform(get("/api/v1/test/page").param("pageSize", "101"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void mapsDirectQueryParameterValidationTo422() throws Exception {
        mockMvc.perform(get("/api/v1/test/count").param("count", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.violations[0]").value("count must be at least 1"));
    }

    @Test
    void rejectsNonnumericPagingWithoutEchoingItsValue() throws Exception {
        mockMvc.perform(get("/api/v1/test/page").param("page", "private-input"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void returnValueValidationIsServerError() throws Exception {
        mockMvc.perform(get("/api/v1/test/invalid-result"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @RestController
    public static class TestController {
        @GetMapping("/api/v1/test/business")
        Map<String, String> businessError() {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "测试资源不存在", Map.of("resource", "test"));
        }

        @PostMapping("/api/v1/test/body")
        Map<String, String> validateBody(@Valid @RequestBody TestBody body) {
            return Map.of("name", body.name());
        }

        @GetMapping("/api/v1/test/page")
        Map<String, Integer> page(@Valid @ModelAttribute PageParameters parameters) {
            var pageRequest = parameters.toPageRequest();
            return Map.of("page", pageRequest.page(), "pageSize", pageRequest.size());
        }

        @GetMapping("/api/v1/test/count")
        Map<String, Integer> count(
                @RequestParam @Min(value = 1, message = "count must be at least 1") int count) {
            return Map.of("count", count);
        }

        @GetMapping("/api/v1/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("sensitive internal detail");
        }

        @GetMapping("/api/v1/test/invalid-result")
        @Min(1)
        int invalidResult() {
            return 0;
        }
    }

    public record TestBody(@NotBlank(message = "name must not be blank") String name) {}

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

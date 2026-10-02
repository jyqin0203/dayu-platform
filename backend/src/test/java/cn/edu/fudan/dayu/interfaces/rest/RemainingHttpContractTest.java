package cn.edu.fudan.dayu.interfaces.rest;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 使用 skeleton Mock 串联剩余公开、管理、下载、Copilot 和 Legacy HTTP 路径。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("skeleton")
class RemainingHttpContractTest {
    @Autowired MockMvc mockMvc;

    @Test
    void publicDiscoveryAndLegacyQueriesReturnContractShapes() throws Exception {
        mockMvc.perform(get("/api/v1/preview-frames")
                        .param("productCode", "BT855").param("dataMode", "REALTIME")
                        .param("from", "2026-09-27T00:00:00Z")
                        .param("to", "2026-09-29T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].webpAssetId").value(81002))
                .andExpect(jsonPath("$.items[0].cycleTime").isEmpty());

        mockMvc.perform(get("/api/v1/scientific-assets")
                        .param("productCode", "PRECIP").param("dataMode", "FORECAST")
                        .param("from", "2026-09-02T06:00:00Z")
                        .param("to", "2026-09-02T09:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assetId").value(92002))
                .andExpect(jsonPath("$.pageSize").value(20));

        mockMvc.perform(get("/api/products.php"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.products").isArray());
        mockMvc.perform(get("/api/admin.php"))
                .andExpect(status().isGone());
    }

    @Test
    void identityAdminCopilotAndDownloadFlowCanBeCalledThroughHttp() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/session").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.test\",\"password\":\"admin-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));

        mockMvc.perform(get("/api/v1/admin/dashboard").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedProducts").value(3));

        mockMvc.perform(post("/api/v1/copilot/queries").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"查询降水预报","displayZone":"Asia/Shanghai",
                                 "recentMessages":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedActions").isArray());

        String authorization = mockMvc.perform(post("/api/v1/downloads").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assetId":92002,"purpose":"用于HTTP下载契约集成测试"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.downloadEventId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long eventId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(authorization).get("downloadEventId").asLong();

        mockMvc.perform(get("/api/v1/downloads/{id}/content", eventId).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect",
                        org.hamcrest.Matchers.startsWith("/internal-netcdf/")));
    }
}

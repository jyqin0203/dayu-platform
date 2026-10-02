package cn.edu.fudan.dayu.integration;

import cn.edu.fudan.dayu.DayuApplication;
import cn.edu.fudan.dayu.identity.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 主 Agent 的整体验收：全部真实业务服务 + 临时 MariaDB + 合成文件 + HTTP Session。
 * 不导入任何 Mock 业务 bean，不调用模型服务，不接生产目录。
 */
@Testcontainers
@SpringBootTest(classes = DayuApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("platform-integration-test")
class PlatformWorkflowIntegrationTest {
    @Container static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>("mariadb:10.6")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withStartupTimeout(Duration.ofMinutes(5));
    static final Path ROOT = temporaryRoot();
    static final Path NC = ROOT.resolve("netcdf");
    static final Path WEBP = ROOT.resolve("webp");
    static final String PASSWORD = "workflow-test-password";
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DATABASE::getJdbcUrl);
        r.add("spring.datasource.username", DATABASE::getUsername);
        r.add("spring.datasource.password", DATABASE::getPassword);
        r.add("dayu.indexing.roots.netcdf-data", NC::toString);
        r.add("dayu.indexing.roots.webp-preview", WEBP::toString);
        r.add("dayu.indexing.enabled", () -> "false");
        r.add("dayu.copilot.enabled", () -> "false");
        r.add("dayu.cache.enabled", () -> "false");
        r.add("dayu.download.transfer-mode", () -> "LOCAL");
    }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identity;
    @Autowired ObjectMapper json;

    @Test void realProductsScanSearchAndSessionBoundDownload() throws Exception {
        // Bootstrap only this test database; public registration cannot create an administrator.
        var admin = identity.register(new RegisterCommand("workflow-admin@example.test", PASSWORD, "测试单位"));
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", admin.id().value());
        Browser administrator = login("workflow-admin@example.test");
        Browser visitor = anonymous();
        http.perform(get("/api/v1/admin/dashboard").session(visitor.session))
                .andExpect(status().isUnauthorized());

        createProduct(administrator, "PRECIP");
        createProduct(administrator, "PLP");
        Instant valid = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(120);
        String compact = DateTimeFormatter.ofPattern("uuuuMMddHHmm").withZone(ZoneOffset.UTC).format(valid);
        byte[] bytes = "synthetic scientific content for integration".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.createDirectories(NC.resolve("realtime"));
        Files.createDirectories(WEBP.resolve("realtime/PRECIP"));
        Files.write(NC.resolve("realtime/FY4B_AGRI_REPPIC_PRECIP_" + compact + ".nc"), bytes);
        Files.writeString(WEBP.resolve("realtime/PRECIP/FY4B_AGRI_PRECIP_" + compact + "_Dpi500.webp"), "synthetic-preview");
        JsonNode task = body(http.perform(post("/api/v1/admin/index-scans").session(administrator.session)
                .header("X-CSRF-TOKEN", administrator.token)).andExpect(status().isAccepted()).andReturn());
        long taskId = task.path("scanRunId").asLong();
        assertThat(taskId).isPositive();
        JsonNode finished = task;
        for (int i = 0; i < 200 && finished.path("status").asText().equals("RUNNING"); i++) {
            Thread.sleep(50);
            finished = body(http.perform(get("/api/v1/admin/index-scans/" + taskId)
                    .session(administrator.session)).andExpect(status().isOk()).andReturn());
        }
        assertThat(finished.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.path("createdAssets").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_asset_products", Long.class)).isEqualTo(3);

        String from = valid.minusSeconds(60).toString(), to = valid.plusSeconds(60).toString();
        JsonNode results = body(http.perform(get("/api/v1/scientific-assets")
                        .param("productCode", "PRECIP").param("dataMode", "REALTIME")
                        .param("from", from).param("to", to))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn());
        long assetId = results.path("items").get(0).path("assetId").asLong();
        assertThat(results.toString()).doesNotContain("relativePath", "storageKey", ROOT.toString());
        http.perform(get("/api/v1/preview-frames")
                        .param("productCode", "PRECIP").param("dataMode", "REALTIME")
                        .param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].downloadAvailable").value(true));

        Browser user = register(visitor, "workflow-user@example.test");
        http.perform(get("/api/v1/admin/dashboard").session(user.session)).andExpect(status().isForbidden());
        var request = Map.of("assetId", assetId, "purpose", "用于真实后端HTTP全链路验收测试");
        JsonNode grant = body(http.perform(post("/api/v1/downloads").session(user.session)
                        .header("X-CSRF-TOKEN", user.token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(grant.toString()).doesNotContain("internalLocation", "relativePath");
        String url = grant.path("downloadUrl").asText();
        http.perform(get(url).session(user.session)).andExpect(status().isOk())
                .andExpect(content().bytes(bytes)).andExpect(header().doesNotExist("X-Accel-Redirect"));
        Browser other = register(anonymous(), "workflow-other@example.test");
        http.perform(get(url).session(other.session)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_events WHERE status='AUTHORIZED'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM download_event_products", Long.class)).isEqualTo(2);
        http.perform(get("/api/v1/admin/download-statistics").session(administrator.session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedRequests").value(1))
                .andExpect(jsonPath("$.uniqueAssets").value(1))
                .andExpect(jsonPath("$.uniqueUsers").value(1));
        http.perform(get("/api/v1/admin/dashboard").session(administrator.session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestScan.scanRunId").value(taskId))
                .andExpect(jsonPath("$.downloads.authorizedRequests").value(1));
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email='workflow-user@example.test'", Long.class);
        http.perform(put("/api/v1/admin/users/" + userId + "/status").session(administrator.session)
                        .header("X-CSRF-TOKEN", administrator.token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}")).andExpect(status().isOk());
        http.perform(get(url).session(user.session)).andExpect(status().isUnauthorized());
    }

    private void createProduct(Browser admin, String code) throws Exception {
        Map<String,Object> request = Map.of("code",code,"family","REPPIC_PRECIP","nameZh",code+"降水",
                "nameEn",code+" precipitation","descriptionZh","合成测试说明","descriptionEn","synthetic test",
                "producer","测试单位","sourceDescription","synthetic","colorbarRequired",false,"sortOrder",1);
        JsonNode created = body(http.perform(post("/api/v1/admin/products").session(admin.session)
                        .header("X-CSRF-TOKEN",admin.token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request))).andExpect(status().isCreated()).andReturn());
        long id = created.path("productId").asLong();
        http.perform(put("/api/v1/admin/products/"+id+"/modes/REALTIME").session(admin.session)
                        .header("X-CSRF-TOKEN",admin.token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"staleAfterMinutes\":90}")).andExpect(status().isOk());
        http.perform(post("/api/v1/admin/products/"+id+"/publish").session(admin.session)
                        .header("X-CSRF-TOKEN",admin.token)).andExpect(status().isOk());
    }
    private Browser anonymous() throws Exception {
        MvcResult result = http.perform(get("/api/v1/session")).andExpect(status().isOk()).andReturn();
        return new Browser((MockHttpSession)result.getRequest().getSession(false), body(result).path("csrfToken").asText());
    }
    private Browser login(String email) throws Exception {
        Browser browser = anonymous();
        MvcResult result = http.perform(post("/api/v1/session").session(browser.session)
                        .header("X-CSRF-TOKEN",browser.token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("email",email,"password",PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return new Browser(browser.session,body(result).path("csrfToken").asText());
    }
    private Browser register(Browser browser,String email) throws Exception {
        MvcResult result = http.perform(post("/api/v1/users").session(browser.session)
                        .header("X-CSRF-TOKEN",browser.token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("email",email,"password",PASSWORD,"organization","测试单位"))))
                .andExpect(status().isCreated()).andReturn();
        return new Browser(browser.session,body(result).path("csrfToken").asText());
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("dayu-workflow-"); }
        catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @AfterAll static void removeSyntheticFiles() throws Exception {
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    record Browser(MockHttpSession session,String token) {}
}

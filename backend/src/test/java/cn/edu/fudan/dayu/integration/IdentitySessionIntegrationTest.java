package cn.edu.fudan.dayu.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.edu.fudan.dayu.catalog.api.CatalogQueryService;
import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.identity.application.IdentityApplicationService;
import cn.edu.fudan.dayu.identity.infrastructure.IdentityPasswordConfiguration;
import cn.edu.fudan.dayu.identity.infrastructure.JdbcUserRepository;
import cn.edu.fudan.dayu.interfaces.rest.legacy.LegacyController;
import cn.edu.fudan.dayu.interfaces.rest.SessionAuthentication;
import cn.edu.fudan.dayu.interfaces.rest.SkeletonSecurityConfiguration;
import cn.edu.fudan.dayu.interfaces.rest.v1.*;
import cn.edu.fudan.dayu.interfaces.rest.v1.identity.IdentityController;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real MariaDB accounts plus the actual Session security filter chain, without other module mocks. */
@Testcontainers
@SpringBootTest(classes = IdentitySessionIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("identity-integration-test")
class IdentitySessionIntegrationTest {
    @Container static final MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.6")
            .withStartupTimeout(java.time.Duration.ofMinutes(5))
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(384L * 1024 * 1024));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("dayu.identity.email-failure-limit", () -> "3");
        registry.add("dayu.identity.ip-failure-limit", () -> "4");
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
    @org.springframework.context.annotation.Profile("identity-integration-test")
    @Import({IdentityApplicationService.class, JdbcUserRepository.class, IdentityPasswordConfiguration.class,
            SkeletonSecurityConfiguration.class, SessionAuthentication.class, IdentityController.class,
            LegacyController.class, CurrentActorProvider.class, V1ExceptionHandler.class, TraceIdFilter.class,
            ProbeController.class})
    static class TestApp {
        @Bean CatalogQueryService catalog() { return org.mockito.Mockito.mock(CatalogQueryService.class); }
    }
    @RestController @org.springframework.context.annotation.Profile("identity-integration-test")
    static class ProbeController {
        private final CurrentActorProvider actors;
        ProbeController(CurrentActorProvider actors) { this.actors = actors; }
        @GetMapping("/api/v1/admin/probe") Map<String, Object> admin() { return Map.of("id", actors.required().userId().value()); }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired IdentityService identity;
    @Autowired UserAdminService administration;
    @Autowired PasswordEncoder passwords;
    private static final String PASSWORD = "integration-password";

    @BeforeEach void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_registration");
        jdbc.update("DELETE FROM login_attempts");
        jdbc.update("DELETE FROM users");
    }
    @Test void sessionsAreIsolatedAndTokensRotateAtAuthenticationAndLogout() throws Exception {
        Browser first = anonymous();
        Browser second = anonymous();
        String oldId = first.session().getId();
        Browser loggedIn = register(first, "first@example.test");
        assertThat(loggedIn.token()).isNotEqualTo(first.token());
        assertThat(loggedIn.session().getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v1/session").session(second.session()))
                .andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/v1/session").session(loggedIn.session()))
                .andExpect(jsonPath("$.user.email").value("first@example.test"));
        mvc.perform(delete("/api/v1/session").session(loggedIn.session()).header("X-CSRF-TOKEN", first.token()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/session").session(loggedIn.session()).header("X-CSRF-TOKEN", loggedIn.token()))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("DAYUSESSID=;")));
        assertThat(loggedIn.session().isInvalid()).isTrue();
        mvc.perform(get("/api/v1/session")).andExpect(jsonPath("$.authenticated").value(false));
    }
    @Test void csrfIsRequiredForLoginAndRegistrationAndUserCannotAccessAdmin() throws Exception {
        Browser browser = anonymous();
        mvc.perform(post("/api/v1/session").session(browser.session()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "test@example.test", "password", PASSWORD))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        Browser user = register(browser, "user@example.test");
        mvc.perform(get("/api/v1/admin/probe").session(user.session()).header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/probe").header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/downloads/1/content"))
                .andExpect(status().isUnauthorized());
    }
    @Test void registrationCommitsAndHashesLongUnicodePasswordWithoutExposingIt() throws Exception {
        Browser browser = anonymous();
        String password = "密".repeat(100);
        var result = mvc.perform(post("/api/v1/users").session(browser.session()).header("X-CSRF-TOKEN", browser.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "email", "long@example.test", "password", password, "organization", "测试单位"))))
                .andExpect(status().isCreated()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(password).doesNotContain("password");
        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE email='long@example.test'", String.class);
        assertThat(stored).startsWith("{pbkdf2@SpringSecurity_v5_8}").doesNotContain(password);
        assertThat(passwords.matches(password, stored)).isTrue();
        assertThat(passwords.matches("密".repeat(99) + "错", stored)).isFalse();
        assertThat(new RegisterCommand("x", password, "y").toString()).doesNotContain(password);
        assertThat(new LegacyController.LegacyAuthRequest("x", password, "y", "secret-token").toString())
                .doesNotContain(password).doesNotContain("secret-token");
        assertThat(new IdentityController.SessionResponse(true, null, "secret-token").toString()).doesNotContain("secret-token");
    }
    @Test void databaseInsertFailureCannotCreateAuthenticatedSession() throws Exception {
        jdbc.execute("CREATE TRIGGER reject_registration AFTER INSERT ON users FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'controlled test failure'");
        Browser browser = anonymous();
        mvc.perform(post("/api/v1/users").session(browser.session()).header("X-CSRF-TOKEN", browser.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "email", "rollback@example.test", "password", PASSWORD, "organization", "测试单位"))))
                .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
        mvc.perform(get("/api/v1/session").session(browser.session()))
                .andExpect(jsonPath("$.authenticated").value(false));
    }
    @Test void loginFailuresPersistAndLimitExistingAndUnknownAccounts() throws Exception {
        Browser browser = anonymous();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/session").session(browser.session()).header("X-CSRF-TOKEN", browser.token())
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                    "email", "unknown@example.test", "password", PASSWORD))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/session").session(browser.session()).header("X-CSRF-TOKEN", browser.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "email", "unknown@example.test", "password", PASSWORD))))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_attempts WHERE successful=FALSE", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT MAX(ABS(TIMESTAMPDIFF(SECOND,attempted_at,UTC_TIMESTAMP()))) FROM login_attempts", Long.class))
                .isLessThan(120);
    }
    @Test void roleChangesAndDisableApplyToExistingSessionsAndLastAdminIsProtected() throws Exception {
        var adminUser = identity.register(new RegisterCommand("admin@example.test", PASSWORD, "测试单位"));
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", adminUser.id().value());
        Browser admin = login(anonymous(), "admin@example.test");
        mvc.perform(get("/api/v1/admin/probe").session(admin.session())).andExpect(status().isOk());
        ActorContext actor = new ActorContext(adminUser.id(), "测试单位", UserRole.ADMIN);
        assertThatThrownBy(() -> administration.changeUserRole(new ChangeUserRoleCommand(adminUser.id(), UserRole.USER), actor))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> administration.changeUserStatus(new ChangeUserStatusCommand(adminUser.id(), UserStatus.DISABLED), actor))
                .isInstanceOf(BusinessException.class);
        var second = identity.register(new RegisterCommand("second@example.test", PASSWORD, "测试单位"));
        administration.changeUserRole(new ChangeUserRoleCommand(second.id(), UserRole.ADMIN), actor);
        administration.changeUserRole(new ChangeUserRoleCommand(adminUser.id(), UserRole.USER), actor);
        mvc.perform(get("/api/v1/admin/probe").session(admin.session())).andExpect(status().isForbidden());
        var secondActor = new ActorContext(second.id(), "测试单位", UserRole.ADMIN);
        administration.changeUserStatus(new ChangeUserStatusCommand(adminUser.id(), UserStatus.DISABLED), secondActor);
        mvc.perform(get("/api/v1/session").session(admin.session())).andExpect(jsonPath("$.authenticated").value(false));
    }
    @Test void legacySupportsFormAndJsonTokensAndLogoutCreatesFreshAnonymousSession() throws Exception {
        Browser browser = anonymous();
        var registered = mvc.perform(post("/api/auth.php").session(browser.session())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("action", "register")
                        .param("email", "legacy@example.test").param("password", PASSWORD)
                        .param("organization", "测试单位").param("csrf_token", browser.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("user")).andReturn();
        String token = json.readTree(registered.getResponse().getContentAsString()).path("csrf_token").asText();
        mvc.perform(post("/api/auth.php").session(browser.session()).param("action", "unexpected")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("csrf_token", token))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.ok").value(false));
        var logout = mvc.perform(post("/api/auth.php").session(browser.session()).param("action", "logout")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("csrf_token", token))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.csrf_token").isNotEmpty()).andReturn();
        assertThat(json.readTree(logout.getResponse().getContentAsString()).path("csrf_token").asText()).isNotEqualTo(token);
        assertThat(browser.session().isInvalid()).isTrue();
    }
    @Test void userSearchPaginatesAndNormalizesDuplicateEmails() throws Exception {
        var admin = identity.register(new RegisterCommand("admin@example.test", PASSWORD, "测试单位"));
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", admin.id().value());
        identity.register(new RegisterCommand("one@example.test", PASSWORD, "另一单位"));
        identity.register(new RegisterCommand("two@example.test", PASSWORD, "另一单位"));
        var page = administration.searchUsers(new UserQuery(null, "另一单位", UserRole.USER, UserStatus.ACTIVE,
                new PageRequest(2, 1)), new ActorContext(admin.id(), "测试单位", UserRole.ADMIN));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).email()).isEqualTo("two@example.test");
        assertThatThrownBy(() -> identity.register(new RegisterCommand(" ONE@EXAMPLE.TEST ", PASSWORD, "测试单位")))
                .isInstanceOf(BusinessException.class).satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void ipLimitCannotBeBypassedByChangingEmail() {
        for (int i = 0; i < 4; i++) {
            String email = "unknown" + i + "@example.test";
            assertThatThrownBy(() -> identity.login(new LoginCommand(email, PASSWORD), new ClientIdentity("192.0.2.1", "test")))
                    .isInstanceOf(BusinessException.class).satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                            .isEqualTo(ErrorCode.UNAUTHENTICATED));
        }
        assertThatThrownBy(() -> identity.login(new LoginCommand("different@example.test", PASSWORD), new ClientIdentity("192.0.2.1", "test")))
                .isInstanceOf(BusinessException.class).satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.RATE_LIMITED));
    }
    @Test void competingAdminDemotionsCannotRemoveLastAdministrator() throws Exception {
        var first = identity.register(new RegisterCommand("first-admin@example.test", PASSWORD, "测试单位"));
        var second = identity.register(new RegisterCommand("second-admin@example.test", PASSWORD, "测试单位"));
        jdbc.update("UPDATE users SET role='ADMIN'");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (var user : java.util.List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        administration.changeUserRole(new ChangeUserRoleCommand(user.id(), UserRole.USER),
                                new ActorContext(user.id(), "测试单位", UserRole.ADMIN));
                        return true;
                    } catch (BusinessException | org.springframework.dao.ConcurrencyFailureException expected) { return false; }
                }));
            }
            start.countDown();
            long succeeded = 0;
            for (var result : results) if (result.get(60, java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
            assertThat(succeeded).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'", Long.class))
                    .isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    private Browser anonymous() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/session")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return new Browser((MockHttpSession) result.getRequest().getSession(false),
                json.readTree(result.getResponse().getContentAsString()).path("csrfToken").asText());
    }
    private Browser register(Browser browser, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/users").session(browser.session()).header("X-CSRF-TOKEN", browser.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "organization", "测试单位", "role", "ADMIN"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.role").value("USER")).andReturn();
        return new Browser(browser.session(), json.readTree(result.getResponse().getContentAsString()).path("csrfToken").asText());
    }
    private Browser login(Browser browser, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/session").session(browser.session()).header("X-CSRF-TOKEN", browser.token())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return new Browser(browser.session(), json.readTree(result.getResponse().getContentAsString()).path("csrfToken").asText());
    }
    record Browser(MockHttpSession session, String token) {}
}

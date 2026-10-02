package cn.edu.fudan.dayu.catalog.application;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.catalog.infrastructure.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;

/** 必须使用真实临时 MariaDB；Docker 不可用即失败，不以跳过冒充持久化验证。 */
@Testcontainers
class CatalogPersistenceIntegrationTest {
    @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:10.6")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withStartupTimeoutSeconds(300);
    private static final ActorContext ADMIN = new ActorContext(new UserId(1), "test lab", UserRole.ADMIN);
    @TempDir Path root;
    AnnotationConfigApplicationContext context;
    CatalogService service;
    JdbcTemplate jdbc;

    @BeforeAll static void schema() {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()).load().migrate();
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(CatalogService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.update("DELETE FROM product_admin_events");
        jdbc.update("DELETE FROM product_mode_policies");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,email,password_hash,organization,role,status) VALUES(1,'admin@example.test','test-only','lab','ADMIN','ACTIVE')");
        context.getBean(CatalogStorageProperties.class).setColorbarRoot(root);
    }
    @AfterEach void close() { context.close(); }

    @Test void persistsAcrossServiceContextsAndAuditsLifecycle() {
        ProductDetail draft = create("CTH");
        mode(draft, DataMode.REALTIME, true);
        ProductDetail published = service.publishProduct(draft.summary().id(), ADMIN);
        service.disableProduct(draft.summary().id(), ADMIN);
        ProductDetail republished = service.publishProduct(draft.summary().id(), ADMIN);
        assertThat(republished.publishedAt()).isEqualTo(published.publishedAt());
        assertThat(jdbc.queryForList("SELECT action FROM product_admin_events ORDER BY id", String.class))
                .containsExactly("CREATE", "UPDATE", "PUBLISH", "DISABLE", "REPUBLISH");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(before_json,'$.summary.status')) FROM product_admin_events WHERE action='REPUBLISH'", String.class))
                .isEqualTo("DISABLED");
        try (var restarted = new AnnotationConfigApplicationContext(Config.class)) {
            ProductDetail stored = restarted.getBean(CatalogService.class).findProduct(new ProductCode("CTH")).orElseThrow();
            assertThat(stored).isEqualTo(republished);
            assertThat(stored.modePolicies()).hasSize(1);
        }
    }

    @Test void rejectsDuplicateCodesUnauthorizedAndInvalidTransitionsWithoutExtraEvents() {
        ProductDetail p = create("CTH");
        assertCode(() -> create("CTH"), ErrorCode.CONFLICT);
        assertCode(() -> service.disableProduct(p.summary().id(), ADMIN), ErrorCode.CONFLICT);
        assertCode(() -> service.publishProduct(p.summary().id(), ADMIN), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.createProduct(command("COT", false, null),
                new ActorContext(new UserId(1), "lab", UserRole.USER)), ErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_admin_events", Integer.class)).isEqualTo(1);
    }

    @Test void rejectsInvalidCodesAndMissingCommandsAtServiceBoundary() {
        assertCode(() -> create("lowercase"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> create("A".repeat(65)), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.createProduct(null, ADMIN), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.updateProduct(null, ADMIN), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.configureProductMode(null, ADMIN), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.publishProduct(null, ADMIN), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.disableProduct(null, ADMIN), ErrorCode.VALIDATION_FAILED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_admin_events", Integer.class)).isZero();
    }

    @Test void auditFailureRollsBackProductAndModeTogether() {
        ProductDetail p = create("CTH");
        jdbc.execute("CREATE TRIGGER reject_catalog_audit BEFORE INSERT ON product_admin_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced audit rejection'");
        try {
            assertThatThrownBy(() -> mode(p, DataMode.REALTIME, true)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> create("COT")).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("DROP TRIGGER reject_catalog_audit"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_mode_policies", Integer.class)).isZero();
        assertThat(service.findProduct(new ProductCode("COT"))).isEmpty();
        assertThat(service.findProduct(new ProductCode("CTH"))).contains(p);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_admin_events", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentModeChangesCannotDisableLastPublishedMode() throws Exception {
        ProductDetail p = create("CTH");
        mode(p, DataMode.REALTIME, true); mode(p, DataMode.FORECAST, true);
        service.publishProduct(p.summary().id(), ADMIN);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> disableAfter(start, p, DataMode.REALTIME));
            Future<Boolean> second = executor.submit(() -> disableAfter(start, p, DataMode.FORECAST));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(service.findProduct(new ProductCode("CTH")).orElseThrow().modePolicies()
                .stream().filter(ProductModePolicy::enabled)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_admin_events", Integer.class)).isEqualTo(5);
    }

    @Test void concurrentPublishingProducesOnlyOneStateAudit() throws Exception {
        ProductDetail p = create("CTH"); mode(p, DataMode.REALTIME, true);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> publish = () -> {
            start.await();
            try { service.publishProduct(p.summary().id(), ADMIN); return true; }
            catch (BusinessException e) { assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT); return false; }
        };
        try {
            Future<Boolean> a = executor.submit(publish); Future<Boolean> b = executor.submit(publish);
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_admin_events WHERE action='PUBLISH'", Integer.class)).isEqualTo(1);
    }

    @Test void checksRequiredColorbarBeforePublicationAndPublishedEdits() throws Exception {
        ProductDetail p = service.createProduct(command("CTH", true, "colorbars/cth.png"), ADMIN);
        mode(p, DataMode.REALTIME, true);
        assertCode(() -> service.publishProduct(p.summary().id(), ADMIN), ErrorCode.VALIDATION_FAILED);
        Files.createDirectories(root.resolve("colorbars"));
        Files.writeString(root.resolve("colorbars/cth.png"), "synthetic colorbar");
        service.publishProduct(p.summary().id(), ADMIN);
        assertCode(() -> service.updateProduct(new UpdateProductCommand(p.summary().id(), "新名称", "new name",
                null, "", "", "lab", null, "", null, true, "missing.png", 4), ADMIN), ErrorCode.VALIDATION_FAILED);
        assertThat(service.findProduct(p.summary().code()).orElseThrow().summary().nameZh()).isEqualTo("测试产品");
        assertCode(() -> service.createProduct(command("COT", true, "../outside.png"), ADMIN), ErrorCode.VALIDATION_FAILED);
    }

    @Test void filtersAndStablySortsBatchResultsWithoutLosingDisabledModes() {
        ProductDetail p = create("CTH"); ProductDetail other = create("COT");
        mode(p, DataMode.REALTIME, true); mode(p, DataMode.FORECAST, false);
        service.publishProduct(p.summary().id(), ADMIN);
        assertThat(service.listPublishedProductDetails()).hasSize(1);
        assertThat(service.listPublishedProductDetails().get(0).modePolicies()).hasSize(2);
        assertThat(service.listManagedProductDetails(null).stream().map(d -> d.summary().id()))
                .containsExactly(p.summary().id(), other.summary().id());
        assertThat(service.listManagedProducts(new ManagedProductQuery("CPP", ProductStatus.DRAFT, null)))
                .extracting(ProductSummary::code).containsExactly(new ProductCode("COT"));
        assertThat(service.resolveProductsForAssetFamily("CPP").products())
                .containsExactlyInAnyOrder(new ProductCode("CTH"), new ProductCode("COT"));
    }

    @Test void updatesMutableFieldsButPreservesIdentityAndPublicationTime() {
        ProductDetail p = create("CTH"); mode(p, DataMode.REALTIME, true);
        ProductDetail published = service.publishProduct(p.summary().id(), ADMIN);
        ProductDetail updated = service.updateProduct(new UpdateProductCommand(p.summary().id(), "更新名称", "Updated name",
                "m", "new description", "description", "new producer", "algorithm", "source", null, false, null, 99), ADMIN);
        assertThat(updated.summary().code()).isEqualTo(p.summary().code());
        assertThat(updated.summary().family()).isEqualTo("CPP");
        assertThat(updated.summary().status()).isEqualTo(ProductStatus.PUBLISHED);
        assertThat(updated.publishedAt()).isEqualTo(published.publishedAt());
        assertThat(updated.modePolicies()).isEqualTo(published.modePolicies());
        assertThat(service.findProduct(p.summary().code())).contains(updated);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json,'$.summary.nameZh')) FROM product_admin_events ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("更新名称");
    }

    private boolean disableAfter(CountDownLatch start, ProductDetail p, DataMode mode) throws InterruptedException {
        start.await();
        try { mode(p, mode, false); return true; }
        catch (BusinessException e) { assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT); return false; }
    }
    private ProductDetail create(String code) { return service.createProduct(command(code, false, null), ADMIN); }
    private static CreateProductCommand command(String code, boolean required, String path) {
        return new CreateProductCommand(new ProductCode(code), "测试产品", "Test Product", "CPP", null,
                "", "", "lab", null, "", null, required, path, 1);
    }
    private void mode(ProductDetail p, DataMode mode, boolean enabled) {
        service.configureProductMode(new ConfigureProductModeCommand(p.summary().id(), mode, enabled, Duration.ofMinutes(90)), ADMIN);
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @TestConfiguration @EnableTransactionManagement(proxyTargetClass = true)
    @Import({CatalogService.class, JdbcCatalogRepository.class, FileColorbarVerifier.class, CatalogStorageProperties.class})
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); }
        @Bean NamedParameterJdbcTemplate jdbc(DataSource ds) { return new NamedParameterJdbcTemplate(ds); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }
}

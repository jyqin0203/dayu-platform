package cn.edu.fudan.dayu.download.application;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.download.infrastructure.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;

/** 真实 MariaDB + 临时小文件；AssetIndex 只使用受控 API 测试替身，不冒充扫描已实现。 */
@Testcontainers
class DownloadPersistenceIntegrationTest {
    @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:10.6")
            .withTmpFs(Map.of("/var/lib/mysql", "rw")).withStartupTimeoutSeconds(300);
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final String PURPOSE = "用于研究并验证区域降水变化";
    private static final ActorContext USER = new ActorContext(new UserId(1), "lab one", UserRole.USER);
    private static final ActorContext OTHER = new ActorContext(new UserId(2), "lab two", UserRole.USER);
    private static final byte[] CONTENT = "synthetic nc bytes for transfer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final FixtureAssets ASSETS = new FixtureAssets();
    private static final MutableClock CLOCK = new MutableClock();
    private static Path storageRoot;
    @TempDir Path temporary;
    AnnotationConfigApplicationContext context;
    DownloadService service;
    JdbcTemplate jdbc;
    Path file;

    @BeforeAll static void schema() {
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()).load().migrate();
    }
    @BeforeEach void setup() throws Exception {
        storageRoot = Files.createDirectory(temporary.resolve("root"));
        file = storageRoot.resolve("sample.nc"); Files.write(file, CONTENT);
        Files.setLastModifiedTime(file, FileTime.from(NOW.minusSeconds(60)));
        CLOCK.value.set(NOW); ASSETS.values.clear();
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(DownloadService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.update("DELETE FROM download_event_products"); jdbc.update("DELETE FROM download_events");
        jdbc.update("DELETE FROM data_assets"); jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,email,password_hash,organization,role,status) VALUES (1,'one@example.test','fixture','lab one','USER','ACTIVE'),(2,'two@example.test','fixture','lab two','USER','ACTIVE')");
        jdbc.update("""
                INSERT INTO data_assets(id,storage_key,relative_path,file_name,asset_type,data_mode,valid_time,
                file_size,file_modified_at,indexed_at,last_seen_at,status)
                VALUES(1,'netcdf-data','sample.nc','sample.nc','NETCDF','REALTIME','2026-10-02 10:00:00',
                ?,'2026-10-02 09:59:00','2026-10-02 10:00:00','2026-10-02 10:00:00','AVAILABLE')
                """, CONTENT.length);
        ASSETS.values.put(new AssetId(1), asset("netcdf-data", "sample.nc", "sample.nc", CONTENT.length, AssetStatus.AVAILABLE));
    }
    @AfterEach void close() { context.close(); }

    @Test void persistsAuthorizationAndProductSnapshotsAcrossApplicationRestart() {
        DownloadGrant grant = authorize(USER);
        assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        try (var restarted = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(restarted.getBean(DownloadService.class).getAuthorizedGrant(grant.eventId(), USER)).isEqualTo(grant);
        }
        DownloadAuditSummary event = service.searchDownloadAudits(query(null, null, null, null, null, null, 1)).items().get(0);
        assertThat(event.organizationSnapshot()).isEqualTo("lab one");
        assertThat(event.productSnapshot()).containsExactlyInAnyOrder(new ProductCode("PLP"), new ProductCode("PRECIP"));
        assertThat(event.status()).isEqualTo(DownloadStatus.AUTHORIZED);
        assertThat(event.expectedBytes()).isEqualTo(CONTENT.length);
    }
    @Test void refusesOtherUserAndExpiryExactlyAtTtlBoundary() {
        context.getBean(DownloadSettings.class).setGrantTtl(Duration.ofSeconds(60));
        DownloadGrant grant = authorize(USER);
        assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        code(() -> service.getAuthorizedGrant(grant.eventId(), OTHER), ErrorCode.FORBIDDEN);
        CLOCK.value.set(NOW.plusSeconds(60));
        code(() -> service.getAuthorizedGrant(grant.eventId(), USER), ErrorCode.ASSET_GONE);
        code(() -> service.getAuthorizedGrant(new DownloadEventId(999999), USER), ErrorCode.NOT_FOUND);
    }
    @Test void validatesIdentityAndPurposeBeforeAnyAudit() {
        code(() -> authorize(null), ErrorCode.UNAUTHENTICATED);
        code(() -> service.authorizeDownload(new DownloadCommand(new AssetId(1), "short"), USER, null), ErrorCode.VALIDATION_FAILED);
        code(() -> service.authorizeDownload(new DownloadCommand(new AssetId(999), PURPOSE), USER, null), ErrorCode.NOT_FOUND);
        assertThat(countEvents()).isZero();
    }
    @Test void missingFileCreatesCommittedDeniedEventWithoutAuthorizedTimestamp() throws Exception {
        Files.delete(file);
        code(() -> authorize(USER), ErrorCode.ASSET_GONE);
        var page = service.searchDownloadAudits(query(null, null, null, null, null, DownloadStatus.DENIED, 1));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).authorizedAt()).isNull();
        code(() -> service.getAuthorizedGrant(page.items().get(0).eventId(), USER), ErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT denial_reason_code FROM download_events", String.class)).isEqualTo("ASSET_GONE");
    }
    @Test void rechecksMissingFilesChangedSizeAndModifiedContentsAtSecondStage() throws Exception {
        DownloadGrant grant = authorize(USER);
        Files.delete(file);
        code(() -> service.prepareContent(grant.eventId(), USER), ErrorCode.ASSET_GONE);
        Files.writeString(file, "new length");
        code(() -> service.prepareContent(grant.eventId(), USER), ErrorCode.ASSET_GONE);
        Files.write(file, CONTENT); Files.setLastModifiedTime(file, FileTime.from(NOW.plusSeconds(1)));
        code(() -> service.prepareContent(grant.eventId(), USER), ErrorCode.ASSET_GONE);
        Files.setLastModifiedTime(file, FileTime.from(NOW.minusSeconds(1)));
        ASSETS.values.put(new AssetId(1), asset("netcdf-data", "sample.nc", "sample.nc", CONTENT.length + 1, AssetStatus.AVAILABLE));
        code(() -> service.prepareContent(grant.eventId(), USER), ErrorCode.ASSET_GONE);
    }
    @Test void rejectsUnsafePathsStorageAndFilenameControls() {
        for (String path : List.of("../sample.nc", "/sample.nc", "C:/sample.nc", "nested/../sample.nc", "%2e%2e/sample.nc", "a\\sample.nc")) {
            ASSETS.values.put(new AssetId(1), asset("netcdf-data", path, "sample.nc", CONTENT.length, AssetStatus.AVAILABLE));
            code(() -> authorize(USER), ErrorCode.FORBIDDEN);
        }
        ASSETS.values.put(new AssetId(1), asset("arbitrary-root", "sample.nc", "sample.nc", CONTENT.length, AssetStatus.AVAILABLE));
        code(() -> authorize(USER), ErrorCode.FORBIDDEN);
        ASSETS.values.put(new AssetId(1), asset("netcdf-data", "evil\r\n.nc", "evil\r\n.nc", CONTENT.length, AssetStatus.AVAILABLE));
        code(() -> authorize(USER), ErrorCode.FORBIDDEN);
        assertThat(countEvents()).isEqualTo(8);
    }
    @Test void rejectsSymbolicDirectoryEscapingConfiguredRoot() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.write(outside.resolve("sample.nc"), CONTENT);
        Path link = storageRoot.resolve("link");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString())
                    .redirectErrorStream(true).start();
            assertThat(process.waitFor()).as("create test-only junction").isZero();
        } else Files.createSymbolicLink(link, outside);
        ASSETS.values.put(new AssetId(1), asset("netcdf-data", "link/sample.nc", "sample.nc", CONTENT.length, AssetStatus.AVAILABLE));
        code(() -> authorize(USER), ErrorCode.FORBIDDEN);
    }
    @Test void auditSnapshotFailureRollsBackWholeAuthorization() {
        jdbc.execute("CREATE TRIGGER reject_download_snapshot BEFORE INSERT ON download_event_products FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced snapshot rejection'");
        try { assertThatThrownBy(() -> authorize(USER)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER reject_download_snapshot"); }
        assertThat(countEvents()).isZero();
    }
    @Test void statisticsAndPaginationDoNotDoubleCountMultiProductFiles() throws Exception {
        authorize(USER); authorize(OTHER);
        Files.delete(file); code(() -> authorize(USER), ErrorCode.ASSET_GONE);
        var stats = service.getDownloadStatistics(new DownloadStatisticsQuery(NOW.minusSeconds(1), NOW.plusSeconds(1), new ProductCode("PRECIP")));
        assertThat(stats.requested()).isEqualTo(3); assertThat(stats.authorized()).isEqualTo(2);
        assertThat(stats.denied()).isEqualTo(1); assertThat(stats.uniqueAssets()).isEqualTo(1);
        assertThat(stats.uniqueUsers()).isEqualTo(2); assertThat(stats.uniqueOrganizations()).isEqualTo(2);
        assertThat(stats.byProduct()).containsEntry("PLP", 2L).containsEntry("PRECIP", 2L);
        assertThat(stats.byUser()).hasSize(2); assertThat(stats.byOrganization()).hasSize(2);
        var first = service.searchDownloadAudits(query(null, null, new ProductCode("PLP"), null, null, null, 1));
        var second = service.searchDownloadAudits(query(null, null, new ProductCode("PLP"), null, null, null, 2));
        assertThat(first.total()).isEqualTo(3); assertThat(first.items()).hasSize(1);
        assertThat(second.items().get(0).eventId()).isNotEqualTo(first.items().get(0).eventId());
        assertThat(service.searchDownloadAudits(query(null, null, null, OTHER.userId(), "lab two", DownloadStatus.AUTHORIZED, 1)).total()).isEqualTo(1);
        assertThat(service.getDownloadStatistics(new DownloadStatisticsQuery(NOW.plusSeconds(1), NOW.plusSeconds(2), null)).requested()).isZero();
    }
    @Test void localModeProvidesActualBytesAndNginxModeDoesNotOpenStream() throws Exception {
        DownloadGrant grant = authorize(USER);
        InputStream stream = service.prepareContent(grant.eventId(), USER).stream();
        assertThat(stream).isNotNull();
        try (stream) { assertThat(stream.readAllBytes()).isEqualTo(CONTENT); }
        assertThatThrownBy(stream::read).isInstanceOf(IOException.class);
        context.getBean(DownloadSettings.class).setTransferMode(DownloadSettings.TransferMode.NGINX);
        DownloadContent redirect = service.prepareContent(grant.eventId(), USER);
        assertThat(redirect.stream()).isNull();
        assertThat(redirect.grant().internalLocation()).isEqualTo("/internal-netcdf/sample.nc");
    }

    private DownloadGrant authorize(ActorContext actor) {
        return service.authorizeDownload(new DownloadCommand(new AssetId(1), PURPOSE), actor, new ClientContext("127.0.0.1", "test"));
    }
    private long countEvents() { return jdbc.queryForObject("SELECT COUNT(*) FROM download_events", Long.class); }
    private static DownloadableAsset asset(String storage, String path, String name, long bytes, AssetStatus status) {
        return new DownloadableAsset(new AssetId(1), AssetType.NETCDF, status, Set.of(new ProductCode("PLP"), new ProductCode("PRECIP")), storage, path, name, bytes);
    }
    private static DownloadAuditQuery query(Instant from, Instant to, ProductCode product, UserId user, String org, DownloadStatus status, int page) {
        return new DownloadAuditQuery(from, to, product, user, org, status, new PageRequest(page, 1));
    }
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode expected) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    static class FixtureAssets implements DownloadAssetLookup {
        final Map<AssetId, DownloadableAsset> values = new HashMap<>();
        @Override public Optional<DownloadableAsset> findDownloadableAsset(AssetId id) { return Optional.ofNullable(values.get(id)); }
        @Override public Optional<DownloadableAsset> findByStoragePath(String key, String path) {
            return values.values().stream().filter(v -> v.storageKey().equals(key) && v.relativePath().equals(path)).findFirst();
        }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value.get(); }
    }
    @TestConfiguration @EnableTransactionManagement(proxyTargetClass = true)
    @Import({DownloadService.class, DownloadAuditWriter.class, JdbcDownloadRepository.class})
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); }
        @Bean NamedParameterJdbcTemplate jdbc(DataSource ds) { return new NamedParameterJdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean DownloadAssetLookup assets() { return ASSETS; }
        @Bean("downloadClock") Clock clock() { return CLOCK; }
        @Bean DownloadSettings settings() { DownloadSettings s = new DownloadSettings(); s.setNetcdfRoot(storageRoot); return s; }
        @Bean LocalDownloadFiles files(DownloadSettings settings) { return new LocalDownloadFiles(settings, ""); }
    }
}

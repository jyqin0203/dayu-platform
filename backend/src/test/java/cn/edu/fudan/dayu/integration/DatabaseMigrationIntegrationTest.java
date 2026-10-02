package cn.edu.fudan.dayu.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 在临时 MariaDB 10.6 中验证 Flyway 基线、关键索引和数据库约束。
 *
 * <p>本测试只操作 Testcontainers 创建的临时数据库。Docker 不可用时明确跳过，
 * 不会连接本地或生产数据库。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationIntegrationTest {

    private static final Set<String> BUSINESS_TABLES = Set.of(
            "users",
            "login_attempts",
            "products",
            "product_mode_policies",
            "product_admin_events",
            "data_assets",
            "data_asset_products",
            "asset_scan_runs",
            "asset_scan_errors",
            "download_events",
            "download_event_products"
    );

    @Container
    private static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>("mariadb:10.6")
            .withDatabaseName("dayu_test")
            .withUsername("dayu_test")
            .withPassword("dayu_test_password");

    @BeforeAll
    static void migrateSchema() {
        var result = Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.migrationsExecuted).isEqualTo(4);
    }

    /** 验证全部业务表和容易遗漏的关键索引确实由 Flyway 创建。 */
    @Test
    void createsAllBusinessTablesAndCriticalIndexes() throws SQLException {
        assertThat(queryStrings(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'"
        )).containsExactlyInAnyOrderElementsOf(BUSINESS_TABLES);

        assertThat(queryStrings(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE()"
        )).contains(
                "uq_data_assets_storage_path",
                "idx_assets_timeline",
                "idx_assets_forecast_cycle",
                "idx_download_events_time_id",
                "idx_download_events_status_time_id"
        );
    }

    /** 验证重复路径不能重复索引，并由数据库兜底保护预报时间不变量。 */
    @Test
    void rejectsDuplicateAssetPathAndInvalidForecastTime() throws SQLException {
        execute("""
                INSERT INTO data_assets (
                    storage_key, relative_path, file_name, asset_type, data_mode,
                    valid_time, file_size, file_modified_at, indexed_at, last_seen_at, status
                ) VALUES (
                    'netcdf-data', 'realtime/sample.nc', 'sample.nc', 'NETCDF', 'REALTIME',
                    '2026-09-02 06:00:00', 1024, '2026-09-02 06:01:00',
                    '2026-09-02 06:02:00', '2026-09-02 06:02:00', 'AVAILABLE'
                )
                """);

        assertThatThrownBy(() -> execute("""
                INSERT INTO data_assets (
                    storage_key, relative_path, file_name, asset_type, data_mode,
                    valid_time, file_size, file_modified_at, indexed_at, last_seen_at, status
                ) VALUES (
                    'netcdf-data', 'realtime/sample.nc', 'renamed.nc', 'NETCDF', 'REALTIME',
                    '2026-09-02 06:00:00', 2048, '2026-09-02 06:01:00',
                    '2026-09-02 06:02:00', '2026-09-02 06:02:00', 'AVAILABLE'
                )
                """))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> execute("""
                INSERT INTO data_assets (
                    storage_key, relative_path, file_name, asset_type, data_mode,
                    cycle_time, valid_time, lead_minutes, file_size,
                    file_modified_at, indexed_at, last_seen_at, status
                ) VALUES (
                    'netcdf-data', 'forecast/invalid.nc', 'invalid.nc', 'NETCDF', 'FORECAST',
                    '2026-09-02 06:00:00', '2026-09-02 08:00:00', 60, 1024,
                    '2026-09-02 06:01:00', '2026-09-02 06:02:00',
                    '2026-09-02 06:02:00', 'AVAILABLE'
                )
                """))
                .isInstanceOf(SQLException.class);
    }

    /** 验证可重建映射会级联清理，而下载审计会阻止资产和用户被误删。 */
    @Test
    void appliesCascadeAndRestrictDeletionRules() throws SQLException {
        long userId = executeAndReturnId("""
                INSERT INTO users (email, password_hash, organization, role, status)
                VALUES ('db-test@example.test', 'test-hash', '测试单位', 'USER', 'ACTIVE')
                """);
        long productId = executeAndReturnId("""
                INSERT INTO products (
                    code, family, name_zh, name_en, description_zh, description_en,
                    producer, source_description, status
                ) VALUES (
                    'DBTEST', 'DBTEST', '数据库测试产品', 'Database Test Product',
                    '测试', 'test', '测试生产者', '测试来源', 'DRAFT'
                )
                """);
        long disposableAssetId = insertRealtimeAsset("realtime/disposable.nc");

        execute("INSERT INTO data_asset_products (asset_id, product_id) VALUES ("
                + disposableAssetId + ", " + productId + ")");
        execute("DELETE FROM data_assets WHERE id = " + disposableAssetId);
        assertThat(queryLong("SELECT COUNT(*) FROM data_asset_products WHERE asset_id = "
                + disposableAssetId)).isZero();

        long auditedAssetId = insertRealtimeAsset("realtime/audited.nc");
        execute(("""
                INSERT INTO download_events (
                    user_id, organization_snapshot, asset_id, file_name_snapshot,
                    expected_bytes, purpose, status
                ) VALUES (
                    %d, '测试单位', %d, 'audited.nc',
                    1024, '用于数据库外键约束集成测试', 'REQUESTED'
                )
                """).formatted(userId, auditedAssetId));

        assertThatThrownBy(() -> execute("DELETE FROM data_assets WHERE id = " + auditedAssetId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("DELETE FROM users WHERE id = " + userId))
                .isInstanceOf(SQLException.class);
    }

    private static long insertRealtimeAsset(String relativePath) throws SQLException {
        return executeAndReturnId(("""
                INSERT INTO data_assets (
                    storage_key, relative_path, file_name, asset_type, data_mode,
                    valid_time, file_size, file_modified_at, indexed_at, last_seen_at, status
                ) VALUES (
                    'netcdf-data', '%s', 'asset.nc', 'NETCDF', 'REALTIME',
                    '2026-09-02 06:00:00', 1024, '2026-09-02 06:01:00',
                    '2026-09-02 06:02:00', '2026-09-02 06:02:00', 'AVAILABLE'
                )
                """).formatted(relativePath));
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long executeAndReturnId(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static List<String> queryStrings(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }
}

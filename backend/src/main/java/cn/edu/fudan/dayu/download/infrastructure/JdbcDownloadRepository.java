package cn.edu.fudan.dayu.download.infrastructure;

import cn.edu.fudan.dayu.assetindex.api.DownloadableAsset;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.download.application.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 只访问 Download 自有事件与快照表。产品筛选用 EXISTS，避免多产品 NC 使计数翻倍。 */
@Repository
@Profile("!skeleton")
public class JdbcDownloadRepository implements DownloadRepository {
    private static final String AUTHORIZED = "e.authorized_at IS NOT NULL AND e.status IN ('AUTHORIZED','DELIVERED','INTERRUPTED')";
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcDownloadRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public DownloadEventId insert(DownloadableAsset asset, ActorContext actor, String purpose, ClientContext client,
                                            DownloadStatus status, String denialReason, Instant time) {
        String name = safe(asset.fileName(), 512);
        MapSqlParameterSource p = new MapSqlParameterSource("user", actor.userId().value())
                .addValue("org", actor.organization()).addValue("asset", asset.assetId().value())
                .addValue("file", name).addValue("bytes", Math.max(0, asset.fileSize())).addValue("purpose", purpose)
                .addValue("ip", safe(client == null ? null : client.ipAddress(), 45))
                .addValue("ua", safe(client == null ? null : client.userAgent(), 500))
                .addValue("status", status.name()).addValue("reason", denialReason).addValue("time", utc(time))
                .addValue("authorized", status == DownloadStatus.AUTHORIZED ? utc(time) : null);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO download_events(user_id,organization_snapshot,asset_id,file_name_snapshot,expected_bytes,
                purpose,ip_address,user_agent,status,denial_reason_code,requested_at,authorized_at)
                VALUES(:user,:org,:asset,:file,:bytes,:purpose,:ip,:ua,:status,:reason,:time,:authorized)
                """, p, key, new String[]{"id"});
        DownloadEventId id = new DownloadEventId(Objects.requireNonNull(key.getKey()).longValue());
        for (ProductCode product : asset.products()) {
            jdbc.update("INSERT INTO download_event_products(download_event_id,product_code_snapshot) VALUES(:id,:code)",
                    Map.of("id", id.value(), "code", product.value()));
        }
        return id;
    }
    @Override public Optional<StoredDownload> find(DownloadEventId id) {
        List<StoredDownload> rows = read("SELECT e.* FROM download_events e WHERE e.id=:id", new MapSqlParameterSource("id", id.value()));
        return rows.stream().findFirst();
    }
    @Override public PageResult<DownloadAuditSummary> search(DownloadAuditQuery q) {
        Filter filter = filter(q.from(), q.to(), q.productCode());
        if (q.userId() != null) filter.and("e.user_id=:user", "user", q.userId().value());
        if (q.organization() != null) filter.and("e.organization_snapshot=:org", "org", q.organization());
        if (q.status() != null) filter.and("e.status=:status", "status", q.status().name());
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM download_events e " + filter.where,
                filter.params, Long.class));
        filter.params.addValue("limit", q.pageRequest().size())
                .addValue("offset", (long) (q.pageRequest().page() - 1) * q.pageRequest().size());
        List<DownloadAuditSummary> rows = read("SELECT e.* FROM download_events e " + filter.where
                + " ORDER BY e.requested_at DESC,e.id DESC LIMIT :limit OFFSET :offset", filter.params)
                .stream().map(StoredDownload::summary).toList();
        return new PageResult<>(rows, q.pageRequest().page(), q.pageRequest().size(), total);
    }
    @Override public DownloadStatistics statistics(DownloadStatisticsQuery q) {
        Filter f = filter(q.from(), q.to(), q.productCode());
        long[] counts = jdbc.queryForObject("SELECT COUNT(*), COALESCE(SUM(" + AUTHORIZED + "),0),"
                + " COALESCE(SUM(e.status='DENIED'),0), COUNT(DISTINCT CASE WHEN " + AUTHORIZED
                + " THEN e.asset_id END), COUNT(DISTINCT CASE WHEN " + AUTHORIZED
                + " THEN e.user_id END), COUNT(DISTINCT CASE WHEN " + AUTHORIZED
                + " THEN e.organization_snapshot END) FROM download_events e " + f.where, f.params,
                (r, n) -> new long[]{r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6)});
        String authorizedWhere = f.where + " AND " + AUTHORIZED;
        Map<String, Long> products = group("SELECT p.product_code_snapshot,COUNT(*) FROM download_events e "
                + "JOIN download_event_products p ON p.download_event_id=e.id " + authorizedWhere
                + " GROUP BY p.product_code_snapshot", f.params);
        Map<String, Long> organizations = group("SELECT e.organization_snapshot,COUNT(*) FROM download_events e "
                + authorizedWhere + " GROUP BY e.organization_snapshot", f.params);
        Map<String, Long> users = group("SELECT e.user_id,COUNT(*) FROM download_events e "
                + authorizedWhere + " GROUP BY e.user_id", f.params);
        return new DownloadStatistics(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], products, organizations, users);
    }
    private Map<String, Long> group(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, r -> {
            Map<String, Long> result = new LinkedHashMap<>();
            while (r.next()) result.put(r.getString(1), r.getLong(2));
            return result;
        });
    }
    private List<StoredDownload> read(String sql, MapSqlParameterSource params) {
        List<StoredDownload> rows = jdbc.query(sql, params, (r, n) -> new StoredDownload(new DownloadAuditSummary(
                new DownloadEventId(r.getLong("id")), new UserId(r.getLong("user_id")), r.getString("organization_snapshot"),
                new AssetId(r.getLong("asset_id")), Set.of(), r.getString("file_name_snapshot"), r.getLong("expected_bytes"),
                r.getString("purpose"), instant(r, "authorized_at"), DownloadStatus.valueOf(r.getString("status"))), instant(r, "requested_at")));
        if (rows.isEmpty()) return rows;
        Map<Long, Set<ProductCode>> products = new HashMap<>();
        jdbc.query("SELECT download_event_id,product_code_snapshot FROM download_event_products WHERE download_event_id IN (:ids)",
                Map.of("ids", rows.stream().map(e -> e.summary().eventId().value()).toList()),
                (org.springframework.jdbc.core.RowCallbackHandler) r -> products.computeIfAbsent(r.getLong(1), unused -> new LinkedHashSet<>())
                        .add(new ProductCode(r.getString(2))));
        return rows.stream().map(e -> {
            DownloadAuditSummary s = e.summary();
            return new StoredDownload(new DownloadAuditSummary(s.eventId(), s.userId(), s.organizationSnapshot(), s.assetId(),
                    products.getOrDefault(s.eventId().value(), Set.of()), s.fileName(), s.expectedBytes(), s.purpose(),
                    s.authorizedAt(), s.status()), e.requestedAt());
        }).toList();
    }
    private static Filter filter(Instant from, Instant to, ProductCode product) {
        Filter f = new Filter();
        if (from != null) f.and("e.requested_at>=:from", "from", utc(from));
        if (to != null) f.and("e.requested_at<=:to", "to", utc(to));
        if (product != null) f.and("EXISTS (SELECT 1 FROM download_event_products p WHERE p.download_event_id=e.id AND p.product_code_snapshot=:product)",
                "product", product.value());
        return f;
    }
    private static class Filter {
        String where = "WHERE 1=1";
        final MapSqlParameterSource params = new MapSqlParameterSource();
        void and(String condition, String key, Object value) { where += " AND " + condition; params.addValue(key, value); }
    }
    private static String safe(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "_");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
    private static LocalDateTime utc(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        LocalDateTime value = r.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}

package cn.edu.fudan.dayu.assetindex.infrastructure;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.assetindex.application.AssetIndexStore;
import cn.edu.fudan.dayu.assetindex.domain.DataAsset;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MariaDB 索引适配器。资产与产品关系在单个事务中写入；不改写科学文件。 */
@Repository
@Profile("!skeleton")
public class JdbcAssetIndexStore implements AssetIndexStore {
    private static final String REPPIC_ROWS="a.asset_type='WEBP' AND a.data_mode='FORECAST' AND "
            +"(a.file_name LIKE 'FY4B!_AGRI!_REPPIC!_PRECIP!_%!_palettev2!_Dpi500.webp' ESCAPE '!' "
            +"OR a.file_name LIKE 'FY4B!_AGRI!_PRECIP!_%' ESCAPE '!')";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate independent;

    public JdbcAssetIndexStore(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Change upsert(DataAsset asset, Set<Long> products, Instant seenAt) {
        return tx.execute(status -> {
            Optional<Row> old = findByPath(asset.storageKey(), asset.relativePath());
            var p = assetParameters(asset).addValue("seen", utc(seenAt));
            boolean changed = old.isEmpty() || !same(old.get().asset(), asset) || !old.get().productIds().equals(products);
            long id;
            if (old.isEmpty()) {
                // UNIQUE 冲突使本文件事务回滚；单实例扫描锁避免正常流程的并发插入。
                id = insert("""
                        INSERT INTO data_assets(storage_key,relative_path,file_name,asset_type,data_mode,
                        cycle_time,valid_time,lead_minutes,file_size,dpi,file_modified_at,indexed_at,last_seen_at,status)
                        VALUES(:storage,:path,:name,:type,:mode,:cycle,:valid,:lead,:size,:dpi,:modified,:indexed,:seen,'AVAILABLE')
                        """, p);
            } else {
                id = old.get().asset().id().value();
                p.addValue("id", id);
                if (changed) jdbc.update("""
                        UPDATE data_assets SET file_name=:name,asset_type=:type,data_mode=:mode,
                        cycle_time=:cycle,valid_time=:valid,lead_minutes=:lead,file_size=:size,dpi=:dpi,
                        file_modified_at=:modified,indexed_at=:indexed,last_seen_at=:seen,status='AVAILABLE',checksum_sha256=NULL
                        WHERE id=:id
                        """, p);
                else jdbc.update("UPDATE data_assets SET last_seen_at=:seen WHERE id=:id", p);
            }
            if (changed) {
                jdbc.update("DELETE FROM data_asset_products WHERE asset_id=:id", Map.of("id", id));
                for (long product : products) jdbc.update("INSERT INTO data_asset_products(asset_id,product_id) VALUES(:a,:p)",
                        Map.of("a", id, "p", product));
            }
            Set<Long> affected = new HashSet<>(products);
            old.ifPresent(row -> affected.addAll(row.productIds()));
            return new Change(old.isEmpty(), changed, affected);
        });
    }

    private boolean same(DataAsset a, DataAsset b) {
        return a.status() == AssetStatus.AVAILABLE && a.assetType() == b.assetType()
                && a.dataMode() == b.dataMode() && Objects.equals(a.cycleTime(), b.cycleTime())
                && a.validTime().equals(b.validTime()) && Objects.equals(a.leadMinutes(), b.leadMinutes())
                && Objects.equals(a.dpi(), b.dpi()) && a.fileSize() == b.fileSize()
                && a.fileModifiedAt().equals(b.fileModifiedAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Override public Set<Instant> reppicCycles(String storageKey) {
        return new HashSet<>(jdbc.query("SELECT DISTINCT a.cycle_time FROM data_assets a WHERE a.storage_key=:storage AND "+REPPIC_ROWS,
                Map.of("storage",storageKey),(r,n) -> instant(r,"cycle_time")));
    }

    @Override public BatchChange replaceReppicCycle(String storage,Instant cycle,List<IndexedWrite> writes,Instant seenAt) {
        if (!writes.isEmpty() && (writes.size()!=3 || !writes.stream().map(w -> w.asset().leadMinutes())
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of(60,120,180))))
            throw new IllegalArgumentException("Released precipitation batch must contain all three leads");
        for(var write:writes) {
            var asset=write.asset();
            if (asset.assetType()!=AssetType.WEBP || asset.dataMode()!=DataMode.FORECAST || !storage.equals(asset.storageKey())
                    || !cycle.equals(asset.cycleTime()) || !new cn.edu.fudan.dayu.assetindex.domain.AssetFilenameParser()
                    .parse(asset.relativePath(),AssetType.WEBP,500).releaseControlled())
                throw new IllegalArgumentException("Invalid precipitation publication batch");
        }
        return tx.execute(status -> {
            var old=rows("SELECT a.* FROM data_assets a WHERE a.storage_key=:storage AND a.cycle_time=:cycle AND "+REPPIC_ROWS,
                    new MapSqlParameterSource("storage",storage).addValue("cycle",utc(cycle)));
            long created=0,updated=0,removed=0;
            Set<Long> affected=new HashSet<>();
            Set<String> keep=new HashSet<>();
            for(var write:writes) {
                var change=upsert(write.asset(),write.productIds(),seenAt);
                keep.add(write.asset().relativePath());
                if (change.created()) created++; else if (change.changed()) updated++;
                if (change.changed()) affected.addAll(change.affectedProductIds());
            }
            for(var row:old) if (!keep.contains(row.asset().relativePath())) {
                removeOrMarkMissing(row); removed++; affected.addAll(row.productIds());
            }
            return new BatchChange(created,updated,removed,Set.copyOf(affected));
        });
    }

    private MapSqlParameterSource assetParameters(DataAsset a) {
        return new MapSqlParameterSource().addValue("storage", a.storageKey()).addValue("path", a.relativePath())
                .addValue("name", a.fileName()).addValue("type", a.assetType().name()).addValue("mode", a.dataMode().name())
                .addValue("cycle", utc(a.cycleTime())).addValue("valid", utc(a.validTime())).addValue("lead", a.leadMinutes())
                .addValue("size", a.fileSize()).addValue("dpi", a.dpi()).addValue("modified", utc(a.fileModifiedAt()))
                .addValue("indexed", utc(a.indexedAt()));
    }

    @Override
    public List<Row> unseen(String storageKey, Instant scanStarted) {
        return rows("SELECT a.* FROM data_assets a WHERE storage_key=:storage AND last_seen_at<:start AND status='AVAILABLE' ORDER BY id LIMIT 200",
                new MapSqlParameterSource("storage", storageKey).addValue("start", utc(scanStarted)));
    }

    @Override
    public void removeOrMarkMissing(Row row) {
        if (row.asset().assetType() == AssetType.WEBP) jdbc.update("DELETE FROM data_assets WHERE id=:id", Map.of("id", row.asset().id().value()));
        else jdbc.update("UPDATE data_assets SET status='MISSING' WHERE id=:id", Map.of("id", row.asset().id().value()));
    }

    private String where(Filter f, MapSqlParameterSource p) {
        String sql = " WHERE a.asset_type=:type AND a.data_mode=:mode AND a.status='AVAILABLE' AND EXISTS "
                + "(SELECT 1 FROM data_asset_products ap WHERE ap.asset_id=a.id AND ap.product_id=:product)";
        p.addValue("type", f.type().name()).addValue("mode", f.mode().name()).addValue("product", f.productId());
        if (f.from() != null) { sql += " AND a.valid_time>=:from"; p.addValue("from", utc(f.from())); }
        if (f.to() != null) { sql += " AND a.valid_time<=:to"; p.addValue("to", utc(f.to())); }
        if (f.cycle() != null) { sql += " AND a.cycle_time=:cycle"; p.addValue("cycle", utc(f.cycle())); }
        if (f.lead() != null) { sql += " AND a.lead_minutes=:lead"; p.addValue("lead", f.lead()); }
        return sql;
    }

    @Override
    public List<Row> query(Filter f, int limit, long offset, boolean ascending) {
        var p = new MapSqlParameterSource();
        String sql = "SELECT a.* FROM data_assets a" + where(f, p);
        sql += ascending ? " ORDER BY a.valid_time,a.id" : " ORDER BY a.valid_time DESC,a.id DESC";
        if (limit > 0) { sql += " LIMIT :limit OFFSET :offset"; p.addValue("limit", limit).addValue("offset", offset); }
        return rows(sql, p);
    }

    @Override
    public long count(Filter f) {
        var p = new MapSqlParameterSource();
        return jdbc.queryForObject("SELECT COUNT(*) FROM data_assets a" + where(f, p), p, Long.class);
    }

    @Override
    public List<ForecastCycleSummary> cycles(long productId, AssetType type, Instant from, Instant to) {
        // 起报范围筛选不能裁掉该批次的有效时次。每个起报+时效聚合一行避免 GROUP_CONCAT 截断。
        var rows = jdbc.query("""
                SELECT a.cycle_time,a.lead_minutes,MIN(a.valid_time) first_time,MAX(a.valid_time) last_time
                FROM data_assets a WHERE a.status='AVAILABLE' AND a.asset_type=:type AND a.data_mode='FORECAST'
                AND (:from IS NULL OR a.cycle_time>=:from) AND (:to IS NULL OR a.cycle_time<=:to)
                AND EXISTS(SELECT 1 FROM data_asset_products ap WHERE ap.asset_id=a.id AND ap.product_id=:product)
                GROUP BY a.cycle_time,a.lead_minutes ORDER BY a.cycle_time DESC,a.lead_minutes
                """, new MapSqlParameterSource("type",type.name()).addValue("from",utc(from)).addValue("to",utc(to)).addValue("product",productId),
                (r,n) -> new ForecastCycleSummary(instant(r,"cycle_time"),instant(r,"first_time"),instant(r,"last_time"),
                        Set.of(r.getInt("lead_minutes")),false));
        Map<Instant, ForecastCycleSummary> grouped = new LinkedHashMap<>();
        for (var row : rows) grouped.merge(row.cycleTime(),row,(a,b) -> {
            Set<Integer> leads = new HashSet<>(a.leadMinutes()); leads.addAll(b.leadMinutes());
            return new ForecastCycleSummary(a.cycleTime(),a.firstValidTime().isBefore(b.firstValidTime()) ? a.firstValidTime() : b.firstValidTime(),
                    a.lastValidTime().isAfter(b.lastValidTime()) ? a.lastValidTime() : b.lastValidTime(),leads,false);
        });
        return List.copyOf(grouped.values());
    }

    @Override
    public Optional<Row> find(long id) {
        return rows("SELECT a.* FROM data_assets a WHERE id=:id", new MapSqlParameterSource("id", id)).stream().findFirst();
    }

    @Override
    public Optional<Row> findByPath(String storageKey, String relativePath) {
        return rows("SELECT a.* FROM data_assets a WHERE storage_key=:storage AND relative_path=:path",
                new MapSqlParameterSource("storage", storageKey).addValue("path", relativePath)).stream().findFirst()
                .map(row -> {
                    // MariaDB 的通用字符排序不等同于 Linux 路径身份，不能静默合并大小写不同的文件。
                    if (!row.asset().storageKey().equals(storageKey) || !row.asset().relativePath().equals(relativePath))
                        throw new IllegalArgumentException("Case-sensitive storage path collision");
                    return row;
                });
    }

    private List<Row> rows(String sql, MapSqlParameterSource p) {
        List<DataAsset> assets = jdbc.query(sql, p, (r, n) -> asset(r));
        if (assets.isEmpty()) return List.of();
        var links = new HashMap<Long, Set<Long>>();
        jdbc.query("SELECT asset_id,product_id FROM data_asset_products WHERE asset_id IN (:ids)",
                Map.of("ids", assets.stream().map(a -> a.id().value()).toList()), r -> {
                    links.computeIfAbsent(r.getLong(1), k -> new HashSet<>()).add(r.getLong(2));
                });
        return assets.stream().map(a -> new Row(a, Set.copyOf(links.getOrDefault(a.id().value(), Set.of())))).toList();
    }

    private DataAsset asset(ResultSet r) throws SQLException {
        return new DataAsset(new AssetId(r.getLong("id")), AssetType.valueOf(r.getString("asset_type")),
                DataMode.valueOf(r.getString("data_mode")), instant(r,"cycle_time"), instant(r,"valid_time"),
                r.getObject("lead_minutes", Integer.class), r.getString("storage_key"), r.getString("relative_path"),
                r.getString("file_name"), r.getLong("file_size"), r.getString("checksum_sha256"),
                r.getObject("dpi", Integer.class), instant(r,"file_modified_at"), AssetStatus.valueOf(r.getString("status")),
                instant(r,"indexed_at"));
    }

    @Override
    public long startRun(ScanTrigger trigger, UserId actor, Instant now) {
        // 必须在返回 ID 前提交，即使调用者处于事务中也不能把未提交任务交给后台线程。
        return independent.execute(status -> insert("INSERT INTO asset_scan_runs(trigger_type,triggered_by_user_id,status,started_at) VALUES(:trigger,:actor,'RUNNING',:now)",
                new MapSqlParameterSource("trigger", trigger.name()).addValue("actor", actor == null ? null : actor.value())
                        .addValue("now", utc(now))));
    }

    @Override
    public void finishRun(ScanRunView run) {
        tx.executeWithoutResult(status -> {
            var p = new MapSqlParameterSource("id", run.scanRunId()).addValue("status", run.status().name())
                    .addValue("end", utc(run.finishedAt())).addValue("scanned", run.scannedFiles())
                    .addValue("created", run.createdAssets()).addValue("updated", run.updatedAssets())
                    .addValue("removed", run.removedWebpAssets()).addValue("missing", run.missingNetcdfAssets())
                    .addValue("errors", run.errorCount());
            jdbc.update("""
                    UPDATE asset_scan_runs SET status=:status,finished_at=:end,scanned_files=:scanned,created_assets=:created,
                    updated_assets=:updated,removed_webp_assets=:removed,missing_netcdf_assets=:missing,error_count=:errors WHERE id=:id
                    """, p);
            for (AssetScanError error : run.errors()) {
                jdbc.update("""
                        INSERT INTO asset_scan_errors(scan_run_id,relative_path,error_code,safe_message,created_at)
                        VALUES(:run,:path,:code,:message,:now)
                        """, new MapSqlParameterSource("run", run.scanRunId()).addValue("path", error.relativePath())
                        .addValue("code", error.errorCode()).addValue("message", error.safeMessage()).addValue("now", utc(run.finishedAt())));
            }
        });
    }

    @Override
    public Optional<ScanRunView> findRun(long id, boolean withErrors) {
        var runs = jdbc.query("SELECT * FROM asset_scan_runs WHERE id=:id", Map.of("id", id), (r, n) -> run(r));
        if (runs.isEmpty()) return Optional.empty();
        var a = runs.get(0);
        var errors = withErrors ? jdbc.query("SELECT * FROM asset_scan_errors WHERE scan_run_id=:id ORDER BY id", Map.of("id", id),
                (r, n) -> new AssetScanError(r.getString("relative_path"),r.getString("error_code"),r.getString("safe_message"))) : List.<AssetScanError>of();
        return Optional.of(new ScanRunView(a.scanRunId(), a.trigger(), a.status(), a.triggeredByUserId(), a.startedAt(), a.finishedAt(),
                a.scannedFiles(), a.createdAssets(), a.updatedAssets(), a.removedWebpAssets(), a.missingNetcdfAssets(), a.errorCount(), errors));
    }

    @Override
    public PageResult<ScanRunView> runs(ScanRunQuery query) {
        String filter = " WHERE 1=1";
        var p = new MapSqlParameterSource();
        if (query.from() != null) { filter += " AND started_at>=:from"; p.addValue("from", utc(query.from())); }
        if (query.to() != null) { filter += " AND started_at<=:to"; p.addValue("to", utc(query.to())); }
        if (query.trigger() != null) { filter += " AND trigger_type=:trigger"; p.addValue("trigger", query.trigger().name()); }
        if (query.status() != null) { filter += " AND status=:status"; p.addValue("status", query.status().name()); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM asset_scan_runs" + filter, p, Long.class);
        p.addValue("limit", query.pageRequest().size()).addValue("offset", (query.pageRequest().page()-1L)*query.pageRequest().size());
        var items = jdbc.query("SELECT * FROM asset_scan_runs" + filter + " ORDER BY started_at DESC,id DESC LIMIT :limit OFFSET :offset", p,
                (r, n) -> run(r));
        return new PageResult<>(items, query.pageRequest().page(), query.pageRequest().size(), total);
    }

    private ScanRunView run(ResultSet r) throws SQLException {
        Long user = r.getObject("triggered_by_user_id", Long.class);
        return new ScanRunView(r.getLong("id"),ScanTrigger.valueOf(r.getString("trigger_type")),ScanStatus.valueOf(r.getString("status")),
                user == null ? null : new UserId(user),instant(r,"started_at"),instant(r,"finished_at"),r.getLong("scanned_files"),
                r.getLong("created_assets"),r.getLong("updated_assets"),r.getLong("removed_webp_assets"),r.getLong("missing_netcdf_assets"),
                r.getInt("error_count"),List.of());
    }

    @Override
    public void recoverInterruptedRuns(Instant now) {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO asset_scan_errors(scan_run_id,relative_path,error_code,safe_message,created_at)
                    SELECT id,'','PROCESS_RESTARTED','Application restarted before scan completed',:now
                    FROM asset_scan_runs WHERE status='RUNNING'
                    """,Map.of("now",utc(now)));
            jdbc.update("UPDATE asset_scan_runs SET status='FAILED',finished_at=:now,error_count=error_count+1 WHERE status='RUNNING'",
                    Map.of("now", utc(now)));
        });
    }

    private long insert(String sql, MapSqlParameterSource p) {
        var key = new GeneratedKeyHolder();
        jdbc.update(sql, p, key, new String[]{"id"});
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
    private static Instant instant(ResultSet r, String column) throws SQLException {
        LocalDateTime time = r.getObject(column, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}

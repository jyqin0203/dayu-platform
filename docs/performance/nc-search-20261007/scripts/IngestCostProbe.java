import cn.edu.fudan.dayu.assetindex.infrastructure.JdbcAssetIndexStore;
import cn.edu.fudan.dayu.assetindex.domain.DataAsset;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Replays real frozen metadata through the unchanged production JDBC store.
 * Inputs: private metadata snapshot and isolated DB password env. Output: aggregate JSON only.
 * Does not read scientific file contents, traverse source directories, or benchmark filesystem IO.
 */
public class IngestCostProbe {
    static final Path ROOT=Path.of("/home/ubuntu/dayu-test/performance/nc-search-20261007");
    static final ObjectMapper JSON=new ObjectMapper();
    record Entry(DataAsset asset,Set<Long> products) {}
    static Instant instant(JsonNode n,String k) {return n.get(k).isNull()?null:LocalDateTime.parse(n.get(k).asText().replace(' ','T')).toInstant(ZoneOffset.UTC);}
    static Integer integer(JsonNode n,String k) {return n.get(k).isNull()?null:n.get(k).asInt();}
    static DataAsset asset(JsonNode n) {
        return new DataAsset(null,AssetType.valueOf(n.get("asset_type").asText()),DataMode.valueOf(n.get("data_mode").asText()),
            instant(n,"cycle_time"),instant(n,"valid_time"),integer(n,"lead_minutes"),n.get("storage_key").asText(),n.get("relative_path").asText(),n.get("file_name").asText(),
            n.get("file_size").asLong(),null,integer(n,"dpi"),instant(n,"file_modified_at"),AssetStatus.AVAILABLE,instant(n,"indexed_at"));
    }
    static DataAsset changed(DataAsset a) {return new DataAsset(null,a.assetType(),a.dataMode(),a.cycleTime(),a.validTime(),a.leadMinutes(),a.storageKey(),a.relativePath(),a.fileName(),a.fileSize()+1,null,a.dpi(),a.fileModifiedAt(),a.status(),a.indexedAt());}
    static List<Entry> entries() throws Exception {
        List<JsonNode> assets=new ArrayList<>();Map<Long,Set<Long>> links=new HashMap<>();
        for(String line:Files.readAllLines(ROOT.resolve("snapshot/metadata.jsonl"))) {
            JsonNode n=JSON.readTree(line),v=n.get("row");
            if(n.get("table").asText().equals("data_assets"))assets.add(v);
            if(n.get("table").asText().equals("data_asset_products"))links.computeIfAbsent(v.get("asset_id").asLong(),k->new HashSet<>()).add(v.get("product_id").asLong());
        }
        return assets.stream().map(n->new Entry(asset(n),Set.copyOf(links.get(n.get("id").asLong())))).toList();
    }
    static void check(boolean condition) {if(!condition)throw new IllegalStateException("Isolated ingestion correctness check failed");}
    public static void main(String[] args) throws Exception {
        List<Entry> all=entries();check(all.size()==9270);List<Entry> subset=new ArrayList<>();for(int i=0;i<all.size();i+=10)subset.add(all.get(i));
        List<Map<String,Object>> results=new ArrayList<>();
        // One unreported warm-up per variant followed by three paired rounds, alternating A/B order.
        for(int round=0;round<=3;round++)for(String variant:round%2==0?List.of("a","b"):List.of("b","a")) {
            HikariConfig config=new HikariConfig();String db="dayu_perf_ingest_"+variant;
            config.setJdbcUrl("jdbc:mariadb://127.0.0.1:13307/"+db);config.setUsername("dayu_perf");config.setPassword(System.getenv("PERF_DB_PASSWORD"));
            config.setMaximumPoolSize(5);config.setMinimumIdle(1);config.setConnectionInitSql("SET time_zone = '+00:00'");
            try(HikariDataSource ds=new HikariDataSource(config)) {
                JdbcTemplate jdbc=new JdbcTemplate(ds);check(jdbc.queryForObject("SELECT @@port",Integer.class)==13307);
                check(jdbc.queryForObject("SELECT DATABASE()",String.class).equals(db));
                Integer indexes=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME='idx_assets_type_mode_status_lead_time'",Integer.class);
                check(indexes==(variant.equals("b")?6:0));
                jdbc.update("DELETE FROM data_asset_products");jdbc.update("DELETE FROM data_assets");jdbc.execute("ALTER TABLE data_assets AUTO_INCREMENT=1");
                JdbcAssetIndexStore store=new JdbcAssetIndexStore(new NamedParameterJdbcTemplate(ds),new DataSourceTransactionManager(ds));
                Instant seen=Instant.parse("2026-10-07T00:00:00Z");
                for(String phase:List.of("insert","unchanged","metadata-update-10pct","missing-10pct")) {
                    List<Entry> input=phase.endsWith("10pct")?subset:all;
                    long begin=System.nanoTime();List<Double> times=new ArrayList<>();
                    for(Entry e:input) {
                        long t=System.nanoTime();
                        if(phase.equals("missing-10pct"))store.removeOrMarkMissing(store.findByPath(e.asset().storageKey(),e.asset().relativePath()).orElseThrow());
                        else {
                            var c=store.upsert(phase.equals("metadata-update-10pct")?changed(e.asset()):e.asset(),e.products(),seen);
                            check(phase.equals("insert")?c.created():phase.equals("unchanged")?!c.changed():c.changed()&&!c.created());
                        }
                        times.add((System.nanoTime()-t)/1e6);
                    }
                    double seconds=(System.nanoTime()-begin)/1e9;Collections.sort(times);
                    long expectedAssets=all.size(),expectedLinks=9762,expectedMissing=0;
                    long expectedBytes=all.stream().mapToLong(e->e.asset().fileSize()).sum();
                    if(phase.endsWith("10pct"))expectedBytes+=subset.size();
                    if(phase.equals("missing-10pct"))for(Entry e:subset) {
                        if(e.asset().assetType()==AssetType.WEBP){expectedAssets--;expectedLinks-=e.products().size();expectedBytes-=e.asset().fileSize()+1;}else expectedMissing++;
                    }
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM data_assets",Long.class)==expectedAssets);
                    check(jdbc.queryForObject("SELECT SUM(file_size) FROM data_assets",Long.class)==expectedBytes);
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM data_asset_products",Long.class)==expectedLinks);
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM data_assets WHERE status='MISSING'",Long.class)==expectedMissing);
                    if(round>0) {
                        Map<String,Object> result=new LinkedHashMap<>();result.put("round",round);result.put("variant",variant);result.put("phase",phase);result.put("operations",input.size());result.put("seconds",seconds);result.put("operationsPerSecond",input.size()/seconds);result.put("p95OperationMs",times.get((int)Math.ceil(times.size()*.95)-1));result.put("assetRowsAfter",expectedAssets);result.put("linkRowsAfter",expectedLinks);result.put("missingNcAfter",expectedMissing);result.put("totalIndexedBytesAfter",expectedBytes);result.put("correctnessPassed",true);results.add(result);
                        Files.writeString(ROOT.resolve("logs/ingest-results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
                    }
                    System.out.println("round="+round+" variant="+variant+" phase="+phase+" seconds="+seconds);System.out.flush();seen=seen.plusSeconds(1);
                }
            }
        }
        System.out.println("Completed replay: no filesystem traversal; production store and real isolated MariaDB used.");
    }
}

package cn.edu.fudan.dayu.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.edu.fudan.dayu.DayuApplication;
import cn.edu.fudan.dayu.assetindex.api.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;

/** Real scanner, database, discovery and Legacy HTTP; only tiny synthetic files in an owned temporary directory. */
@Testcontainers @SpringBootTest(classes=DayuApplication.class) @AutoConfigureMockMvc
@ActiveProfiles("reppic-integration-test")
class ReppicWorkflowIntegrationTest {
    @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:10.6")
            .withTmpFs(Map.of("/var/lib/mysql","rw")).withStartupTimeout(Duration.ofMinutes(5))
            .withCommand("--innodb-buffer-pool-size=32M","--max-connections=30");
    static final Path ROOT=temporaryRoot(), NC=ROOT.resolve("nc"), WEBP=ROOT.resolve("webp");
    static final DateTimeFormatter STAMP=DateTimeFormatter.ofPattern("uuuuMMddHHmm").withZone(ZoneOffset.UTC);
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl); r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
        r.add("dayu.indexing.roots.netcdf-data",NC::toString); r.add("dayu.indexing.roots.webp-preview",WEBP::toString);
        r.add("dayu.indexing.enabled",()->false); r.add("dayu.cache.enabled",()->false);
        r.add("dayu.copilot.enabled",()->false); r.add("dayu.media.enabled",()->false);
    }
    @Autowired JdbcTemplate sql;
    @Autowired AssetIndexCommandService scanner;
    @Autowired MockMvc http;

    @Test void publishesOnlyCompleteBatchesAndBridgesAllThreeAliasesWithoutExtraProducts() throws Exception {
        for(String code:new String[]{"PRECIP","PLP"}) {
            sql.update("INSERT INTO products(code,family,name_zh,name_en,description_zh,description_en,producer,source_description,status,published_at) VALUES(?,'REPPIC_PRECIP',?,?,'test','test','test','test','PUBLISHED',UTC_TIMESTAMP())",code,code,code);
        }
        sql.update("INSERT INTO product_mode_policies(product_id,data_mode,enabled,stale_after_minutes) SELECT id,'FORECAST',1,180 FROM products");
        Files.createDirectories(NC); Files.createDirectories(WEBP);
        Instant older=Instant.now().truncatedTo(ChronoUnit.HOURS).minusSeconds(10800), newer=older.plusSeconds(3600);
        for(int lead=1;lead<=3;lead++) {
            write(image(older,lead),"synthetic");
            write(NC.resolve("forecast/"+STAMP.format(older)+"/FY4B_AGRI_REPPIC_PRECIP_"+lead+"H_"+STAMP.format(older)+"_"+STAMP.format(older.plusSeconds(lead*3600L))+".nc"),"synthetic scientific data");
            // Even three generic PRECIP filenames cannot bypass the production release protocol.
            Instant bypass=newer.plusSeconds(3600);
            write(WEBP.resolve("forecast/"+STAMP.format(bypass)+"/PRECIP/FY4B_AGRI_PRECIP_"+STAMP.format(bypass)+"_"
                    +STAMP.format(bypass.plusSeconds(lead*3600L))+"_Dpi500.webp"),"synthetic bypass attempt");
        }
        // Three images alone are insufficient, while NC remains independently discoverable.
        assertThat(scanner.runInitialFullScan().createdAssets()).isEqualTo(3);
        latest("");
        marker(older);
        assertThat(scanner.runIncrementalScan(ScanTrigger.MANUAL).createdAssets()).isEqualTo(3);
        latest(STAMP.format(older));
        for(int lead=1;lead<=3;lead++) {
            http.perform(get("/api/files.php").param("path","WebP/WebP_V2_Dpi500_4KM/forecast/"+STAMP.format(older)+"/PRECIP_"+lead+"H"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.files.length()").value(1))
                    .andExpect(jsonPath("$.files[0]").value("WebP/WebP_V2_Dpi500_4KM/"+WEBP.relativize(image(older,lead)).toString().replace('\\','/')));
        }
        http.perform(get("/api/search.php").param("type","multi").param("dir","netcdf/forecast")
                        .param("start",STAMP.format(older)).param("end",STAMP.format(older.plusSeconds(14400))).param("product","PRECIP_2H"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.files[0]").value(org.hamcrest.Matchers.containsString("_2H_")));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM products WHERE code LIKE 'PRECIP_%'",Long.class)).isZero();

        write(image(newer,1),"synthetic"); write(image(newer,2),"synthetic"); marker(newer);
        assertThat(scanner.runIncrementalScan(ScanTrigger.MANUAL).createdAssets()).isZero();
        latest(STAMP.format(older));
        write(image(newer,3),""); scanner.runIncrementalScan(ScanTrigger.MANUAL); latest(STAMP.format(older));
        write(image(newer,3),"synthetic");
        assertThat(scanner.runIncrementalScan(ScanTrigger.MANUAL).createdAssets()).isEqualTo(3);
        latest(STAMP.format(newer));
        http.perform(get("/api/v1/preview-frames").param("productCode","PRECIP").param("dataMode","FORECAST")
                        .param("from",newer.toString()).param("to",newer.plusSeconds(10800).toString()).param("cycleTime",newer.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));

        Files.delete(markerPath(newer));
        assertThat(scanner.runIncrementalScan(ScanTrigger.MANUAL).removedWebpAssets()).isEqualTo(3);
        latest(STAMP.format(older));
        assertThat(image(newer,1)).exists(); // Visibility changes never delete upstream files.
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_assets WHERE asset_type='NETCDF' AND status='AVAILABLE'",Long.class)).isEqualTo(3);
        Path offline=ROOT.resolve("webp-offline");
        Files.move(WEBP,offline);
        try {
            assertThat(scanner.runIncrementalScan(ScanTrigger.MANUAL).errors()).isNotEmpty();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_assets WHERE asset_type='WEBP'",Long.class)).isEqualTo(3);
        } finally { Files.move(offline,WEBP); }
    }
    private void latest(String expected) throws Exception {
        for(String product:new String[]{"PRECIP","PRECIP_1H","PRECIP_2H","PRECIP_3H"})
            http.perform(get("/api/fcst_latest.php").param("path","WebP/WebP_V2_Dpi500_4KM/forecast").param("product",product))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.latest").value(expected));
    }
    private Path image(Instant cycle,int lead) {
        return WEBP.resolve("forecast/"+STAMP.format(cycle)+"/PRECIP_"+lead+"H/FY4B_AGRI_REPPIC_PRECIP_"+lead+"H_"+STAMP.format(cycle)+"_"+STAMP.format(cycle.plusSeconds(lead*3600L))+"_palettev2_Dpi500.webp");
    }
    private Path markerPath(Instant cycle) { return WEBP.resolve("forecast/"+STAMP.format(cycle)+"/.reppic-complete.json"); }
    private void marker(Instant cycle) throws Exception {
        write(markerPath(cycle),"{\"schema\":\"fducrias-reppic-complete/v1\",\"complete\":true,\"cycle12\":\""+STAMP.format(cycle)+"\",\"archive_sha256\":\""+"a".repeat(64)+"\"}");
    }
    private static void write(Path file,String value) throws Exception { Files.createDirectories(file.getParent()); Files.writeString(file,value); }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("dayu-reppic-workflow-"); }
        catch(java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @AfterAll static void clean() throws Exception {
        try(var files=Files.walk(ROOT)) {
            for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}

package cn.edu.fudan.dayu.assetindex.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.assetindex.application.*;
import cn.edu.fudan.dayu.assetindex.domain.DataAsset;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.*;

/** 真正的临时 MariaDB + 临时目录；只有微型无科学内容文件，不依赖生产数据。 */
@Testcontainers
class AssetIndexIntegrationTest {
    @Container static final MariaDBContainer<?> DB=new MariaDBContainer<>("mariadb:10.6")
            .withDatabaseName("asset_index_test").withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql","rw"))
            .withCommand("--innodb-buffer-pool-size=32M", "--max-connections=30");
    @TempDir Path temp;
    private static JdbcTemplate sql;
    private static DriverManagerDataSource ds;
    private JdbcAssetIndexStore store;
    private PersistentAssetScanner scanner;
    private IndexedAssetQueries queries;
    private CatalogQueryService catalog;
    private ApplicationEventPublisher publisher;
    private IndexSettings settings;
    private Path nc,webp;
    private final ProductCode precip=new ProductCode("PRECIP");
    private final Instant cycle=Instant.parse("2026-09-02T06:00:00Z");

    @BeforeAll static void database() {
        ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        sql=new JdbcTemplate(ds);
    }

    @BeforeEach void setup() throws Exception {
        sql.update("DELETE FROM asset_scan_runs"); sql.update("DELETE FROM data_assets");
        sql.update("DELETE FROM products"); sql.update("DELETE FROM users");
        sql.update("INSERT INTO users(id,email,password_hash,organization,role,status) VALUES(1,'admin@example.test','fixture','Lab','ADMIN','ACTIVE')");
        List<ProductSummary> products=new ArrayList<>();
        long id=1;
        for (String code : List.of("PLP","PRECIP","BT855","CTH")) {
            String family=code.equals("BT855") ? "BT" : code.equals("CTH") ? "CPP" : "REPPIC_PRECIP";
            sql.update("INSERT INTO products(id,code,family,name_zh,name_en,description_zh,description_en,producer,source_description,status,published_at) VALUES(?,?,?,?,?,'fixture','fixture','lab','lab','PUBLISHED','2026-09-01 00:00:00')",
                    id,code,family,code,code);
            products.add(new ProductSummary(new ProductId(id++),new ProductCode(code),code,code,family,null,"Lab",null,"Lab",null,ProductStatus.PUBLISHED,0));
        }
        catalog=mock(CatalogQueryService.class);
        when(catalog.listManagedProducts(any())).thenReturn(products);
        when(catalog.resolveProductsForAssetFamily(anyString())).thenAnswer(call -> new AssetFamilyProductMapping(call.getArgument(0),
                products.stream().filter(p -> p.family().equals(call.getArgument(0))).map(ProductSummary::code).collect(java.util.stream.Collectors.toSet())));
        settings=new IndexSettings();
        nc=Files.createDirectories(temp.resolve("nc")); webp=Files.createDirectories(temp.resolve("webp"));
        settings.setRoots(Map.of("netcdf-data",nc,"webp-preview",webp));
        settings.setExpectedLeads(Map.of("PRECIP",Set.of(60,120,180)));
        publisher=mock(ApplicationEventPublisher.class);
        store=new JdbcAssetIndexStore(new NamedParameterJdbcTemplate(ds),new DataSourceTransactionManager(ds));
        scanner=new PersistentAssetScanner(store,new LocalFileInventory(),catalog,settings,publisher);
        scanner.initialize();
        queries=new IndexedAssetQueries(store,catalog,settings);
    }

    @AfterEach void close() { if (scanner != null) scanner.close(); }

    @Test void indexesIdempotentlyUpdatesMetadataAndReconcilesMissingFiles() throws Exception {
        Path scientific=write(nc,forecast(1),"abc");
        Path preview=write(webp,preview(60),"abc");
        var first=scanner.runInitialFullScan();
        assertThat(first.createdAssets()).isEqualTo(2);
        assertThat(first.errors()).isEmpty();
        var asset=queries.findByStoragePath("netcdf-data",forecast(1)).orElseThrow();
        assertThat(asset.products()).containsExactlyInAnyOrder(precip,new ProductCode("PLP"));
        assertThat(queries.findByStoragePath("wrong-root",forecast(1))).isEmpty();
        assertThat(queries.findByStoragePath("netcdf-science",forecast(1))).isEmpty();
        assertThat(scanner.runIncrementalScan(ScanTrigger.SCHEDULED).updatedAssets()).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_assets",Long.class)).isEqualTo(2);
        Files.writeString(scientific,"longer-file");
        var updated=scanner.runIncrementalScan(ScanTrigger.SCHEDULED);
        assertThat(updated.createdAssets()).isZero();
        assertThat(updated.updatedAssets()).isEqualTo(1);
        assertThat(queries.findDownloadableAsset(asset.assetId()).orElseThrow().fileSize()).isEqualTo(11);
        Files.delete(scientific); Files.delete(preview);
        var missing=scanner.runIncrementalScan(ScanTrigger.SCHEDULED);
        assertThat(missing.missingNetcdfAssets()).isEqualTo(1);
        assertThat(missing.removedWebpAssets()).isEqualTo(1);
        assertThat(queries.findDownloadableAsset(asset.assetId()).orElseThrow().status()).isEqualTo(AssetStatus.MISSING);
        assertThat(search(1,20).total()).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_asset_products",Long.class)).isEqualTo(2);
        write(nc,forecast(1),"restored");
        assertThat(scanner.runIncrementalScan(ScanTrigger.SCHEDULED).updatedAssets()).isEqualTo(1);
        assertThat(queries.findDownloadableAsset(asset.assetId()).orElseThrow().status()).isEqualTo(AssetStatus.AVAILABLE);
        verify(publisher,atLeastOnce()).publishEvent(any(AssetIndexChanged.class));
    }

    @Test void filtersProductTypeModeTimeCycleLeadAndPaginatesWithoutJoinDuplicates() throws Exception {
        for (int h=1;h<=3;h++) write(nc,forecast(h),"nc");
        write(nc,"realtime/FY4B_AGRI_REPPIC_PRECIP_202609020600.nc","nc");
        write(webp,preview(60),"webp"); write(webp,preview(120),"webp");
        scanner.runInitialFullScan();
        var p1=search(1,2); var p2=search(2,2);
        assertThat(p1.total()).isEqualTo(3);
        assertThat(p1.items()).extracting(IndexedAssetView::leadMinutes).containsExactly(180,120);
        assertThat(p2.items()).extracting(IndexedAssetView::leadMinutes).containsExactly(60);
        assertThat(queries.searchNetcdfAssets(new AssetSearchCriteria(precip,DataMode.FORECAST,null,null,null,null,new PageRequest(1,1))).items())
                .extracting(IndexedAssetView::leadMinutes).containsExactly(180);
        assertThat(queries.searchNetcdfAssets(new AssetSearchCriteria(precip,DataMode.FORECAST,cycle.plusSeconds(7200),null,null,null,new PageRequest(1,20))).total()).isEqualTo(2);
        assertThat(queries.searchNetcdfAssets(new AssetSearchCriteria(precip,DataMode.FORECAST,null,cycle.plusSeconds(7200),null,null,new PageRequest(1,20))).total()).isEqualTo(2);
        assertThat(p1.items()).allSatisfy(a -> { assertThat(a.previewRelativePath()).isNull(); assertThat(a.products()).hasSize(2); });
        assertThat(queries.searchNetcdfAssets(new AssetSearchCriteria(precip,DataMode.FORECAST,cycle,cycle.plusSeconds(3600),cycle,60,new PageRequest(1,20))).total()).isEqualTo(1);
        assertThat(queries.searchNetcdfAssets(new AssetSearchCriteria(precip,DataMode.REALTIME,cycle,cycle,null,null,new PageRequest(1,20))).total()).isEqualTo(1);
        assertThat(queries.findNetcdfCandidates(new AssetMatchCriteria(precip,DataMode.FORECAST,cycle.plusSeconds(7200),cycle,120))).hasSize(1);
        assertThat(queries.findNetcdfCandidates(new AssetMatchCriteria(precip,DataMode.REALTIME,cycle.plusSeconds(7200),null,null))).isEmpty();
        // Use an ordinary BT series for partial-frame query tests: precipitation partial batches are now intentionally hidden.
        var bt=new ProductCode("BT855");
        store.upsert(new DataAsset(null,AssetType.NETCDF,DataMode.FORECAST,cycle,cycle.plusSeconds(10800),180,
                "netcdf-data","forecast/202609020600/existing-bt.nc","existing-bt.nc",1,null,null,cycle,AssetStatus.AVAILABLE,cycle),Set.of(3L),cycle);
        var frames=queries.listPreviewAssets(new AssetPreviewCriteria(bt,DataMode.FORECAST,cycle,cycle.plusSeconds(10800),null,null,1));
        assertThat(frames).hasSize(1); assertThat(frames.get(0).leadMinutes()).isEqualTo(120);
        assertThat(frames.get(0).previewRelativePath()).isEqualTo(preview(120));
        assertThat(queries.listPreviewAssets(new AssetPreviewCriteria(bt,DataMode.FORECAST,null,null,null,null,1)))
                .extracting(IndexedAssetView::leadMinutes).containsExactly(120);
        assertThatThrownBy(() -> queries.findNetcdfCandidates(new AssetMatchCriteria(precip,DataMode.FORECAST,null,null,null)))
                .isInstanceOf(BusinessException.class);
        var ncCycles=queries.listForecastCycles(new AssetForecastCycleCriteria(precip,AssetType.NETCDF,cycle,cycle));
        var webpCycles=queries.listForecastCycles(new AssetForecastCycleCriteria(bt,AssetType.WEBP,null,null));
        assertThat(ncCycles).hasSize(1); assertThat(ncCycles.get(0).complete()).isTrue();
        assertThat(ncCycles.get(0).lastValidTime()).isEqualTo(cycle.plusSeconds(10800));
        assertThat(webpCycles.get(0).leadMinutes()).containsExactlyInAnyOrder(60,120);
        assertThat(webpCycles.get(0).complete()).isFalse();
        assertThat(queries.listForecastCycles(new AssetForecastCycleCriteria(precip,AssetType.NETCDF,cycle.plusSeconds(60),cycle.plusSeconds(3600)))).isEmpty();
    }

    @Test void incompleteOrUnavailableRootNeverDeletesOrMarksOtherFilesMissing() throws Exception {
        Path old=write(nc,forecast(1),"nc");
        scanner.runInitialFullScan(); Files.delete(old);
        write(nc,"realtime/bad.nc","bad");
        var partial=scanner.runIncrementalScan(ScanTrigger.SCHEDULED);
        assertThat(partial.errors()).hasSize(1);
        assertThat(partial.missingNetcdfAssets()).isZero();
        assertThat(search(1,20).total()).isEqualTo(1);
        settings.setRoots(Map.of("netcdf-data",temp.resolve("does-not-exist")));
        assertThat(scanner.runIncrementalScan(ScanTrigger.SCHEDULED).missingNetcdfAssets()).isZero();
        assertThat(search(1,20).total()).isEqualTo(1);
        assertThat(scanner.searchScanRuns(new ScanRunQuery(null,null,null,ScanStatus.PARTIAL,new PageRequest(1,20))).total()).isEqualTo(1);
        assertThat(scanner.searchScanRuns(new ScanRunQuery(null,null,null,ScanStatus.FAILED,new PageRequest(1,20))).total()).isEqualTo(1);
    }

    @Test void preservesTotalErrorsWhenDetailsAreCappedAndIgnoresTempDirectories() throws Exception {
        settings.setMaxErrors(1);
        write(nc,"realtime/bad1.nc","bad"); write(nc,"realtime/bad2.nc","bad");
        write(nc,"tmp/"+forecast(1),"ignored"); write(nc,".upload/"+forecast(1),"ignored");
        write(nc,forecast(1)+".part","ignored");
        Files.createDirectories(nc.resolve("forecast/202609020600"));
        scanner.runInitialFullScan();
        var run=scanner.searchScanRuns(new ScanRunQuery(null,null,null,null,new PageRequest(1,20))).items().get(0);
        assertThat(run.scannedFiles()).isEqualTo(2); assertThat(run.errorCount()).isEqualTo(2);
        assertThat(scanner.findScanRun(run.scanRunId()).orElseThrow().errors()).hasSize(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_assets",Long.class)).isZero();
        assertThat(scanner.findScanRun(Long.MAX_VALUE)).isEmpty();
    }

    @Test void rollsBackAssetAndRelationshipTogetherWhenForeignKeyFails() {
        var asset=new DataAsset(null,AssetType.NETCDF,DataMode.REALTIME,null,cycle,null,"netcdf-data","realtime/test.nc","test.nc",1,null,null,cycle,AssetStatus.AVAILABLE,cycle);
        assertThatThrownBy(() -> store.upsert(asset,Set.of(1L,99999L),cycle)).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_assets",Long.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_asset_products",Long.class)).isZero();
    }

    @Test void rollsBackWholeReleasedBatchWhenSecondRelationshipFails() {
        List<AssetIndexStore.IndexedWrite> writes=new ArrayList<>();
        for(int h=1;h<=3;h++) {
            String name="FY4B_AGRI_REPPIC_PRECIP_"+h+"H_202609020600_20260902"+String.format("%02d",6+h)+"00_palettev2_Dpi500.webp";
            String path="forecast/202609020600/PRECIP_"+h+"H/"+name;
            var asset=new DataAsset(null,AssetType.WEBP,DataMode.FORECAST,cycle,cycle.plusSeconds(h*3600L),h*60,
                    "webp-preview",path,name,1,null,500,cycle,AssetStatus.AVAILABLE,cycle);
            writes.add(new AssetIndexStore.IndexedWrite(asset,Set.of(h==2 ? 99999L : 2L)));
        }
        assertThatThrownBy(() -> store.replaceReppicCycle("webp-preview",cycle,writes,cycle)).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_assets",Long.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_asset_products",Long.class)).isZero();
        var valid=writes.stream().map(w -> new AssetIndexStore.IndexedWrite(w.asset(),Set.of(2L))).toList();
        assertThat(store.replaceReppicCycle("webp-preview",cycle,valid,cycle).created()).isEqualTo(3);
        assertThat(store.replaceReppicCycle("webp-preview",cycle,List.of(),cycle).removed()).isEqualTo(3);
        assertThat(store.reppicCycles("webp-preview")).isEmpty();
    }

    @Test void commitsTaskBeforeReturningEvenWhenCallerTransactionRollsBack() {
        var outer=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
        long[] id={0};
        outer.executeWithoutResult(status -> {
            id[0]=store.startRun(ScanTrigger.MANUAL,new UserId(1),Instant.now());
            status.setRollbackOnly();
        });
        assertThat(store.findRun(id[0],true)).isPresent();
    }

    @Test void reconcilesMoreThanOneBatchWithoutLoadingAllMissingAssets() {
        List<Object[]> parameters=new ArrayList<>();
        for (int i=0;i<205;i++) parameters.add(new Object[]{"realtime/missing-"+i+".nc"});
        sql.batchUpdate("""
                INSERT INTO data_assets(storage_key,relative_path,file_name,asset_type,data_mode,valid_time,
                file_size,file_modified_at,indexed_at,last_seen_at,status)
                VALUES('netcdf-data',?,'fixture.nc','NETCDF','REALTIME','2026-09-02 06:00:00',1,
                '2026-09-02 06:00:00','2026-09-02 06:00:00','2026-09-02 06:00:00','AVAILABLE')
                """,parameters);
        assertThat(scanner.runInitialFullScan().missingNetcdfAssets()).isEqualTo(205);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM data_assets WHERE status='MISSING'",Long.class)).isEqualTo(205);
    }

    @Test void persistsAsyncTaskBeforeDispatchRejectsOverlapAndRecoversInterruptedRuns() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        FileInventory blocking=(root,files,errors) -> {
            entered.countDown();
            try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            return true;
        };
        scanner.close();
        scanner=new PersistentAssetScanner(store,blocking,catalog,settings,publisher);
        var actor=new ActorContext(new UserId(1),"Lab",UserRole.ADMIN);
        var run=scanner.submitScan(ScanTrigger.MANUAL,actor);
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(run.status()).isEqualTo(ScanStatus.RUNNING);
        assertThat(scanner.findScanRun(run.scanRunId()).orElseThrow().triggeredByUserId()).isEqualTo(actor.userId());
        assertThatThrownBy(() -> scanner.submitScan(ScanTrigger.MANUAL,actor)).isInstanceOf(BusinessException.class);
        release.countDown();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (scanner.findScanRun(run.scanRunId()).orElseThrow().status()==ScanStatus.RUNNING && System.nanoTime()<deadline) Thread.sleep(20);
        assertThat(scanner.findScanRun(run.scanRunId()).orElseThrow().status()).isEqualTo(ScanStatus.SUCCEEDED);
        long orphan=store.startRun(ScanTrigger.STARTUP,null,Instant.now());
        store.recoverInterruptedRuns(Instant.now());
        assertThat(store.findRun(orphan,true).orElseThrow().status()).isEqualTo(ScanStatus.FAILED);
        assertThat(store.findRun(orphan,true).orElseThrow().errors()).extracting(AssetScanError::errorCode).containsExactly("PROCESS_RESTARTED");
        assertThatThrownBy(() -> scanner.submitScan(ScanTrigger.MANUAL,new ActorContext(new UserId(1),"Lab",UserRole.USER))).isInstanceOf(BusinessException.class);
    }

    private PageResult<IndexedAssetView> search(int page,int size) {
        return queries.searchNetcdfAssets(new AssetSearchCriteria(precip,DataMode.FORECAST,cycle,cycle.plusSeconds(10800),null,null,new PageRequest(page,size)));
    }
    private String forecast(int hour) {
        return "forecast/202609020600/FY4B_AGRI_REPPIC_PRECIP_"+hour+"H_202609020600_20260902"+String.format("%02d",6+hour)+"00.nc";
    }
    private String preview(int lead) {
        return "forecast/202609020600/BT855/FY4B_AGRI_BT855_202609020600_20260902"+String.format("%02d",6+lead/60)+"00_Dpi500.webp";
    }
    private Path write(Path root,String path,String content) throws Exception {
        Path file=root.resolve(path); Files.createDirectories(file.getParent()); return Files.writeString(file,content);
    }
}

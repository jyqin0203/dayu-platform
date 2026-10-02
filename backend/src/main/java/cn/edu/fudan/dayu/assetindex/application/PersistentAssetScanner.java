package cn.edu.fudan.dayu.assetindex.application;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.assetindex.domain.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 单实例有界扫描：正式文件元数据逐个入库，根完整遍历后才处理缺失。 */
@Service
@Profile("!skeleton")
@DependsOnDatabaseInitialization
public class PersistentAssetScanner implements AssetIndexCommandService, AssetScanTaskService, AssetAdminQueryService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PersistentAssetScanner.class);
    private final AssetIndexStore store;
    private final FileInventory files;
    private final CatalogQueryService catalog;
    private final IndexSettings settings;
    private final ApplicationEventPublisher publisher;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new SynchronousQueue<>(),r -> { Thread t=new Thread(r,"dayu-index-scan"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"dayu-index-schedule"); t.setDaemon(true); return t;
    });
    private final AssetFilenameParser parser = new AssetFilenameParser();

    public PersistentAssetScanner(AssetIndexStore store, FileInventory files, CatalogQueryService catalog,
            IndexSettings settings, ApplicationEventPublisher publisher) {
        this.store=store; this.files=files; this.catalog=catalog; this.settings=settings; this.publisher=publisher;
    }

    /** 迁移执行后恢复旧 RUNNING；调度默认关闭，开启时同线程 fixed delay 不重叠。 */
    @PostConstruct
    public void initialize() {
        settings.validate();
        store.recoverInterruptedRuns(now());
        if (settings.isEnabled()) timer.scheduleWithFixedDelay(() -> {
            try { runIncrementalScan(ScanTrigger.SCHEDULED); }
            catch (RuntimeException e) {
                // 保留失败类别便于运维排查，不将驱动异常内的 SQL/路径打印到日志。
                LOG.warn("Scheduled index scan did not complete: {}",e.getClass().getSimpleName());
            }
        },settings.getInterval().toMillis(),settings.getInterval().toMillis(),TimeUnit.MILLISECONDS);
    }

    @Override
    public ScanRunView submitScan(ScanTrigger trigger, ActorContext actor) {
        if (actor==null) throw new BusinessException(ErrorCode.UNAUTHENTICATED,"Login required");
        if (actor.role()!=UserRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN,"Administrator required");
        if (trigger!=ScanTrigger.MANUAL) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"Manual trigger required");
        ScanRunView run = begin(trigger,actor.userId());
        try { worker.execute(() -> execute(run)); }
        catch (RejectedExecutionException e) {
            store.finishRun(new ScanRunView(run.scanRunId(),run.trigger(),ScanStatus.FAILED,run.triggeredByUserId(),
                    run.startedAt(),now(),0,0,0,0,0,1,List.of(new AssetScanError("","TASK_REJECTED","Scanner is unavailable"))));
            running.set(false);
            throw new BusinessException(ErrorCode.CONFLICT,"Scanner is unavailable");
        }
        return run;
    }

    @Override public AssetScanResult runInitialFullScan() { return runIncrementalScan(ScanTrigger.STARTUP); }
    @Override
    public AssetScanResult runIncrementalScan(ScanTrigger trigger) {
        if (trigger==null) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"Trigger required");
        return execute(begin(trigger,null));
    }

    private ScanRunView begin(ScanTrigger trigger, UserId actor) {
        if (!running.compareAndSet(false,true)) throw new BusinessException(ErrorCode.CONFLICT,"SCAN_ALREADY_RUNNING");
        try {
            Instant started=now();
            long id=store.startRun(trigger,actor,started);
            return new ScanRunView(id,trigger,ScanStatus.RUNNING,actor,started,null,0,0,0,0,0,0,List.of());
        } catch (RuntimeException e) { running.set(false); throw e; }
    }

    private AssetScanResult execute(ScanRunView run) {
        var counts = new Counts(settings.getMaxErrors());
        boolean fatal=false;
        int completedRoots=0;
        try {
            settings.validate();
            Map<Long,ProductSummary> products=catalog.listManagedProducts(new ManagedProductQuery(null,null,null)).stream()
                    .collect(Collectors.toMap(p -> p.id().value(),p -> p));
            Map<ProductCode,Long> ids=products.values().stream().collect(Collectors.toMap(ProductSummary::code,p -> p.id().value()));
            Map<String,Set<ProductCode>> families=new HashMap<>();
            if (settings.getRoots().isEmpty()) {
                counts.error(new AssetScanError("","NO_STORAGE_ROOTS","No storage roots have been configured"));
                fatal=true;
            }
            for (var root : settings.getRoots().entrySet()) {
                String storage=root.getKey();
                AssetType type=settings.typeOf(storage);
                int errorsBefore=counts.errorCount;
                boolean complete=files.visit(root.getValue(),file -> {
                    // Other-type files in a root are never claimed as indexed assets.
                    if (type==AssetType.WEBP && !file.relativePath().endsWith(".webp")
                            || type==AssetType.NETCDF && !file.relativePath().endsWith(".nc")) return;
                    counts.scanned++;
                    try {
                        ParsedAsset parsed=parser.parse(file.relativePath(),type,settings.getDpi());
                        Set<ProductCode> codes=parsed.product()!=null ? Set.of(new ProductCode(parsed.product()))
                                : families.computeIfAbsent(parsed.family(),family -> catalog.resolveProductsForAssetFamily(family).products());
                        if (codes.isEmpty() || !ids.keySet().containsAll(codes)) throw new IllegalArgumentException("Unmapped product");
                        Set<Long> productIds=codes.stream().map(ids::get).collect(Collectors.toSet());
                        String name=file.relativePath().substring(file.relativePath().lastIndexOf('/')+1);
                        DataAsset asset=new DataAsset(null,type,parsed.mode(),parsed.cycleTime(),parsed.validTime(),parsed.leadMinutes(),storage,
                                file.relativePath(),name,file.size(),null,parsed.dpi(),file.modifiedAt(),AssetStatus.AVAILABLE,run.startedAt());
                        var change=store.upsert(asset,productIds,run.startedAt());
                        if (change.created()) counts.created++;
                        else if (change.changed()) counts.updated++;
                        if (change.changed()) change.affectedProductIds().forEach(id -> {
                            if (products.containsKey(id)) counts.affected.add(products.get(id).code());
                        });
                    } catch (RuntimeException e) {
                        counts.error(new AssetScanError(safePath(file.relativePath()),"INDEX_FILE_FAILED","File name, product mapping or metadata could not be indexed"));
                    }
                },counts::error);
                if (!complete && counts.errorCount==errorsBefore) {
                    counts.error(new AssetScanError("","INCOMPLETE_ROOT","Storage root could not be completely enumerated"));
                }
                // 连一个文件读取/解析/落库失败也不进行该根的缺失清理，避免误删除。
                if (complete && counts.errorCount==errorsBefore) {
                    completedRoots++;
                    List<AssetIndexStore.Row> absent;
                    while (!(absent=store.unseen(storage,run.startedAt())).isEmpty()) {
                        for (var missing : absent) {
                            store.removeOrMarkMissing(missing);
                            if (type==AssetType.WEBP) counts.removed++; else counts.missing++;
                            missing.productIds().forEach(id -> { if (products.containsKey(id)) counts.affected.add(products.get(id).code()); });
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            fatal=true;
            counts.error(new AssetScanError("","SCAN_FAILED","Scan could not be completed"));
        }
        try {
            Instant finished=now();
            ScanStatus status=fatal || completedRoots==0 && counts.scanned==0 && counts.errorCount>0
                    ? ScanStatus.FAILED : counts.errorCount>0 ? ScanStatus.PARTIAL : ScanStatus.SUCCEEDED;
            store.finishRun(new ScanRunView(run.scanRunId(),run.trigger(),status,run.triggeredByUserId(),run.startedAt(),finished,
                    counts.scanned,counts.created,counts.updated,counts.removed,counts.missing,counts.errorCount,counts.errors));
            if (!counts.affected.isEmpty()) {
                try { publisher.publishEvent(new AssetIndexChanged(counts.affected)); }
                catch (RuntimeException e) {
                    LOG.warn("Index committed but cache notification failed: {}",e.getClass().getSimpleName());
                }
            }
            return new AssetScanResult(run.trigger(),run.startedAt(),finished,counts.scanned,counts.created,counts.updated,
                    counts.removed,counts.missing,counts.affected,counts.errors);
        } finally { running.set(false); }
    }

    @Override public Optional<ScanRunView> findScanRun(long id) { return id<1 ? Optional.empty() : store.findRun(id,true); }
    @Override public PageResult<ScanRunView> searchScanRuns(ScanRunQuery query) {
        if (query==null || query.pageRequest()==null || query.from()!=null && query.to()!=null && query.from().isAfter(query.to()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"Invalid scan query");
        return store.runs(query);
    }
    @Override
    public Optional<AssetScanResult> getLatestScanResult() {
        // 新代码应使用 ScanRunView；旧契约没有 ID 和 status，只投影最近一次已结束任务。
        return store.runs(new ScanRunQuery(null,null,null,null,new PageRequest(1,200))).items().stream()
                .filter(r -> r.status()!=ScanStatus.RUNNING).findFirst().flatMap(r -> store.findRun(r.scanRunId(),true))
                .map(r -> new AssetScanResult(r.trigger(),r.startedAt(),r.finishedAt(),r.scannedFiles(),r.createdAssets(),r.updatedAssets(),
                        r.removedWebpAssets(),r.missingNetcdfAssets(),Set.of(),r.errors()));
    }
    @Override
    public PageResult<ScanHistorySummary> searchScanHistory(ScanHistoryQuery query) {
        var result=searchScanRuns(new ScanRunQuery(query.from(),query.to(),query.trigger(),null,query.pageRequest()));
        return new PageResult<>(result.items().stream().map(r -> new ScanHistorySummary(r.trigger(),r.startedAt(),r.finishedAt(),
                r.scannedFiles(),r.createdAssets()+r.updatedAssets()+r.removedWebpAssets()+r.missingNetcdfAssets(),r.errorCount())).toList(),
                result.page(),result.size(),result.total());
    }
    @PreDestroy public void close() { timer.shutdownNow(); worker.shutdown(); }
    private Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private String safePath(String path) {
        String safe=path.replaceAll("[\\p{Cntrl}]","?");
        return safe.length()>1000 ? safe.substring(0,1000) : safe;
    }
    private static class Counts {
        long scanned,created,updated,removed,missing;
        int errorCount;
        final int max;
        final Set<ProductCode> affected=new HashSet<>();
        final List<AssetScanError> errors=new ArrayList<>();
        Counts(int max) { this.max=max; }
        void error(AssetScanError error) {
            errorCount++;
            if (errors.size()<max) {
                String path=error.relativePath().replaceAll("[\\p{Cntrl}]","?");
                errors.add(new AssetScanError(path.length()>1000 ? path.substring(0,1000) : path,error.errorCode(),error.safeMessage()));
            }
        }
    }
}

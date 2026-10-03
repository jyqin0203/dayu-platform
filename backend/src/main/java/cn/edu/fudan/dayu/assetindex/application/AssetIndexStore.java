package cn.edu.fudan.dayu.assetindex.application;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.assetindex.domain.DataAsset;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.*;

/** AssetIndex 拥有的四张表持久化端口，不读取 Catalog 用户资料或产品实体表。 */
public interface AssetIndexStore {
    record Row(DataAsset asset, Set<Long> productIds) {}
    record Filter(long productId, AssetType type, DataMode mode, Instant from, Instant to,
                  Instant cycle, Integer lead) {}
    record Change(boolean created, boolean changed, Set<Long> affectedProductIds) {}
    record IndexedWrite(DataAsset asset, Set<Long> productIds) {}
    record BatchChange(long created, long updated, long removed, Set<Long> affectedProductIds) {}
    Change upsert(DataAsset asset, Set<Long> productIds, Instant seenAt);
    /** Existing precipitation WebP cycles must be rechecked even when their files or marker disappear. */
    Set<Instant> reppicCycles(String storageKey);
    /** Publish the three verified rows or withdraw a whole indexed batch atomically; never deletes files. */
    BatchChange replaceReppicCycle(String storageKey, Instant cycle, List<IndexedWrite> assets, Instant seenAt);
    /** 每批最多 200 条；调用方处理后继续读取，避免全部缺失资产进入内存。 */
    List<Row> unseen(String storageKey, Instant scanStarted);
    void removeOrMarkMissing(Row row);
    List<Row> query(Filter filter, int limit, long offset, boolean ascending);
    long count(Filter filter);
    List<ForecastCycleSummary> cycles(long productId, AssetType type, Instant from, Instant to);
    Optional<Row> find(long id);
    Optional<Row> findByPath(String storageKey, String relativePath);
    long startRun(ScanTrigger trigger, UserId actor, Instant now);
    void finishRun(ScanRunView run);
    Optional<ScanRunView> findRun(long id, boolean withErrors);
    PageResult<ScanRunView> runs(ScanRunQuery query);
    void recoverInterruptedRuns(Instant now);
}

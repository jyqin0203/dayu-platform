package cn.edu.fudan.dayu.assetindex.api;

import java.time.Instant;

/**
 * 管理后台列表中展示的一次扫描历史摘要。
 */
public record ScanHistorySummary(
        ScanTrigger trigger, Instant startedAt, Instant finishedAt,
        long scannedFiles, long changedAssets, int errorCount
) {}

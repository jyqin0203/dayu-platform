package cn.edu.fudan.dayu.assetindex.api;

import java.time.Instant;
import java.util.Set;

/**
 * 一个预报起报批次的可用时间范围、预报时效和完整性摘要。
 */
public record ForecastCycleSummary(
        Instant cycleTime, Instant firstValidTime, Instant lastValidTime,
        Set<Integer> leadMinutes, boolean complete
) {
    public ForecastCycleSummary {
        leadMinutes = Set.copyOf(leadMinutes);
    }
}

package cn.edu.fudan.dayu.discovery.api;

import java.time.Instant;
import java.util.Set;

/**
 * Discovery 对外返回的预报批次摘要。
 */
public record ForecastCycleSummary(
        Instant cycleTime, Instant firstValidTime, Instant lastValidTime,
        Set<Integer> leadMinutes, boolean complete
) {
    public ForecastCycleSummary {
        leadMinutes = Set.copyOf(leadMinutes);
    }
}

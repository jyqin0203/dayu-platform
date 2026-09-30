package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import java.time.Instant;

/**
 * 管理员查询扫描历史时使用的时间、触发来源和分页条件。
 */
public record ScanHistoryQuery(Instant from, Instant to, ScanTrigger trigger, PageRequest pageRequest) {}

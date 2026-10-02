package cn.edu.fudan.dayu.assetindex.api;

import cn.edu.fudan.dayu.shared.kernel.PageRequest;
import java.time.Instant;

/** 可选 UTC 起止时间、触发来源和状态；按开始时间及 ID 倒序分页。 */
public record ScanRunQuery(Instant from, Instant to, ScanTrigger trigger,
                           ScanStatus status, PageRequest pageRequest) {}

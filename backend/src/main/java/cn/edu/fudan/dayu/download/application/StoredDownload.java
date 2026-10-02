package cn.edu.fudan.dayu.download.application;

import cn.edu.fudan.dayu.download.api.DownloadAuditSummary;
import java.time.Instant;

/** 持久事件及申请时间；拒绝事件 authorizedAt 可以为空。 */
public record StoredDownload(DownloadAuditSummary summary, Instant requestedAt) {}

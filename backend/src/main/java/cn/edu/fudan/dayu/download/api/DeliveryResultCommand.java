package cn.edu.fudan.dayu.download.api;

import cn.edu.fudan.dayu.shared.kernel.DownloadEventId;
import java.time.Instant;

/**
 * 后续根据 Nginx 传输日志回写实际传输结果时使用的命令。
 */
public record DeliveryResultCommand(
        DownloadEventId eventId, long deliveredBytes, Instant finishedAt, DownloadStatus status
) {}

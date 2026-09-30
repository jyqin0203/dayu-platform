package cn.edu.fudan.dayu.shared.kernel;

/**
 * 表示一次下载申请或授权记录的唯一内部编号。
 */
public record DownloadEventId(long value) {
    public DownloadEventId {
        if (value <= 0) throw new IllegalArgumentException("download event id must be positive");
    }
}

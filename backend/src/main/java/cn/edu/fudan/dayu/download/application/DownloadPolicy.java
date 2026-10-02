package cn.edu.fudan.dayu.download.application;

import java.time.Duration;

/** 下载用例读取的配置端口；测试可以注入固定配置。 */
public interface DownloadPolicy {
    Duration grantTtl();
    boolean nginxTransfer();
}

package cn.edu.fudan.dayu.operations.api;

import cn.edu.fudan.dayu.discovery.api.ProductHealthStatus;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductCode;
import java.time.Instant;

/**
 * 管理后台展示的单个产品数据健康摘要。未执行资产数量聚合时计数为 null，不能用有无文件冒充数量。
 */
public record ProductHealth(
        ProductCode productCode, DataMode dataMode, Long webpCount, Long netcdfCount,
        Instant latestValidTime, ProductHealthStatus status, long staleAfterMinutes
) {}

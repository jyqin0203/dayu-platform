package cn.edu.fudan.dayu.catalog.api;

import cn.edu.fudan.dayu.shared.kernel.DataMode;
import cn.edu.fudan.dayu.shared.kernel.ProductId;
import java.time.Duration;
import java.util.Objects;

/**
 * 创建或替换一个产品在指定数据模式下的启用状态和过期阈值。
 */
public record ConfigureProductModeCommand(
        ProductId productId,
        DataMode dataMode,
        boolean enabled,
        Duration staleAfter
) {
    private static final Duration MINIMUM_STALE_AFTER = Duration.ofMinutes(10);
    private static final Duration MAXIMUM_STALE_AFTER = Duration.ofDays(7);

    public ConfigureProductModeCommand {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(dataMode, "dataMode");
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.compareTo(MINIMUM_STALE_AFTER) < 0
                || staleAfter.compareTo(MAXIMUM_STALE_AFTER) > 0
                || !staleAfter.equals(Duration.ofMinutes(staleAfter.toMinutes()))) {
            throw new IllegalArgumentException(
                    "staleAfter must be a whole number of minutes between 10 and 10080");
        }
    }
}

package cn.edu.fudan.dayu.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.fudan.dayu.assetindex.domain.DataAsset;
import cn.edu.fudan.dayu.shared.kernel.AssetId;
import cn.edu.fudan.dayu.shared.kernel.AssetStatus;
import cn.edu.fudan.dayu.shared.kernel.AssetType;
import cn.edu.fudan.dayu.shared.kernel.DataMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * DataAsset 领域时间规则的单元测试。
 */
class DataAssetTest {

    /** 验证预报有效时间必须等于起报时间加预报时效。 */
    @Test
    void forecastValidTimeMustEqualCyclePlusLeadMinutes() {
        Instant cycle = Instant.parse("2026-09-02T06:00:00Z");

        assertThatThrownBy(() -> new DataAsset(new AssetId(1), AssetType.NETCDF, DataMode.FORECAST,
                cycle, cycle.plusSeconds(3_600), 120, "netcdf-science", "safe/file.nc", "file.nc",
                1, null, null, cycle, AssetStatus.AVAILABLE, cycle))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid time");
    }
}

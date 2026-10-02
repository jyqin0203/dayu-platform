package cn.edu.fudan.dayu.assetindex.domain;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AssetFilenameParserTest {
    private final AssetFilenameParser parser=new AssetFilenameParser();

    @Test void parsesObservedAndForecastFilesWithoutReadingArrays() {
        var bt=parser.parse("realtime/BT855/FY4B_AGRI_BT855_202609020600_Dpi500.webp",AssetType.WEBP,500);
        assertThat(bt.mode()).isEqualTo(DataMode.REALTIME);
        assertThat(bt.cycleTime()).isNull();
        assertThat(bt.product()).isEqualTo("BT855");
        var webp=parser.parse("forecast/202609020600/CTH/FY4B_AGRI_CTH_202609020600_202609020630_Dpi500.webp",AssetType.WEBP,500);
        assertThat(webp.leadMinutes()).isEqualTo(30);
        var nc=parser.parse("forecast/202609020600/FY4B_AGRI_REPPIC_PRECIP_2H_202609020600_202609020800.nc",AssetType.NETCDF,500);
        assertThat(nc.family()).isEqualTo("REPPIC_PRECIP");
        assertThat(nc.validTime()).isEqualTo(Instant.parse("2026-09-02T08:00:00Z"));
        assertThat(parser.parse("realtime/FY4B_AGRI_REPPIC_PRECIP_202609020600.nc",AssetType.NETCDF,500).family()).isEqualTo("REPPIC_PRECIP");
        // 族映射已经认可，不代表 BT/CPP 的物理文件命名已经确认。
        assertThatThrownBy(() -> parser.parse("realtime/FY4B_AGRI_CPP_202609020600.nc",AssetType.NETCDF,500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("realtime/FY4B_AGRI_BT_202609020600.nc",AssetType.NETCDF,500)).isInstanceOf(IllegalArgumentException.class);
        var cloud=parser.parse("realtime/CLP/FY4B_AGRI_CLP_FD_202609260530_Dpi500.webp",AssetType.WEBP,500);
        assertThat(cloud.product()).isEqualTo("CLP");
        assertThat(cloud.dpi()).isEqualTo(500);
        assertThat(parser.parse("realtime/RGB/FY4B_AGRI_RGB_FD_202511080430.webp",AssetType.WEBP,500).product()).isEqualTo("RGB");
        assertThat(parser.parse("realtime/FRGB/FY4B_AGRI_FRGB_FD_202511080430.webp",AssetType.WEBP,500).dpi()).isEqualTo(500);
    }

    @Test void rejectsInconsistentTimesAndUnsafeOrUnpublishedNames() {
        for (String path : new String[]{
                "realtime/FY4B_AGRI_REPPIC_PRECIP_202602300600.nc",
                "forecast/202609020500/FY4B_AGRI_REPPIC_PRECIP_1H_202609020600_202609020700.nc",
                "forecast/202609020600/FY4B_AGRI_REPPIC_PRECIP_2H_202609020600_202609020700.nc",
                "forecast/202609020600/FY4B_AGRI_REPPIC_PRECIP_202609020600_202609020700.nc",
                "forecast/202609020600/FY4B_AGRI_BT_202609020600_202609020500.nc",
                "realtime/FY4B_AGRI_BT_202609020600_202609020700.nc",
                "../realtime/FY4B_AGRI_BT_202609020600.nc",
                "C:/realtime/FY4B_AGRI_BT_202609020600.nc",
                "realtime/FY4B_AGRI_BT_202609020600.nc.tmp"}) {
            assertThatThrownBy(() -> parser.parse(path,AssetType.NETCDF,500)).as(path).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> parser.parse("realtime/CTH/FY4B_AGRI_BT855_202609020600_Dpi500.webp",AssetType.WEBP,500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("realtime/BT855/FY4B_AGRI_BT855_202609020600_Dpi1000.webp",AssetType.WEBP,500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("realtime/CLP/FY4B_AGRI_CLP_FD_202609260530.webp",AssetType.WEBP,500)).isInstanceOf(IllegalArgumentException.class);
    }
}

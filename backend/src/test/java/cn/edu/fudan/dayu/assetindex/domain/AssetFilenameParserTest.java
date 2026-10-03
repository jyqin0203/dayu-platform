package cn.edu.fudan.dayu.assetindex.domain;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AssetFilenameParserTest {
    private final AssetFilenameParser parser=new AssetFilenameParser();

    @Test void normalizesProductionPaletteForecastToOneProductAndExactLead() {
        for(int h=1;h<=3;h++) {
            String path="forecast/202609021200/PRECIP_"+h+"H/FY4B_AGRI_REPPIC_PRECIP_"+h+"H_202609021200_20260902"+(12+h)+"00_palettev2_Dpi500.webp";
            var asset=parser.parse(path,AssetType.WEBP,500);
            assertThat(asset.product()).isEqualTo("PRECIP");
            assertThat(asset.leadMinutes()).isEqualTo(h*60);
            assertThat(asset.releaseControlled()).isTrue();
        }
        assertThat(parser.parse("forecast/202609021200/PRECIP/FY4B_AGRI_PRECIP_202609021200_202609021300_Dpi500.webp",
                AssetType.WEBP,500).releaseControlled()).isTrue();
    }

    @Test void rejectsWrongPaletteLeadFolderTimestampAndDpi() {
        String path="forecast/202609021200/PRECIP_1H/FY4B_AGRI_REPPIC_PRECIP_1H_202609021200_202609021300_palettev2_Dpi500.webp";
        for(String invalid:java.util.List.of(path.replace("/PRECIP_1H/","/PRECIP_2H/"),
                path.replace("202609021300","202609021400"),path.replace("_1H_","_4H_"),
                path.replace("palettev2","palettev3"),path.replace("forecast/202609021200","forecast/202609021100")))
            assertThatThrownBy(() -> parser.parse(invalid,AssetType.WEBP,500)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parser.parse(path,AssetType.WEBP,1000)).isInstanceOf(IllegalArgumentException.class);
    }

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

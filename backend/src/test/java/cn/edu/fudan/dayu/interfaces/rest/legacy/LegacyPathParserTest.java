package cn.edu.fudan.dayu.interfaces.rest.legacy;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyPathParserTest {
    private final LegacyPathParser parser = new LegacyPathParser("WebP/WebP_V2_Dpi500_4KM", "netcdf");
    @Test void acceptsOnlyExactConfiguredRootsAndKnownDirectoryShapes() {
        var path = parser.directory("WebP/WebP_V2_Dpi500_4KM/forecast/202609020600/BT855/").orElseThrow();
        assertThat(path.mode()).isEqualTo(DataMode.FORECAST);
        assertThat(path.cycle()).isEqualTo(Instant.parse("2026-09-02T06:00:00Z"));
        assertThat(path.product().value()).isEqualTo("BT855");
        assertThat(parser.directory("netcdf/realtime")).isPresent();
        assertThat(parser.directory("netcdf/forecast/202609020600")).isPresent();
        assertThat(parser.forecastRoot("WebP/WebP_V2_Dpi500_4KM/forecast")).isTrue();
        for (String invalid : List.of("WebP/wrong/forecast", "WebP/WebP_V2_Dpi500_4KM/forecaster",
                "netcdf-backup/realtime", "netcdf/realtime/unknown", "prefix/forecast", "netcdf/unknown",
                "WebP/WebP_V2_Dpi500_4KM/forecast/not-a-cycle/BT855", "WebP/WebP_V2_Dpi500_4KM/realtime/BT855/extra"))
            assertThat(parser.directory(invalid)).as(invalid).isEmpty();
    }
    @Test void rejectsAbsoluteTraversalEncodedAndControlPathsBeforeModuleCalls() {
        for (String invalid : List.of("/netcdf/realtime/a.nc", "C:\\netcdf\\a.nc", "netcdf/../a.nc",
                "netcdf/./a.nc", "netcdf/%2e%2e/a.nc", "netcdf/%252e%252e/a.nc", "netcdf//a.nc",
                "netcdf/a.nc\r\nX-Test:bad", "netcdf/a.nc?download=true"))
            assertThatThrownBy(() -> parser.ncRelativePath(invalid)).isInstanceOf(BusinessException.class);
        assertThat(parser.ncRelativePath("netcdf/forecast/202609020600/data.nc"))
                .isEqualTo("forecast/202609020600/data.nc");
    }
    @Test void requiresExactlyTwelveDigitsAndStrictGregorianCalendar() {
        assertThat(LegacyPathParser.time("202402290600")).isEqualTo(Instant.parse("2024-02-29T06:00:00Z"));
        for (String invalid : List.of("202602290600", "202609310600", "202613010600", "202601012400", "202601010060", "20260902060", "2026090206000"))
            assertThatThrownBy(() -> LegacyPathParser.time(invalid)).isInstanceOf(RuntimeException.class);
    }
}

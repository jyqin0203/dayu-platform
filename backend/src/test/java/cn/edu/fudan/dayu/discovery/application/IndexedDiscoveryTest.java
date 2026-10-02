package cn.edu.fudan.dayu.discovery.application;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IndexedDiscoveryTest {
    private final CatalogQueryService catalog = mock(CatalogQueryService.class);
    private final AssetQueryService assets = mock(AssetQueryService.class);
    private final ProductCode code = new ProductCode("PRECIP");
    private final Instant now = Instant.parse("2026-10-02T00:00:00Z");
    private IndexedDiscovery service;

    @BeforeEach void setup() {
        service = new IndexedDiscovery(catalog, assets, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofDays(3), "/media/webp/");
        when(catalog.findProduct(code)).thenReturn(Optional.of(product(true)));
    }

    @Test void rejectsDisabledModeBeforeLookingAtAssets() {
        when(catalog.findProduct(code)).thenReturn(Optional.of(product(false)));
        assertThatThrownBy(() -> service.searchScientificAssets(new ScientificAssetQuery(
                code, DataMode.FORECAST, now.minusSeconds(86400), now, null, null, new PageRequest(1,20))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(assets);
    }

    @Test void rejectsRealtimeForecastParameters() {
        assertThatThrownBy(() -> service.searchScientificAssets(new ScientificAssetQuery(
                code, DataMode.REALTIME, now.minusSeconds(60), now, now, 60, new PageRequest(1,20))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(assets);
    }

    @Test void historicalNcDoesNotUseWebpRetention() {
        var asset = asset(AssetType.NETCDF, DataMode.FORECAST, now.minusSeconds(864000), null);
        when(assets.searchNetcdfAssets(any())).thenReturn(new PageResult<>(List.of(asset),1,20,1));
        assertThat(service.searchScientificAssets(new ScientificAssetQuery(code, DataMode.FORECAST,
                now.minusSeconds(864000), now, null,null,new PageRequest(1,20))).total()).isEqualTo(1);
    }

    @Test void returnsEncodedPublicPreviewPathAndTrueDownloadAvailability() {
        var webp = asset(AssetType.WEBP, DataMode.REALTIME, now.minusSeconds(60),
                "realtime/PRECIP/frame name.webp");
        when(assets.listPreviewAssets(any())).thenReturn(List.of(webp));
        when(assets.findNetcdfCandidates(any())).thenReturn(List.of(
                asset(AssetType.NETCDF, DataMode.REALTIME, now.minusSeconds(60), null)));
        var frames = service.listPreviewFrames(new PreviewQuery(code, DataMode.REALTIME,
                now.minusSeconds(120), now, null,null,200));
        assertThat(frames.get(0).previewUrl().toASCIIString()).isEqualTo("/media/webp/realtime/PRECIP/frame%20name.webp");
        assertThat(frames.get(0).downloadAvailable()).isTrue();
        assertThat(frames.get(0).fileSize()).isEqualTo(1024);
    }

    @Test void oldPreviewReturnsEmptyAndDoesNotQueryIndex() {
        assertThat(service.listPreviewFrames(new PreviewQuery(code, DataMode.REALTIME,
                now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(7)), null,null,200))).isEmpty();
        verifyNoInteractions(assets);
    }

    @Test void forecastHealthUsesCycleTimeRatherThanFutureValidTime() {
        when(assets.listPreviewAssets(any())).thenReturn(List.of());
        // Effective time lies in future, but cycle is two hours old and policy is one hour.
        when(assets.searchNetcdfAssets(any())).thenReturn(new PageResult<>(List.of(
                new IndexedAssetView(new AssetId(1), AssetType.NETCDF, Set.of(code), DataMode.FORECAST,
                        now.minusSeconds(7200), now.plusSeconds(7200),240,"test.nc",1,null,AssetStatus.AVAILABLE)),1,1,1));
        assertThat(service.getProductAvailability(new ProductAvailabilityQuery(code,DataMode.FORECAST)).health())
                .isEqualTo(ProductHealthStatus.STALE);
    }

    @Test void rejectsPreviewPathEscapesInsteadOfLeakingThem() {
        when(assets.listPreviewAssets(any())).thenReturn(List.of(asset(AssetType.WEBP,
                DataMode.REALTIME,now,"../private.webp")));
        assertThatThrownBy(() -> service.listPreviewFrames(new PreviewQuery(code,DataMode.REALTIME,
                now.minusSeconds(60),now,null,null,20))).isInstanceOf(BusinessException.class);
    }

    private IndexedAssetView asset(AssetType type, DataMode mode, Instant valid, String path) {
        return new IndexedAssetView(new AssetId(1),type,Set.of(code),mode,
                mode==DataMode.FORECAST ? valid.minusSeconds(3600):null, valid,
                mode==DataMode.FORECAST ? 60:null,"sample",1024,type==AssetType.WEBP ? 500:null,
                AssetStatus.AVAILABLE,path);
    }

    private ProductDetail product(boolean forecastEnabled) {
        return new ProductDetail(new ProductSummary(new ProductId(1),code,"降水","Precipitation",
                "REPPIC_PRECIP","mm/h","lab","RePPIC","AGRI",null,ProductStatus.PUBLISHED,1),
                "说明","description",false,null,List.of(
                new ProductModePolicy(code,DataMode.REALTIME,true,Duration.ofHours(1)),
                new ProductModePolicy(code,DataMode.FORECAST,forecastEnabled,Duration.ofHours(1))),
                now,now,now);
    }
}

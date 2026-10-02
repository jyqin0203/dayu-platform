package cn.edu.fudan.dayu.operations.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.operations.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

/** 仅验证 Operations 编排；依赖模块用可控响应验证实参和口径，不假装数据库集成。 */
class OperationsApplicationServiceTest {
    private final CatalogQueryService catalog=mock(CatalogQueryService.class);
    private final AssetIndexCommandService commands=mock(AssetIndexCommandService.class);
    private final AssetScanTaskService scans=mock(AssetScanTaskService.class);
    private final DiscoveryQueryService discovery=mock(DiscoveryQueryService.class);
    private final DownloadAuditQueryService downloads=mock(DownloadAuditQueryService.class);
    private final UserAdminService users=mock(UserAdminService.class);
    private final ActorContext admin=new ActorContext(new UserId(1),"Lab",UserRole.ADMIN);
    private final ProductCode precip=new ProductCode("PRECIP");
    private final ProductCode bt=new ProductCode("BT855");
    private final Instant now=Instant.parse("2026-10-02T06:00:00Z");
    private final OperationsApplicationService service=new OperationsApplicationService(catalog,commands,scans,discovery,downloads,users,
            Clock.fixed(now,ZoneOffset.UTC));
    private final DownloadStatistics statistics=new DownloadStatistics(50,40,10,17,29,13,Map.of(),Map.of(),Map.of());

    @BeforeEach void setUp() {
        var rain=product(1,precip,ProductStatus.PUBLISHED,List.of(mode(precip,DataMode.REALTIME,true,90),mode(precip,DataMode.FORECAST,true,360)));
        var brightness=product(2,bt,ProductStatus.PUBLISHED,List.of(mode(bt,DataMode.REALTIME,true,120),mode(bt,DataMode.FORECAST,false,480)));
        var cloud=product(3,new ProductCode("CTH"),ProductStatus.DRAFT,List.of(mode(new ProductCode("CTH"),DataMode.REALTIME,true,180)));
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(rain,brightness));
        when(catalog.listManagedProducts(any())).thenReturn(List.of(rain.summary(),brightness.summary(),cloud.summary()));
        when(catalog.listManagedProductDetails(any())).thenReturn(List.of(rain,brightness,cloud));
        for (var p : List.of(rain,brightness,cloud)) when(catalog.findProduct(p.summary().code())).thenReturn(Optional.of(p));
        when(discovery.listProductAvailability(any())).thenAnswer(call -> {
            ProductAvailabilityListQuery q=call.getArgument(0);
            return q.productCodes().stream().map(code -> code.equals(bt)
                    ? new ProductAvailability(code,q.dataMode(),false,false,null,null,ProductHealthStatus.MISSING)
                    : new ProductAvailability(code,q.dataMode(),q.dataMode()==DataMode.REALTIME,true,
                            q.dataMode()==DataMode.FORECAST ? now.plus(Duration.ofHours(2)) : now,null,
                            q.dataMode()==DataMode.REALTIME ? ProductHealthStatus.HEALTHY : ProductHealthStatus.STALE)).toList();
        });
        when(downloads.getDownloadStatistics(any())).thenReturn(statistics);
        when(scans.searchScanRuns(any())).thenReturn(new PageResult<>(List.of(),1,1,0));
    }

    @Test void dashboardDefaultsToThirtyDaysAndCountsProductsOnceAcrossModes() {
        var result=service.getDashboard(new OperationsQuery(null,null,null,null),admin);
        assertThat(result.publishedProducts()).isEqualTo(2);
        assertThat(result.previewAvailableProducts()).isEqualTo(1);
        assertThat(result.downloadAvailableProducts()).isEqualTo(1);
        assertThat(result.missingProducts()).isEqualTo(1);
        assertThat(result.staleProducts()).isEqualTo(1);
        assertThat(result.latestScan()).isNull();
        assertThat(result.downloads()).isSameAs(statistics);
        var query=ArgumentCaptor.forClass(DownloadStatisticsQuery.class);
        verify(downloads).getDownloadStatistics(query.capture());
        assertThat(query.getValue().to()).isEqualTo(now);
        assertThat(Duration.between(query.getValue().from(),query.getValue().to())).isEqualTo(Duration.ofDays(30));
    }

    @Test void dashboardForwardsProductAndModeAndPreservesTaskIdentity() {
        var run=new ScanRunView(23,ScanTrigger.MANUAL,ScanStatus.RUNNING,admin.userId(),now,null,0,0,0,0,0,0,List.of());
        when(scans.searchScanRuns(any())).thenReturn(new PageResult<>(List.of(run),1,1,1));
        var result=service.getDashboard(new OperationsQuery(now.minusSeconds(3600),now,precip,DataMode.FORECAST),admin);
        assertThat(result.publishedProducts()).isEqualTo(1);
        assertThat(result.previewAvailableProducts()).isZero();
        assertThat(result.latestScan().scanRunId()).isEqualTo(23);
        verify(discovery).listProductAvailability(new ProductAvailabilityListQuery(Set.of(precip),DataMode.FORECAST));
        verify(downloads).getDownloadStatistics(new DownloadStatisticsQuery(now.minusSeconds(3600),now,precip));
    }

    @Test void dashboardModeOnlyFiltersCurrentHealthNotPublishedProductCount() {
        var result=service.getDashboard(new OperationsQuery(now.minusSeconds(3600),now,null,DataMode.FORECAST),admin);
        assertThat(result.publishedProducts()).isEqualTo(2);
        verify(discovery).listProductAvailability(new ProductAvailabilityListQuery(Set.of(precip),DataMode.FORECAST));
    }

    @Test void healthIncludesAllConfiguredModesAndDoesNotInventCountsOrThresholds() {
        var result=service.getProductHealth(new ProductHealthQuery(Set.of(),null),admin);
        assertThat(result).hasSize(5);
        assertThat(result).allSatisfy(h -> { assertThat(h.webpCount()).isNull(); assertThat(h.netcdfCount()).isNull(); });
        assertThat(result.stream().filter(h -> h.productCode().equals(bt) && h.dataMode()==DataMode.FORECAST).findFirst()).get()
                .satisfies(h -> { assertThat(h.status()).isEqualTo(ProductHealthStatus.DISABLED); assertThat(h.staleAfterMinutes()).isEqualTo(480); assertThat(h.latestValidTime()).isNull(); });
        var filtered=service.getProductHealth(new ProductHealthQuery(Set.of(precip),DataMode.FORECAST),admin);
        assertThat(filtered).singleElement().satisfies(h -> {
            assertThat(h.staleAfterMinutes()).isEqualTo(360);
            assertThat(h.latestValidTime()).isAfter(now);
            assertThat(h.status()).isEqualTo(ProductHealthStatus.STALE);
        });
    }

    @Test void rejectsInvalidRangesAndPermissionBeforeCallingDependencies() {
        assertThatThrownBy(() -> service.getDashboard(new OperationsQuery(now,now.minusSeconds(1),null,null),admin))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.getDashboard(new OperationsQuery(now.minus(Duration.ofDays(366)),now,null,null),admin))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getDashboard(new OperationsQuery(null,null,null,null),new ActorContext(new UserId(2),"Lab",UserRole.USER)))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.getProductHealth(new ProductHealthQuery(Set.of(),null),null))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verifyNoInteractions(discovery,downloads,scans);
    }

    @Test void auditAndUserCommandsDelegateWithoutDuplicatingBusinessRules() {
        var query=new DownloadAuditQuery(now.minusSeconds(60),now,precip,new UserId(2),"Lab",DownloadStatus.DENIED,new PageRequest(2,10));
        var page=new PageResult<DownloadAuditSummary>(List.of(),2,10,0);
        when(downloads.searchDownloadAudits(query)).thenReturn(page);
        assertThat(service.searchDownloadAudits(query,admin)).isSameAs(page);
        var command=new ChangeUserRoleCommand(new UserId(2),UserRole.ADMIN);
        var changed=new UserSummary(new UserId(2),"member@example.test","Lab",UserRole.ADMIN,UserStatus.ACTIVE);
        when(users.changeUserRole(command,admin)).thenReturn(changed);
        assertThat(service.changeUserRole(command,admin)).isSameAs(changed);
        verify(users).changeUserRole(command,admin);
    }

    private ProductModePolicy mode(ProductCode code,DataMode mode,boolean enabled,long minutes) {
        return new ProductModePolicy(code,mode,enabled,Duration.ofMinutes(minutes));
    }
    private ProductDetail product(long id,ProductCode code,ProductStatus status,List<ProductModePolicy> modes) {
        var summary=new ProductSummary(new ProductId(id),code,code.value(),code.value(),"fixture",null,"Lab",null,"source",null,status,0);
        return new ProductDetail(summary,"description","description",false,null,modes,status==ProductStatus.PUBLISHED ? now : null,now,now);
    }
}

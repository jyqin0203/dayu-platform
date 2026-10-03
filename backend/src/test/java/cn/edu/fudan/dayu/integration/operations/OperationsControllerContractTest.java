package cn.edu.fudan.dayu.integration.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.edu.fudan.dayu.assetindex.api.*;
import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.identity.api.*;
import cn.edu.fudan.dayu.interfaces.rest.SkeletonSecurityConfiguration;
import cn.edu.fudan.dayu.interfaces.rest.v1.*;
import cn.edu.fudan.dayu.interfaces.rest.v1.operations.OperationsController;
import cn.edu.fudan.dayu.operations.application.OperationsApplicationService;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 真实安全过滤链+Session主体+Operations编排；其他模块的可控API用于检查HTTP形状及参数转发。 */
@WebMvcTest(OperationsController.class)
@Import({OperationsApplicationService.class,CurrentActorProvider.class,SkeletonSecurityConfiguration.class,V1ExceptionHandler.class,TraceIdFilter.class})
class OperationsControllerContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CatalogQueryService catalog;
    @MockitoBean AssetIndexCommandService commands;
    @MockitoBean AssetScanTaskService scans;
    @MockitoBean DiscoveryQueryService discovery;
    @MockitoBean DownloadAuditQueryService downloads;
    @MockitoBean UserAdminService users;
    @MockitoBean IdentityService identity;
    private final Instant time=Instant.parse("2026-10-02T06:00:00Z");
    private final ProductCode code=new ProductCode("PRECIP");
    private MockHttpSession admin;

    @BeforeEach void prepare() {
        admin=session(UserRole.ADMIN);
        // 当前用户来自本请求恢复的安全上下文；不存在共享的“当前登录用户”变量。
        when(identity.getCurrentUser()).thenAnswer(call -> {
            var auth=SecurityContextHolder.getContext().getAuthentication();
            return auth!=null && auth.getPrincipal() instanceof AuthenticatedUser user ? Optional.of(user) : Optional.empty();
        });
        var summary=new ProductSummary(new ProductId(1),code,"降水","Precipitation","REPPIC_PRECIP",null,"Lab",null,"source",null,ProductStatus.PUBLISHED,0);
        var detail=new ProductDetail(summary,"说明","description",false,null,List.of(
                new ProductModePolicy(code,DataMode.REALTIME,true,Duration.ofMinutes(90)),
                new ProductModePolicy(code,DataMode.FORECAST,true,Duration.ofMinutes(360))),time,time,time);
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(detail));
        when(catalog.listManagedProducts(any())).thenReturn(List.of(summary));
        when(catalog.listManagedProductDetails(any())).thenReturn(List.of(detail));
        when(catalog.findProduct(code)).thenReturn(Optional.of(detail));
        when(discovery.listProductAvailability(any())).thenAnswer(call -> {
            ProductAvailabilityListQuery query=call.getArgument(0);
            return query.productCodes().stream().map(c -> new ProductAvailability(c,query.dataMode(),false,false,null,null,ProductHealthStatus.MISSING)).toList();
        });
        when(downloads.getDownloadStatistics(any())).thenReturn(new DownloadStatistics(100,80,20,41,29,13,Map.of(),Map.of(),Map.of()));
        when(scans.searchScanRuns(any())).thenAnswer(call -> {
            ScanRunQuery q=call.getArgument(0); return new PageResult<>(List.of(),q.pageRequest().page(),q.pageRequest().size(),0);
        });
    }

    @Test void allAdminRoutesRequireAuthenticatedAdministratorAndWritesRequireCsrf() throws Exception {
        for (String path : List.of("dashboard","product-health","index-scans","index-scans/73","download-audits","download-statistics","users")) {
            mvc.perform(get("/api/v1/admin/"+path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/"+path).session(session(UserRole.USER))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/admin/index-scans").session(admin)).andExpect(status().isForbidden());
        verifyNoInteractions(commands,scans);
    }

    @Test void dashboardAndHealthUseRealNullableShapesAndExplicitDistinctStatistics() throws Exception {
        String body=mvc.perform(get("/api/v1/admin/dashboard").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publishedProducts").value(1))
                .andExpect(jsonPath("$.downloads.authorizedRequests").value(80))
                .andExpect(jsonPath("$.downloads.uniqueUsers").value(29))
                .andExpect(jsonPath("$.downloads.uniqueOrganizations").value(13))
                .andExpect(jsonPath("$.downloads.uniqueAssets").value(41))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).get("latestScan").isNull()).isTrue();
        String health=mvc.perform(get("/api/v1/admin/product-health").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        var items=mapper.readTree(health).get("items");
        assertThat(items.get(0).get("latestValidTime").isNull()).isTrue();
        assertThat(items.get(1).get("staleAfterMinutes").asLong()).isEqualTo(360);
        assertThat(items.get(1).get("dataMode").asText()).isEqualTo("FORECAST");
        assertThat(items.get(0).has("webpCount")).isFalse();
    }

    @Test void statisticsExposeTheOpenApiFieldsAndForwardEveryFilter() throws Exception {
        when(downloads.getDownloadStatistics(any())).thenReturn(new DownloadStatistics(12,9,3,7,5,4,
                Map.of("PRECIP",9L),Map.of("Lab",9L),Map.of("2",9L)));
        String body=mvc.perform(get("/api/v1/admin/download-statistics").session(admin)
                        .param("from",time.minusSeconds(3600).toString()).param("to",time.toString()).param("productCode","PRECIP"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authorizedRequests").value(9))
                .andExpect(jsonPath("$.uniqueAssets").value(7)).andExpect(jsonPath("$.uniqueUsers").value(5))
                .andExpect(jsonPath("$.uniqueOrganizations").value(4)).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).size()).isEqualTo(4);
        verify(downloads).getDownloadStatistics(new DownloadStatisticsQuery(time.minusSeconds(3600),time,code));
    }

    @Test void dashboardRejectsReversedExcessiveAndInvalidFilters() throws Exception {
        mvc.perform(get("/api/v1/admin/dashboard").session(admin).param("from","2026-10-03T00:00:00Z").param("to","2026-10-02T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/dashboard").session(admin).param("from","2024-10-03T00:00:00Z").param("to","2026-10-02T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/dashboard").session(admin).param("productCode","bad-code"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/dashboard").session(admin).param("from","not-a-time"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/dashboard").session(admin).param("productCode","PRECIP").param("dataMode","FORECAST"))
                .andExpect(status().isOk());
        verify(discovery).listProductAvailability(new ProductAvailabilityListQuery(Set.of(code),DataMode.FORECAST));
    }

    @Test void scanUsesPersistedIdStateDetailAndHistoryFilters() throws Exception {
        var accepted=run(73,ScanStatus.RUNNING,0,List.of());
        var completed=run(73,ScanStatus.PARTIAL,7,List.of(new AssetScanError("realtime/bad.nc","INVALID_FILENAME","Invalid name")));
        when(scans.submitScan(eq(ScanTrigger.MANUAL),any())).thenReturn(accepted);
        mvc.perform(post("/api/v1/admin/index-scans").session(admin).with(csrf().asHeader()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.scanRunId").value(73))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        when(scans.findScanRun(73)).thenReturn(Optional.of(completed));
        when(scans.findScanRun(74)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/admin/index-scans/73").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath("$.errorCount").value(7))
                .andExpect(jsonPath("$.errors.length()").value(1));
        mvc.perform(get("/api/v1/admin/index-scans/74").session(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/index-scans/0").session(admin)).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/admin/index-scans").session(admin).param("from",time.minusSeconds(3600).toString())
                        .param("to",time.toString()).param("trigger","MANUAL").param("status","PARTIAL").param("page","2").param("pageSize","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.pageSize").value(10));
        verify(scans).searchScanRuns(new ScanRunQuery(time.minusSeconds(3600),time,ScanTrigger.MANUAL,ScanStatus.PARTIAL,new PageRequest(2,10)));
        when(scans.submitScan(eq(ScanTrigger.MANUAL),any())).thenThrow(new BusinessException(ErrorCode.CONFLICT,"SCAN_ALREADY_RUNNING"));
        mvc.perform(post("/api/v1/admin/index-scans").session(admin).with(csrf().asHeader())).andExpect(status().isConflict());
        verifyNoInteractions(commands);
    }

    @Test void deniedAuditKeepsNullAuthorizationAndForwardsEveryFilter() throws Exception {
        var item=new DownloadAuditSummary(new DownloadEventId(8),new UserId(2),"Lab",new AssetId(9),Set.of(code),"fixture.nc",12,"scientific research",null,DownloadStatus.DENIED);
        when(downloads.searchDownloadAudits(any())).thenReturn(new PageResult<>(List.of(item),2,10,11));
        String response=mvc.perform(get("/api/v1/admin/download-audits").session(admin)
                        .param("from",time.minusSeconds(3600).toString()).param("to",time.toString()).param("productCode","PRECIP")
                        .param("userId","2").param("organization","Lab").param("status","DENIED").param("page","2").param("pageSize","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("DENIED"))
                .andExpect(jsonPath("$.total").value(11)).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response).path("items").get(0).get("authorizedAt").isNull()).isTrue();
        verify(downloads).searchDownloadAudits(new DownloadAuditQuery(time.minusSeconds(3600),time,code,new UserId(2),"Lab",DownloadStatus.DENIED,new PageRequest(2,10)));
        assertThat(response).doesNotContain("ipAddress","userAgent","storageKey","relativePath");
    }

    @Test void usersRejectNullCommandsAndForwardRoleStatusAndPagination() throws Exception {
        var user=new UserSummary(new UserId(2),"member@example.test","Lab",UserRole.USER,UserStatus.ACTIVE);
        when(users.searchUsers(any(),any())).thenReturn(new PageResult<>(List.of(user),1,20,1));
        mvc.perform(get("/api/v1/admin/users").session(admin).param("role","USER").param("status","ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].userId").value(2));
        verify(users).searchUsers(eq(new UserQuery(null,null,UserRole.USER,UserStatus.ACTIVE,new PageRequest(1,20))),any());
        mvc.perform(put("/api/v1/admin/users/2/status").session(admin).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/admin/users/0/role").session(admin).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/admin/users/2/role").session(admin).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
        when(users.changeUserStatus(any(),any())).thenReturn(new UserSummary(user.id(),user.email(),user.organization(),user.role(),UserStatus.DISABLED));
        mvc.perform(put("/api/v1/admin/users/2/status").session(admin).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        verify(users).changeUserStatus(eq(new ChangeUserStatusCommand(new UserId(2),UserStatus.DISABLED)),any());
        mvc.perform(get("/api/v1/admin/users").session(admin).param("pageSize","101")).andExpect(status().isUnprocessableEntity());
    }

    private ScanRunView run(long id,ScanStatus status,int errors,List<AssetScanError> detail) {
        return new ScanRunView(id,ScanTrigger.MANUAL,status,new UserId(1),time,status==ScanStatus.RUNNING ? null : time.plusSeconds(10),
                9,2,1,0,0,errors,detail);
    }
    private MockHttpSession session(UserRole role) {
        var session=new MockHttpSession();
        var user=new AuthenticatedUser(new UserId(role==UserRole.ADMIN ? 1 : 2),"user@example.test","Lab",role);
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,AuthorityUtils.createAuthorityList("ROLE_"+role)));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        return session;
    }
}

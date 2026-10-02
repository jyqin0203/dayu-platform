package cn.edu.fudan.dayu.interfaces.rest.v1.download;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.edu.fudan.dayu.download.api.*;
import cn.edu.fudan.dayu.interfaces.rest.v1.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import java.io.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** 校验下载 HTTP、真实流响应和关闭责任；授权规则由 MariaDB 集成测试验证。 */
class DownloadControllerContractTest {
    DownloadAuthorizationService authorization;
    DownloadContentService contents;
    CurrentActorProvider actors;
    MockMvc mvc;
    LocalValidatorFactoryBean validator;
    private static final byte[] BYTES = "file bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @BeforeEach void setup() {
        authorization = mock(DownloadAuthorizationService.class); contents = mock(DownloadContentService.class);
        actors = mock(CurrentActorProvider.class);
        when(actors.required()).thenReturn(new ActorContext(new UserId(1), "lab", UserRole.USER));
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new DownloadController(authorization, contents, actors))
                .setControllerAdvice(new V1ExceptionHandler()).setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)), new ResourceHttpMessageConverter())
                .addFilters(new TraceIdFilter()).build();
    }
    @AfterEach void close() { validator.close(); }
    private static DownloadGrant grant(String name, String location) {
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        return new DownloadGrant(new DownloadEventId(1), name, "application/x-netcdf", BYTES.length,
                location, now, now.plusSeconds(600));
    }
    @Test void authorizationUsesServiceExpiryWithoutExposingInternalLocation() throws Exception {
        when(authorization.authorizeDownload(any(), any(), any())).thenReturn(grant("sample.nc", "/internal-netcdf/sample.nc"));
        mvc.perform(post("/api/v1/downloads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":1,\"purpose\":\"用于验证并研究区域降水变化\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expiresAt").value("2026-10-02T10:10:00Z"))
                .andExpect(jsonPath("$.downloadUrl").value("/api/v1/downloads/1/content"))
                .andExpect(jsonPath("$.internalLocation").doesNotExist());
    }
    @Test void sendsLocalBytesAndClosesInputStream() throws Exception {
        TrackingInput stream = new TrackingInput();
        when(contents.prepareContent(any(), any())).thenReturn(new DownloadContent(grant("sample.nc", "/internal-netcdf/sample.nc"), stream));
        mvc.perform(get("/api/v1/downloads/1/content")).andExpect(status().isOk())
                .andExpect(content().bytes(BYTES)).andExpect(header().string("Content-Length", Integer.toString(BYTES.length)))
                .andExpect(header().doesNotExist("X-Accel-Redirect"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        assertThat(stream.closed).isTrue();
    }
    @Test void nginxModeUsesEncodedUnicodeDispositionAndInternalRedirect() throws Exception {
        when(contents.prepareContent(any(), any())).thenReturn(new DownloadContent(grant("降水资料.nc", "/internal-netcdf/sample.nc"), null));
        var result = mvc.perform(get("/api/v1/downloads/1/content")).andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect", "/internal-netcdf/sample.nc")).andReturn();
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("filename*=UTF-8''");
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }
    @Test void expiredGrantReturns410JsonBeforeAnyTransferHeaders() throws Exception {
        when(contents.prepareContent(any(), any())).thenThrow(new BusinessException(ErrorCode.ASSET_GONE, "下载授权已过期"));
        mvc.perform(get("/api/v1/downloads/1/content")).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ASSET_GONE")).andExpect(header().doesNotExist("X-Accel-Redirect"));
    }
    @Test void missingAndNonPositiveIdsReturn422() throws Exception {
        mvc.perform(get("/api/v1/downloads/0/content")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/downloads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"用于验证并研究区域降水变化\"}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(authorization, contents);
    }
    @Test void unsafeFilenameReturnsSafeErrorAndClosesPreparedStream() throws Exception {
        TrackingInput stream = new TrackingInput();
        when(contents.prepareContent(any(), any())).thenReturn(new DownloadContent(grant("bad\r\n.nc", "/internal-netcdf/a.nc"), stream));
        mvc.perform(get("/api/v1/downloads/1/content")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(stream.closed).isTrue();
    }
    @Test void rejectsHeaderInjectionInInternalLocation() throws Exception {
        when(contents.prepareContent(any(), any())).thenReturn(new DownloadContent(grant("sample.nc", "/internal-netcdf/a.nc\r\nInjected: yes"), null));
        mvc.perform(get("/api/v1/downloads/1/content")).andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Injected"));
    }
    private static class TrackingInput extends ByteArrayInputStream {
        boolean closed;
        TrackingInput() { super(BYTES); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}

package cn.edu.fudan.dayu.copilot.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.edu.fudan.dayu.catalog.api.*;
import cn.edu.fudan.dayu.copilot.api.*;
import cn.edu.fudan.dayu.discovery.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CopilotApplicationServiceTest {
    private final CatalogQueryService catalog = mock(CatalogQueryService.class);
    private final DiscoveryQueryService discovery = mock(DiscoveryQueryService.class);
    private final ChatClient chat = mock(ChatClient.class);
    private final CopilotApplicationService service = new CopilotApplicationService(catalog, discovery, chat, new ObjectMapper());
    private static final ProductCode CODE = new ProductCode("PRECIP");
    private static final String SEARCH = """
            {"productCode":"PRECIP","dataMode":"FORECAST","from":"2026-09-02T06:00:00Z",
             "to":"2026-09-02T09:00:00Z","queryKind":"SCIENTIFIC_ASSET",
             "cycleTime":"2026-09-02T06:00:00Z","leadMinutes":120}
            """;

    @BeforeEach void products() {
        var product = new ProductDetail(new ProductSummary(new ProductId(1), CODE, "降水", "Precipitation", "REPPIC_PRECIP",
                "mm/h", "课题组", "RePPIC-Net", "AGRI", null, ProductStatus.PUBLISHED, 1),
                "降水率科学数据", "Scientific precipitation", false, null,
                List.of(new ProductModePolicy(CODE, DataMode.FORECAST, true, Duration.ofHours(6))), null, Instant.EPOCH, Instant.EPOCH);
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(product));
        when(catalog.findProduct(CODE)).thenReturn(Optional.of(product));
    }
    @Test void searchesRealIndexAndCarriesForecastConstraintsWithoutSendingIdentity() {
        when(chat.complete(any())).thenReturn(SEARCH);
        when(discovery.searchScientificAssets(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 7));
        var actor = new ActorContext(new UserId(999), "private-organization", UserRole.ADMIN);
        var response = service.query(command(null), Optional.of(actor));
        assertThat(response.answer()).contains("7个NC");
        assertThat(response.suggestedActions().get(0).type()).isEqualTo("APPLY_SCIENTIFIC_SEARCH");
        assertThat(response.suggestedActions().get(0).parameters()).containsEntry("leadMinutes", "120")
                .containsEntry("cycleTime", "2026-09-02T06:00:00Z");
        var criteria = ArgumentCaptor.forClass(ScientificAssetQuery.class);
        verify(discovery).searchScientificAssets(criteria.capture());
        assertThat(criteria.getValue().leadMinutes()).isEqualTo(120);
        var prompt = ArgumentCaptor.forClass(ChatClient.ChatRequest.class);
        verify(chat).complete(prompt.capture());
        assertThat(prompt.getValue().userContent()).contains("Asia/Shanghai").doesNotContain("private-organization", "999", "ADMIN");
    }
    @Test void emptyResultsAreNotFabricated() {
        when(chat.complete(any())).thenReturn(SEARCH);
        when(discovery.searchScientificAssets(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
        assertThat(service.query(command(null), Optional.empty()).answer()).contains("没有可用的NC");
    }
    @Test void previewIntentUsesOnlyPreviewQueryAndWhitelistedAction() {
        when(chat.complete(any())).thenReturn(SEARCH.replace("SCIENTIFIC_ASSET", "PREVIEW"));
        when(discovery.listPreviewFrames(any())).thenReturn(List.of());
        var response = service.query(command(null), Optional.empty());
        assertThat(response.answer()).contains("没有可用的WebP");
        assertThat(response.suggestedActions().get(0).type()).isEqualTo("APPLY_PREVIEW_FILTER");
        verify(discovery).listPreviewFrames(any());
        verify(discovery, never()).searchScientificAssets(any());
    }
    @Test void unknownProductsAndMissingTimesAskForClarificationWithoutGuessing() {
        when(chat.complete(any())).thenReturn(SEARCH.replace("PRECIP", "UNKNOWN"));
        var unknown = service.query(command(null), Optional.empty());
        assertThat(unknown.criteria()).isNull(); assertThat(unknown.suggestedActions()).isEmpty();
        when(chat.complete(any())).thenReturn("{\"productCode\":\"PRECIP\",\"queryKind\":\"SCIENTIFIC_ASSET\"}");
        assertThat(service.query(command(null), Optional.empty()).answer()).contains("补充");
        verifyNoInteractions(discovery);
    }
    @Test void promptInjectionUnknownFieldsAndDuplicateKeysCannotBecomeTools() {
        for (String invalid : List.of(SEARCH.replace("120}", "120,\"action\":\"DOWNLOAD_ASSET\"}"),
                SEARCH.replace("SCIENTIFIC_ASSET", "RUN_SCAN"), "{\"productCode\":\"PRECIP\",\"productCode\":\"BT855\"}", "{}{}")) {
            when(chat.complete(any())).thenReturn(invalid);
            assertThatThrownBy(() -> service.query(command(null), Optional.empty())).isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        }
        verifyNoInteractions(discovery);
    }
    @Test void providerFailureUsesOnlyExplicitPageContextForSafeHelp() {
        when(chat.complete(any())).thenThrow(new AiUnavailableException());
        var response = service.query(command(new PageContext(CODE, null, ZoneId.of("Asia/Shanghai"))), Optional.empty());
        assertThat(response.degraded()).isTrue();
        assertThat(response.criteria().queryKind()).isEqualTo("PRODUCT_HELP");
        assertThat(response.suggestedActions().get(0).type()).isEqualTo("OPEN_PRODUCT_DETAILS");
        verifyNoInteractions(discovery);
    }
    @Test void invalidDatesAndRealtimeForecastFieldsAreNeverQueried() {
        for (String invalid : List.of(SEARCH.replace("2026-09-02T09:00:00Z", "2026-09-01T09:00:00Z"),
                SEARCH.replace("2026-09-02T06:00:00Z", "not-a-dateZ"), SEARCH.replace("FORECAST", "REALTIME"))) {
            when(chat.complete(any())).thenReturn(invalid);
            try { service.query(command(null), Optional.empty()); } catch (BusinessException safe) { assertThat(safe.errorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE); }
        }
        verifyNoInteractions(discovery);
    }
    @Test void validatesIanaZoneContextConsistencyAndTotalPromptBudgetBeforeNetwork() {
        var conflicting = new CopilotCommand("查询数据", ZoneId.of("UTC"), new PageContext(CODE, null, ZoneId.of("Asia/Shanghai")), List.of());
        var tooMuch = new CopilotCommand("x".repeat(2000), ZoneId.of("UTC"), null, Collections.nCopies(4, "x".repeat(2000)));
        var secret = new CopilotCommand("api_key=do-not-send-this", ZoneId.of("UTC"), null, List.of());
        var offset = new CopilotCommand("查询数据", ZoneId.of("+08:00"), null, List.of());
        for (var invalid : List.of(conflicting, tooMuch, secret, offset))
            assertThatThrownBy(() -> service.query(invalid, Optional.empty())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(chat);
    }
    @Test void productHelpComesFromCatalogNotModelWrittenAnswers() {
        when(chat.complete(any())).thenReturn("{\"productCode\":\"PRECIP\",\"queryKind\":\"PRODUCT_HELP\"}");
        var response = service.query(command(null), Optional.empty());
        assertThat(response.answer()).contains("降水率科学数据");
        assertThat(response.criteria().from()).isNull();
        verifyNoInteractions(discovery);
    }
    private static CopilotCommand command(PageContext page) {
        return new CopilotCommand("查询北京时间降水预报", ZoneId.of("Asia/Shanghai"), page, List.of());
    }
}

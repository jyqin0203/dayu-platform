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
            {"intent":"SCIENTIFIC_SEARCH","aboutTopic":null,"productCode":"PRECIP","dataMode":"FORECAST",
             "from":"2026-09-02T06:00:00Z","to":"2026-09-02T09:00:00Z",
             "cycleTime":"2026-09-02T06:00:00Z","leadMinutes":120,
             "interpretedZone":"Asia/Shanghai","missingFields":[]}
            """;

    @BeforeEach void products() {
        var product = new ProductDetail(new ProductSummary(new ProductId(1), CODE, "降水", "Precipitation", "REPPIC_PRECIP",
                "mm/h", "课题组", "RePPIC-Net", "AGRI", null, ProductStatus.PUBLISHED, 1),
                "降水率科学数据。本地目录初始化；数据是否可用由后续文件索引决定。", "Scientific precipitation", false, null,
                List.of(new ProductModePolicy(CODE, DataMode.FORECAST, true, Duration.ofHours(6))), null, Instant.EPOCH, Instant.EPOCH);
        when(catalog.listPublishedProductDetails()).thenReturn(List.of(product));
        when(catalog.findProduct(CODE)).thenReturn(Optional.of(product));
    }
    @Test void searchesRealIndexAndCarriesForecastConstraintsWithoutSendingIdentity() {
        when(chat.complete(any())).thenReturn(SEARCH);
        when(discovery.searchScientificAssets(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 7));
        var actor = new ActorContext(new UserId(999), "private-organization", UserRole.ADMIN);
        var response = service.query(command(null), Optional.of(actor));
        assertThat(response.answer()).contains("文件数：7");
        assertThat(response.suggestedActions().get(0).type()).isEqualTo("APPLY_SCIENTIFIC_SEARCH");
        assertThat(response.suggestedActions().get(0).parameters()).containsEntry("leadMinutes", "120")
                .containsEntry("cycleTime", "2026-09-02T06:00:00Z");
        var criteria = ArgumentCaptor.forClass(ScientificAssetQuery.class);
        verify(discovery).searchScientificAssets(criteria.capture());
        assertThat(criteria.getValue().leadMinutes()).isEqualTo(120);
        var prompt = ArgumentCaptor.forClass(ChatClient.ChatRequest.class);
        verify(chat, times(2)).complete(prompt.capture());
        assertThat(prompt.getAllValues().get(0).userContent()).contains("Asia/Shanghai").doesNotContain("private-organization", "999", "ADMIN");
        assertThat(prompt.getAllValues().get(1).userContent()).doesNotContain("private-organization", "999", "ADMIN");
        assertThat(response.criteria().interpretedZone()).isEqualTo("Asia/Shanghai");
    }
    @Test void emptyResultsAreNotFabricated() {
        when(chat.complete(any())).thenReturn(SEARCH);
        when(discovery.searchScientificAssets(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
        assertThat(service.query(command(null), Optional.empty()).answer()).contains("没有可用的 NetCDF");
    }
    @Test void previewIntentUsesOnlyPreviewQueryAndWhitelistedAction() {
        when(chat.complete(any())).thenReturn(SEARCH.replace("SCIENTIFIC_SEARCH", "PREVIEW_SEARCH"));
        when(discovery.listPreviewFrames(any())).thenReturn(List.of());
        var response = service.query(command(null), Optional.empty());
        assertThat(response.answer()).contains("没有可用的 WebP");
        assertThat(response.suggestedActions().get(0).type()).isEqualTo("APPLY_PREVIEW_FILTER");
        verify(discovery).listPreviewFrames(any());
        verify(discovery, never()).searchScientificAssets(any());
    }
    @Test void unknownProductsAndMissingTimesAskForClarificationWithoutGuessing() {
        when(chat.complete(any())).thenReturn(SEARCH.replace("PRECIP", "UNKNOWN"));
        var unknown = service.query(command(null), Optional.empty());
        assertThat(unknown.criteria()).isNull(); assertThat(unknown.suggestedActions()).isEmpty();
        when(chat.complete(any())).thenReturn("{\"intent\":\"SCIENTIFIC_SEARCH\",\"productCode\":\"PRECIP\",\"missingFields\":[\"dataMode\",\"from\",\"to\"]}");
        assertThat(service.query(command(null), Optional.empty()).answer()).contains("实况", "预报");
        verifyNoInteractions(discovery);
    }
    @Test void promptInjectionUnknownFieldsAndDuplicateKeysCannotBecomeTools() {
        for (String invalid : List.of(SEARCH.replace("\"missingFields\":[]", "\"missingFields\":[],\"action\":\"DOWNLOAD_ASSET\""),
                SEARCH.replace("SCIENTIFIC_SEARCH", "RUN_SCAN"), "{\"intent\":\"PRODUCT_HELP\",\"productCode\":\"PRECIP\",\"productCode\":\"BT855\"}", "{}{}")) {
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
    @Test void rateLimitIsNotSwallowedByModelFallback() {
        var limited = new BusinessException(ErrorCode.RATE_LIMITED, "slow down", Map.of("retryAfterSeconds", 17));
        when(chat.complete(any())).thenThrow(limited);
        assertThatThrownBy(() -> service.query(command(null), Optional.empty())).isSameAs(limited);
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
        var tooMuch = new CopilotCommand("x".repeat(2000), ZoneId.of("UTC"), null,
                Collections.nCopies(4, new ConversationMessage("USER", "x".repeat(2000))));
        var secret = new CopilotCommand("api_key=do-not-send-this", ZoneId.of("UTC"), null, List.of());
        var offset = new CopilotCommand("查询数据", ZoneId.of("+08:00"), null, List.of());
        for (var invalid : List.of(conflicting, tooMuch, secret, offset))
            assertThatThrownBy(() -> service.query(invalid, Optional.empty())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(chat);
    }
    @Test void invalidPolishFallsBackToSanitizedCatalogFacts() {
        when(chat.complete(any())).thenReturn(
                "{\"intent\":\"PRODUCT_HELP\",\"productCode\":\"PRECIP\",\"missingFields\":[]}",
                "{\"answer\":\"本地目录初始化后返回的产品。\"}");
        var response = service.query(command(null), Optional.empty());
        assertThat(response.answer()).contains("降水率科学数据").doesNotContain("本地目录初始化", "文件索引", "未说明");
        assertThat(response.degraded()).isTrue();
        assertThat(response.criteria().from()).isNull();
        verifyNoInteractions(discovery);
    }
    @Test void toolFactsArePolishedByASecondModelCallWithoutChangingActions() {
        when(chat.complete(any())).thenReturn(
                "{\"intent\":\"PRODUCT_HELP\",\"productCode\":\"PRECIP\",\"missingFields\":[]}",
                "{\"answer\":\"降水产品用于查看平台中的降水率科学数据，单位为 mm/h，目前提供预报数据。\"}");
        var response = service.query(command(null), Optional.empty());
        assertThat(response.answer()).startsWith("降水产品用于").doesNotContain("本地目录", "未说明");
        assertThat(response.degraded()).isFalse();
        assertThat(response.suggestedActions()).singleElement().satisfies(action ->
                assertThat(action.type()).isEqualTo("OPEN_PRODUCT_DETAILS"));
        var prompts = ArgumentCaptor.forClass(ChatClient.ChatRequest.class);
        verify(chat, times(2)).complete(prompts.capture());
        assertThat(prompts.getAllValues().get(1).systemPrompt()).contains("trusted Java tool", "single key answer");
        assertThat(prompts.getAllValues().get(1).userContent()).contains("降水率科学数据")
                .doesNotContain("本地目录初始化", "文件索引");
    }
    @Test void aboutCapabilitiesAndProductListComeFromTrustedApplicationData() {
        when(chat.complete(any())).thenReturn("{\"intent\":\"ABOUT_DAYU\",\"aboutTopic\":\"SYSTEMS\",\"missingFields\":[]}");
        var about = service.query(command(null), Optional.empty());
        assertThat(about.answer()).contains("DaYu-RTM", "DaYu-CLAS", "DaYu-PRAS", "DaYu-Nowcast");
        assertThat(about.suggestedActions().get(0).type()).isEqualTo("OPEN_ABOUT");
        when(chat.complete(any())).thenReturn("{\"intent\":\"LIST_PRODUCTS\",\"missingFields\":[]}");
        var products = service.query(command(null), Optional.empty());
        assertThat(products.answer()).contains("PRECIP", "降水");
        assertThat(products.suggestedActions().get(0).type()).isEqualTo("OPEN_PRODUCT_CATALOG");
        verifyNoInteractions(discovery);
    }
    @Test void simpleGreetingIsPoliteAndDoesNotExposeInternalReleaseLanguage() {
        var response = service.query(new CopilotCommand("你好", ZoneId.of("Asia/Shanghai"), null, List.of()), Optional.empty());
        assertThat(response.criteria().queryKind()).isEqualTo("GREETING");
        assertThat(response.answer()).contains("你好～", "大禹 AI 助手", "可以帮你")
                .doesNotContain("第一版", "能力边界", "开放式气象知识问答");
        verifyNoInteractions(chat, discovery);
    }
    @Test void modelClassifiedGreetingUsesTheSameTrustedReply() {
        when(chat.complete(any())).thenReturn("{\"intent\":\"GREETING\",\"missingFields\":[]}");
        var response = service.query(new CopilotCommand("早上好呀", ZoneId.of("Asia/Shanghai"), null, List.of()), Optional.empty());
        assertThat(response.answer()).isEqualTo("你好～我是大禹 AI 助手。我可以为你介绍大禹平台和公开产品，也可以帮你整理地图预览或科学数据的检索条件。请问你想了解什么？");
        verifyNoInteractions(discovery);
    }
    @Test void roleBasedRecentMessagesAreSentAsDataForFollowUpResolution() {
        when(chat.complete(any())).thenReturn(SEARCH);
        when(discovery.searchScientificAssets(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0));
        var command = new CopilotCommand("昨天下午", ZoneId.of("Asia/Shanghai"), null, List.of(
                new ConversationMessage("USER", "帮我查降水预报"),
                new ConversationMessage("ASSISTANT", "请补充时间范围")));
        service.query(command, Optional.empty());
        var prompt = ArgumentCaptor.forClass(ChatClient.ChatRequest.class);verify(chat, times(2)).complete(prompt.capture());
        assertThat(prompt.getAllValues().get(0).userContent()).contains("USER", "ASSISTANT", "昨天下午");
    }
    private static CopilotCommand command(PageContext page) {
        return new CopilotCommand("查询北京时间降水预报", ZoneId.of("Asia/Shanghai"), page, List.of());
    }
}

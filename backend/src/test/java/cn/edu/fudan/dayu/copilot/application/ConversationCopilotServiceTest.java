package cn.edu.fudan.dayu.copilot.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.edu.fudan.dayu.copilot.api.*;
import cn.edu.fudan.dayu.shared.kernel.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ConversationCopilotServiceTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final CopilotApplicationService queries = mock(CopilotApplicationService.class);

  CopilotCommand command(String text) {
    return new CopilotCommand(text, ZoneId.of("Asia/Shanghai"), null, List.of());
  }

  @Test
  void keepsConfirmedFieldsAndClearsForecastFieldsWhenSwitchingToRealtime() throws Exception {
    var seen = new ArrayList<JsonNode>();
    when(queries.execute(any(), any()))
        .thenAnswer(
            c -> {
              seen.add(((JsonNode) c.getArgument(0)).deepCopy());
              return new CopilotResponse("conditions", null, "补充时间", List.of(), false);
            });
    var turns = new java.util.concurrent.atomic.AtomicInteger();
    AgentModel model =
        (system, input, tools, delta) -> {
          var tool =
              tools.stream()
                  .filter(t -> t.name().equals("SCIENTIFIC_SEARCH"))
                  .findFirst()
                  .orElseThrow();
          tool.execute()
              .apply(
                  turns.getAndIncrement() == 0
                      ? "{\"productCode\":\"PRECIP\",\"dataMode\":\"FORECAST\",\"leadMinutes\":60}"
                      : "{\"dataMode\":\"REALTIME\"}");
          return "请补充时间";
        };
    var service = new ConversationCopilotService(model, queries, json);
    String id = service.create("owner");
    service.query("owner", id, command("查降水预报"), (k, v) -> {});
    service.query("owner", id, command("改实况"), (k, v) -> {});
    assertThat(seen.get(1).path("productCode").asText()).isEqualTo("PRECIP");
    assertThat(seen.get(1).has("leadMinutes")).isFalse();
  }

  @Test
  void enforcesSessionOwnershipAndClear() {
    var model = mock(AgentModel.class);
    var service = new ConversationCopilotService(model, queries, json);
    String id = service.create("a");
    assertThatThrownBy(() -> service.query("b", id, command("hi"), (k, v) -> {}))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.clear("b", id)).isInstanceOf(BusinessException.class);
    service.clear("a", id);
    assertThatThrownBy(() -> service.query("a", id, command("hi"), (k, v) -> {}))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(model);
  }

  @Test
  void rejectsCredentialsBeforeModel() {
    var model = mock(AgentModel.class);
    var service = new ConversationCopilotService(model, queries, json);
    String id = service.create("a");
    assertThatThrownBy(
            () -> service.query("a", id, command("password=private-fixture"), (k, v) -> {}))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(model);
  }

  @Test
  void rejectsOverlappingTurnsAndClearedInflightResults() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    AgentModel model =
        (a, b, c, d) -> {
          entered.countDown();
          try {
            release.await(3, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "late";
        };
    var service = new ConversationCopilotService(model, queries, json);
    String id = service.create("a");
    var pool = Executors.newSingleThreadExecutor();
    try {
      var f = pool.submit(() -> service.query("a", id, command("hi"), (k, v) -> {}));
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> service.query("a", id, command("hi"), (k, v) -> {}))
          .isInstanceOf(BusinessException.class);
      service.clear("a", id);
      release.countDown();
      assertThatThrownBy(() -> f.get()).isInstanceOf(ExecutionException.class);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void onlyExplicitUnambiguousModesAreAccepted() {
    assertThat(ConversationCopilotService.explicitMode("PRECIP forecast")).isEqualTo("FORECAST");
    assertThat(ConversationCopilotService.explicitMode("改成实况，不要预报")).isEqualTo("REALTIME");
    assertThat(ConversationCopilotService.explicitMode("实况和预报有什么区别")).isNull();
    assertThat(ConversationCopilotService.explicitMode("查PRECIP")).isNull();
  }

  @Test
  void invalidToolPatchDoesNotPoisonLaterTurns() throws Exception {
    var seen = new ArrayList<JsonNode>();
    when(queries.execute(any(), any()))
        .thenAnswer(
            c -> {
              seen.add(((JsonNode) c.getArgument(0)).deepCopy());
              return new CopilotResponse("conditions", null, "补充时间", List.of(), false);
            });
    var turn = new java.util.concurrent.atomic.AtomicInteger();
    AgentModel model =
        (system, input, tools, delta) -> {
          var tool =
              tools.stream()
                  .filter(t -> t.name().equals("SCIENTIFIC_SEARCH"))
                  .findFirst()
                  .orElseThrow();
          if (turn.getAndIncrement() == 0)
            assertThat(tool.execute().apply("{\"productCode\":\"PRECIP\",\"leadMinutes\":\"bad\"}"))
                .contains("error");
          else tool.execute().apply("{}");
          return "clarify";
        };
    var service = new ConversationCopilotService(model, queries, json);
    String id = service.create("a");
    service.query("a", id, command("查数据"), (k, v) -> {});
    service.query("a", id, command("继续"), (k, v) -> {});
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).has("productCode")).isFalse();
  }

  @Test
  void missingTimeCannotBeInventedByModel() throws Exception {
    var seen = new ArrayList<JsonNode>();
    when(queries.execute(any(), any()))
        .thenAnswer(
            c -> {
              seen.add(((JsonNode) c.getArgument(0)).deepCopy());
              return new CopilotResponse("conditions", null, "补充时间", List.of(), false);
            });
    AgentModel model =
        (system, input, tools, delta) -> {
          tools.stream()
              .filter(t -> t.name().equals("SCIENTIFIC_SEARCH"))
              .findFirst()
              .orElseThrow()
              .execute()
              .apply(
                  "{\"productCode\":\"PRECIP\",\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}");
          return "clarify";
        };
    var service = new ConversationCopilotService(model, queries, json);
    service.query("a", service.create("a"), command("查PRECIP预报科学数据"), (k, v) -> {});
    assertThat(seen.get(0).has("from")).isFalse();
    assertThat(seen.get(0).has("to")).isFalse();
  }

  @Test
  void latestScientificQueryUsesActualDiscoveredCycleRange() throws Exception {
    var seen = new ArrayList<JsonNode>();
    when(queries.execute(any(), any()))
        .thenAnswer(
            c -> {
              seen.add(((JsonNode) c.getArgument(0)).deepCopy());
              return new CopilotResponse(
                  "conditions",
                  new InterpretedCriteria(
                      new ProductCode("PRECIP"), null, null, null, "FORECAST_CYCLES"),
                  "cycles",
                  List.of(),
                  false);
            });
    Instant cycle = Instant.parse("2026-09-08T03:00:00Z"), last = cycle.plusSeconds(3600);
    when(queries.scientificCycles(any()))
        .thenReturn(
            List.of(
                new cn.edu.fudan.dayu.discovery.api.ForecastCycleSummary(
                    cycle, cycle, last, Set.of(0, 60), true)));
    AgentModel model =
        (system, input, tools, delta) -> {
          for (String name : List.of("FORECAST_CYCLES", "SCIENTIFIC_SEARCH"))
            tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow()
                .execute()
                .apply("{\"productCode\":\"PRECIP\"}");
          return "files";
        };
    var service = new ConversationCopilotService(model, queries, json);
    service.query("a", service.create("a"), command("查PRECIP最新预报批次科学数据"), (k, v) -> {});
    assertThat(seen.get(1).path("cycleTime").asText()).isEqualTo(cycle.toString());
    assertThat(seen.get(1).path("to").asText()).isEqualTo(last.toString());
  }

  @Test
  void resolvesDayChangesWithConfirmedYearAndExplicitZone() throws Exception {
    var state = json.readTree("{\"from\":\"2026-09-01T16:00:00Z\"}");
    assertThat(
            ConversationCopilotService.explicitDayRange(
                "改成9月3日，其他不变", state, ZoneId.of("Asia/Shanghai"))[0])
        .isEqualTo(Instant.parse("2026-09-02T16:00:00Z"));
    assertThat(
            ConversationCopilotService.explicitDayRange("2026年9月2日全天", state, ZoneId.of("UTC"))[0])
        .isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
    assertThatThrownBy(
            () ->
                ConversationCopilotService.explicitDayRange(
                    "2026年2月30日全天", state, ZoneId.of("UTC")))
        .isInstanceOf(BusinessException.class);
    assertThat(ConversationCopilotService.explicitDayRange("9月2日至9月3日全天", state, ZoneId.of("UTC")))
        .isNull();
  }

  @Test
  void dateOnlyChangePreservesTimeOfDay() throws Exception {
    var state =
        json.readTree("{\"from\":\"2026-09-02T04:00:00Z\",\"to\":\"2026-09-02T10:00:00Z\"}");
    var range =
        ConversationCopilotService.explicitDayRange(
            "改成9月3日，其他不变", state, ZoneId.of("Asia/Shanghai"));
    assertThat(range[0]).isEqualTo(Instant.parse("2026-09-03T04:00:00Z"));
    assertThat(range[1]).isEqualTo(Instant.parse("2026-09-03T10:00:00Z"));
  }
}

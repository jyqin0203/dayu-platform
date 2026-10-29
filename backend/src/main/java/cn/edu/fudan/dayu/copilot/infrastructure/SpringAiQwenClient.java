package cn.edu.fudan.dayu.copilot.infrastructure;

import cn.edu.fudan.dayu.copilot.application.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/** Spring AI transport for the existing Qwen endpoint; no automatic paid retries. */
public final class SpringAiQwenClient implements AiClient, AgentModel {
  private final OpenAiChatModel model;
  private final int limit;
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(SpringAiQwenClient.class);

  @org.springframework.beans.factory.annotation.Value(
      "${dayu.copilot.rate-limit.requests-per-minute:30}")
  private int rpm = 30;

  private long window;
  private int used;

  private synchronized void reserve() {
    long now = System.currentTimeMillis();
    if (now - window >= 60000) {
      window = now;
      used = 0;
    }
    if (++used > rpm)
      throw new cn.edu.fudan.dayu.shared.kernel.BusinessException(
          cn.edu.fudan.dayu.shared.kernel.ErrorCode.RATE_LIMITED, "模型调用额度已用完，请稍后重试");
  }

  public SpringAiQwenClient(URI base, String key, String name, Duration timeout, int limit) {
    if (!"https".equals(base.getScheme())
        && !("http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost())))
      throw new IllegalArgumentException("HTTPS endpoint required");
    if (base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null) throw new IllegalArgumentException("Invalid endpoint");
    this.limit = limit;
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build());
    factory.setReadTimeout(timeout);
    var api =
        OpenAiApi.builder()
            .baseUrl(base.toString().replaceAll("/+$", ""))
            .completionsPath("/chat/completions")
            .apiKey(key == null || key.isBlank() ? "disabled" : key)
            .restClientBuilder(RestClient.builder().requestFactory(factory))
            .build();
    model =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(name)
                    .temperature(0.0)
                    .extraBody(java.util.Map.of("enable_thinking", false))
                    .maxTokens(1000)
                    .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                    .build())
            .build();
  }

  @Override
  public String provider() {
    return "spring-ai-qwen";
  }

  @Override
  public String complete(ChatRequest request) {
    try {
      var response =
          model.call(
              new Prompt(
                  List.of(
                      new SystemMessage(request.systemPrompt()),
                      new UserMessage(request.userContent()))));
      var result = response.getResult();
      if (result == null || !"stop".equalsIgnoreCase(result.getMetadata().getFinishReason()))
        throw new AiUnavailableException();
      String text = result.getOutput().getText();
      if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limit)
        throw new AiUnavailableException();
      return text;
    } catch (Exception failure) {
      throw new AiUnavailableException();
    }
  }

  private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(2);

  /**
   * Manual tool loop caps paid calls, rejects repetitions, and streams only the final model answer.
   */
  @Override
  public String run(
      String system,
      String input,
      List<AgentModel.Tool> tools,
      java.util.function.Consumer<String> delta) {
    if (!permits.tryAcquire())
      throw new cn.edu.fudan.dayu.shared.kernel.BusinessException(
          cn.edu.fudan.dayu.shared.kernel.ErrorCode.RATE_LIMITED, "AI繁忙，请稍后重试");
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    try {
      var messages = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
      messages.add(new SystemMessage(system));
      messages.add(new UserMessage(input));
      var callbacks =
          tools.stream()
              .map(
                  t ->
                      org.springframework.ai.tool.function.FunctionToolCallback.builder(
                              t.name(),
                              (java.util.function.Function<java.util.Map, String>)
                                  (v -> {
                                    throw new IllegalStateException("Manual tools only");
                                  }))
                          .description(t.description())
                          .inputType(java.util.Map.class)
                          .inputSchema(t.schema())
                          .build())
              .toList();
      var options =
          OpenAiChatOptions.builder()
              .toolCallbacks(new java.util.ArrayList<>(callbacks))
              .toolChoice("required")
              .internalToolExecutionEnabled(false)
              .responseFormat(new ResponseFormat(ResponseFormat.Type.TEXT, null))
              .maxTokens(1200)
              .build();
      var seen = new java.util.HashSet<String>();
      int count = 0;
      for (int round = 0; round < 2; round++) {
        check(deadline);
        reserve();
        var response = model.call(new Prompt(messages, options));
        var result = response.getResult();
        if (result == null
            || !java.util.Set.of("stop", "tool_calls")
                .contains(
                    String.valueOf(result.getMetadata().getFinishReason())
                        .toLowerCase(java.util.Locale.ROOT))) throw new AiUnavailableException();
        LOG.info(
            "Copilot model stage=tools round={} usage={}",
            round + 1,
            response.getMetadata().getUsage());
        var output = result.getOutput();
        messages.add(output);
        if (!output.hasToolCalls()) break;
        var responses = new java.util.ArrayList<ToolResponseMessage.ToolResponse>();
        for (var call : output.getToolCalls()) {
          check(deadline);
          if (++count > 5 || !seen.add(call.name() + call.arguments()))
            throw new IllegalArgumentException("Tool budget exceeded");
          var tool =
              tools.stream().filter(t -> t.name().equals(call.name())).findFirst().orElseThrow();
          String value = tool.execute().apply(call.arguments());
          if (value.length() > 16000) throw new IllegalArgumentException("Tool result too large");
          responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), value));
        }
        messages.add(ToolResponseMessage.builder().responses(responses).build());
        // Only a batch-discovery result can require a dependent query round.
        if (output.getToolCalls().stream().noneMatch(t -> t.name().equals("FORECAST_CYCLES")))
          break;
      }
      check(deadline);
      var finalOptions =
          OpenAiChatOptions.builder()
              .internalToolExecutionEnabled(false)
              .responseFormat(new ResponseFormat(ResponseFormat.Type.TEXT, null))
              .maxTokens(1200)
              .build();
      messages.add(
          new UserMessage(
              "根据工具返回的answer作简洁中文回答。只询问工具明确要求补充的条件；起报时间和预报时效都是可选条件，不得要求用户必须提供。不要增加任何事实或条件。不要再调用工具。"));
      var answer = new StringBuilder();
      var finished = new java.util.concurrent.atomic.AtomicBoolean();
      reserve();
      model.stream(new Prompt(messages, finalOptions))
          .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
          .doOnNext(
              response -> {
                check(deadline);
                var result = response.getResult();
                if (result != null) {
                  if ("stop".equalsIgnoreCase(result.getMetadata().getFinishReason()))
                    finished.set(true);
                  String part = result.getOutput().getText();
                  if (part != null) {
                    answer.append(part);
                    if (answer.length() > 6000)
                      throw new IllegalArgumentException("Answer too long");
                    delta.accept(part);
                  }
                }
              })
          .blockLast(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
      if (!finished.get()) throw new AiUnavailableException();
      return answer.toString();
    } finally {
      permits.release();
    }
  }

  private static void check(long deadline) {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
      throw new AiUnavailableException();
  }
}

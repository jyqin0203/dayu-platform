package cn.edu.fudan.dayu.copilot.infrastructure;

import static org.assertj.core.api.Assertions.*;

import cn.edu.fudan.dayu.copilot.application.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpringAiQwenClientTest {
  @Test
  void usesCompatibleEndpointAndDoesNotRetryErrors() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        e -> {
          calls.incrementAndGet();
          String body = new String(e.getRequestBody().readAllBytes());
          assertThat(body).contains("fixture-model", "json_object");
          e.sendResponseHeaders(503, -1);
          e.close();
        });
    server.start();
    try {
      var client =
          new SpringAiQwenClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
              "fixture-key",
              "fixture-model",
              Duration.ofSeconds(2),
              65536);
      assertThatThrownBy(() -> client.complete(new ChatClient.ChatRequest("JSON", "hi")))
          .isInstanceOf(AiUnavailableException.class);
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void executesWhitelistedToolAndStreamsFinalAnswer() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    var tools = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        e -> {
          String request = new String(e.getRequestBody().readAllBytes());
          boolean stream = request.contains("\"stream\":true");
          calls.incrementAndGet();
          String body;
          if (!stream) {
            body =
                "{\"id\":\"fixture\",\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"SCIENTIFIC_SEARCH\",\"arguments\":\"{}\"}}]}}]}";
            e.getResponseHeaders().set("Content-Type", "application/json");
          } else {
            body =
                "data:"
                    + " {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"找到数据\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            e.getResponseHeaders().set("Content-Type", "text/event-stream");
          }
          byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          e.sendResponseHeaders(200, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.start();
    try {
      var client =
          new SpringAiQwenClient(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
              "fixture-key",
              "fixture-model",
              Duration.ofSeconds(2),
              65536);
      var chunks = new ArrayList<String>();
      String answer =
          client.run(
              "query",
              "hi",
              List.of(
                  new AgentModel.Tool(
                      "SCIENTIFIC_SEARCH",
                      "query",
                      "{\"type\":\"object\",\"properties\":{}}",
                      args -> {
                        tools.incrementAndGet();
                        return "{\"count\":2}";
                      })),
              chunks::add);
      assertThat(answer).isEqualTo("找到数据");
      assertThat(chunks).contains("找到数据");
      assertThat(calls.get()).isEqualTo(2);
      assertThat(tools.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsUnknownOrExcessiveToolCallsWithoutExecutingThem() throws Exception {
    for (boolean excessive : List.of(false, true)) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      var count = new AtomicInteger();
      var executed = new AtomicInteger();
      server.createContext(
          "/v1/chat/completions",
          e -> {
            e.getRequestBody().readAllBytes();
            count.incrementAndGet();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var calls = new ArrayList<Map<String, Object>>();
            for (int n = 0; n < (excessive ? 6 : 1); n++)
              calls.add(
                  Map.of(
                      "id",
                      "t" + n,
                      "type",
                      "function",
                      "function",
                      Map.of(
                          "name",
                          excessive ? "SCIENTIFIC_SEARCH" : "EXECUTE_SQL",
                          "arguments",
                          "{\"n\":" + n + "}")));
            byte[] body =
                mapper.writeValueAsBytes(
                    Map.of(
                        "id",
                        "fixture",
                        "choices",
                        List.of(
                            Map.of(
                                "index",
                                0,
                                "finish_reason",
                                "tool_calls",
                                "message",
                                Map.of("role", "assistant", "tool_calls", calls)))));
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      server.start();
      try {
        var client =
            new SpringAiQwenClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "fixture-key",
                "fixture-model",
                Duration.ofSeconds(2),
                65536);
        assertThatThrownBy(
                () ->
                    client.run(
                        "query",
                        "hi",
                        List.of(
                            new AgentModel.Tool(
                                "SCIENTIFIC_SEARCH",
                                "query",
                                "{\"type\":\"object\",\"properties\":{}}",
                                args -> {
                                  executed.incrementAndGet();
                                  return "{}";
                                })),
                        part -> {}))
            .isInstanceOf(RuntimeException.class);
        assertThat(count.get()).isEqualTo(1);
        assertThat(executed.get()).isEqualTo(excessive ? 5 : 0);
      } finally {
        server.stop(0);
      }
    }
  }
}

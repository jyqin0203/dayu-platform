package cn.edu.fudan.dayu.copilot.infrastructure;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.copilot.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

/** Loopback fake server only: no live key, cloud connection, account or billable inference. */
class QwenAiClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();
    private final ChatClient.ChatRequest prompt = new ChatClient.ChatRequest("Output JSON only", "query");
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    @Test void usesConfiguredBaseModelAndBearerWithJsonOutputMode() throws Exception {
        var captured = new AtomicReference<String>(); var authorization = new AtomicReference<String>();
        server.createContext("/workspace/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = response("{\"queryKind\":\"PRODUCT_HELP\"}");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        String output = client(Duration.ofSeconds(2), 65536, "test-only-token").complete(prompt);
        assertThat(output).contains("PRODUCT_HELP");
        assertThat(authorization.get()).isEqualTo("Bearer test-only-token");
        var body = json.readTree(captured.get());
        assertThat(body.path("model").asText()).isEqualTo("test-qwen-model");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
    }
    @Test void nonSuccessAndToolCallsNeverLeakProviderBody() {
        server.createContext("/workspace/v1/chat/completions", exchange -> {
            byte[] response = "sensitive-upstream-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 65536, "test-only-token").complete(prompt))
                .isInstanceOf(AiUnavailableException.class).hasMessage("AI provider temporarily unavailable");
    }
    @Test void bodySizeIsBoundedEvenWhenProviderStreamsWithoutContentLength() {
        server.createContext("/workspace/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try { exchange.getResponseBody().write(new byte[8192]); } finally { exchange.close(); }
        });
        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 1024, "test-only-token").complete(prompt)).isInstanceOf(AiUnavailableException.class);
    }
    @Test void deadlineAlsoCoversDelayedResponseBody() {
        server.createContext("/workspace/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try { exchange.getResponseBody().write(' '); exchange.getResponseBody().flush(); Thread.sleep(1500); }
            catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var adapter = client(Duration.ofMillis(200), 65536, "test-only-token");
        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter.complete(prompt)).isInstanceOf(AiUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
    @Test void absentKeyNeverMakesAnHttpRequest() {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/workspace/v1/chat/completions", exchange -> {
            requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 65536, "").complete(prompt)).isInstanceOf(AiUnavailableException.class);
        assertThat(requests.get()).isZero();
    }
    @Test void rejectsUnsafeConfigurationAndUnexpectedModelToolResponse() {
        assertThatThrownBy(() -> new QwenAiClient(json, URI.create("http://example.test/v1"), "", "qwen", Duration.ofSeconds(2), 65536))
                .isInstanceOf(IllegalArgumentException.class);
        server.createContext("/workspace/v1/chat/completions", exchange -> {
            byte[] bytes = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[],\"content\":\"{}\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 65536, "test-only-token").complete(prompt)).isInstanceOf(AiUnavailableException.class);
    }
    private QwenAiClient client(Duration timeout, int limit, String key) {
        return new QwenAiClient(json, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/workspace/v1"),
                key, "test-qwen-model", timeout, limit);
    }
    private byte[] response(String content) throws java.io.IOException {
        return json.writeValueAsBytes(Map.of("choices", java.util.List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
    }
}

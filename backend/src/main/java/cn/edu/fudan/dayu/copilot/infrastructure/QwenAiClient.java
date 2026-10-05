package cn.edu.fudan.dayu.copilot.infrastructure;

import cn.edu.fudan.dayu.copilot.application.AiClient;
import cn.edu.fudan.dayu.copilot.application.AiUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bailian OpenAI-compatible HTTP adapter. No OpenAI SDK, redirects, retries, or paid startup probe. */
public final class QwenAiClient implements AiClient {
    private static final Logger LOG = LoggerFactory.getLogger(QwenAiClient.class);
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final Duration timeout;
    private final int maxResponseBytes;

    public QwenAiClient(ObjectMapper json, URI baseUrl, String key, String model, Duration timeout, int maxResponseBytes) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0
                || maxResponseBytes < 1024 || maxResponseBytes > 1024 * 1024)
            throw new IllegalArgumentException("Invalid AI transport limits");
        boolean localHttp = "http".equals(baseUrl.getScheme()) && baseUrl.getHost() != null
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUrl.getHost());
        if ((!"https".equals(baseUrl.getScheme()) && !localHttp) || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null)
            throw new IllegalArgumentException("AI base URL must be HTTPS (HTTP allowed only for loopback tests)");
        this.endpoint = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions");
        this.json = json; this.key = key; this.model = model; this.timeout = timeout; this.maxResponseBytes = maxResponseBytes;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public String provider() { return "qwen"; }

    /** Total deadline covers headers and body; bounded subscriber cancels oversized responses immediately. */
    @Override public String complete(ChatRequest request) {
        if (key == null || key.isBlank() || model == null || model.isBlank()) {
            LOG.warn("Qwen request rejected before network call: provider configuration is incomplete");
            throw new AiUnavailableException();
        }
        CompletableFuture<HttpResponse<byte[]>> response = null;
        try {
            byte[] body = json.writeValueAsBytes(Map.of("model", model, "stream", false, "enable_thinking", false,
                    "response_format", Map.of("type", "json_object"), "max_tokens", 1000,
                    "messages", List.of(Map.of("role", "system", "content", request.systemPrompt()),
                            Map.of("role", "user", "content", request.userContent()))));
            if (body.length > 131072) throw new AiUnavailableException();
            var httpRequest = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            response = http.sendAsync(httpRequest, info -> new LimitedBody(maxResponseBytes));
            var result = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result.statusCode() != 200) {
                LOG.warn("Qwen request failed with HTTP status {}", result.statusCode());
                throw new AiUnavailableException();
            }
            var tree = json.readTree(result.body());
            var message = tree.path("choices").path(0).path("message");
            if (message.has("tool_calls") || !message.path("content").isTextual()
                    || !tree.path("choices").path(0).path("finish_reason").asText().equals("stop")) {
                LOG.warn("Qwen response rejected because its completion shape is not allowed");
                throw new AiUnavailableException();
            }
            return message.path("content").asText();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Qwen request interrupted");
            throw new AiUnavailableException();
        } catch (TimeoutException timeoutFailure) {
            LOG.warn("Qwen request exceeded the configured deadline");
            throw new AiUnavailableException();
        } catch (ExecutionException transportFailure) {
            LOG.warn("Qwen transport failed: {}", safeType(transportFailure.getCause()));
            throw new AiUnavailableException();
        } catch (AiUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception unavailable) {
            LOG.warn("Qwen response processing failed: {}", safeType(unavailable));
            throw new AiUnavailableException();
        } finally {
            if (response != null && !response.isDone()) response.cancel(true);
        }
    }

    private static String safeType(Throwable failure) {
        return failure == null ? "unknown" : failure.getClass().getSimpleName();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new AiUnavailableException()); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new AiUnavailableException()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

package cn.edu.fudan.dayu.copilot.infrastructure;

import cn.edu.fudan.dayu.copilot.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** Deployment-only provider configuration; secrets are injected but never included in prompt or logs. */
@Configuration
@Profile("!skeleton")
public class CopilotClientConfiguration {
    @Bean AiClient qwenAiClient(ObjectMapper mapper,
            @Value("${dayu.copilot.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") URI baseUrl,
            @Value("${dayu.copilot.qwen.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${dayu.copilot.qwen.model:qwen-plus}") String model,
            @Value("${dayu.copilot.timeout:10s}") Duration timeout,
            @Value("${dayu.copilot.max-response-bytes:65536}") int maxResponseBytes) {
        return new QwenAiClient(mapper, baseUrl, apiKey, model, timeout, maxResponseBytes);
    }
    @Bean @Primary ChatClient chatClient(List<AiClient> clients,
            @Value("${dayu.copilot.enabled:false}") boolean enabled,
            @Value("${dayu.copilot.provider:qwen}") String provider,
            @Value("${dayu.copilot.rate-limit.requests-per-minute:30}") int requestsPerMinute,
            @Value("${dayu.copilot.rate-limit.max-concurrent-calls:2}") int maxConcurrentCalls) {
        ChatClient routing = new RoutingChatClient(enabled, provider, clients);
        // 禁用时保持原有 AI_UNAVAILABLE 行为；启用后所有真实 provider 调用共享同一限制器。
        return enabled ? new RateLimitedChatClient(routing, requestsPerMinute, maxConcurrentCalls) : routing;
    }
}

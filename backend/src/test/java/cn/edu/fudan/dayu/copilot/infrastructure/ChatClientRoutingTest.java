package cn.edu.fudan.dayu.copilot.infrastructure;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.copilot.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class ChatClientRoutingTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(
                    org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(CopilotClientConfiguration.class, FakeClients.class);
    @Test void providerPropertySelectsTwoDifferentRegisteredClients() {
        for (String provider : List.of("alpha", "beta")) {
            contexts.withPropertyValues("dayu.copilot.enabled=true", "dayu.copilot.provider=" + provider)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(ChatClient.class).complete(new ChatClient.ChatRequest("JSON", "query")))
                                .isEqualTo(provider);
                    });
        }
    }
    @Test void disabledDefaultAndUnknownProviderStartWithoutAnyModelCall() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(ChatClient.class).complete(new ChatClient.ChatRequest("JSON", "query")))
                    .isInstanceOf(AiUnavailableException.class);
        });
        contexts.withPropertyValues("dayu.copilot.enabled=true", "dayu.copilot.provider=missing").run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(ChatClient.class).complete(new ChatClient.ChatRequest("JSON", "query")))
                    .isInstanceOf(AiUnavailableException.class);
        });
    }
    @TestConfiguration(proxyBeanMethods = false) static class FakeClients {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean AiClient alpha() { return fake("alpha"); }
        @Bean AiClient beta() { return fake("beta"); }
        private static AiClient fake(String id) {
            return new AiClient() {
                public String provider() { return id; }
                public String complete(ChatRequest request) { return id; }
            };
        }
    }
}

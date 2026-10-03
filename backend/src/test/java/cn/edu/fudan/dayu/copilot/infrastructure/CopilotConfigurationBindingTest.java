package cn.edu.fudan.dayu.copilot.infrastructure;

import static org.assertj.core.api.Assertions.*;
import cn.edu.fudan.dayu.copilot.application.AiClient;
import cn.edu.fudan.dayu.copilot.application.ChatClient;
import cn.edu.fudan.dayu.copilot.application.RateLimitedChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/** Checks real configuration wiring with a fake key; never calls complete() or an external model. */
class CopilotConfigurationBindingTest {
    @Test void bindsConfiguredModelKeyAndEnabledClientWithoutMakingRequests() {
        new ApplicationContextRunner().withUserConfiguration(CopilotClientConfiguration.class)
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .withPropertyValues("dayu.copilot.enabled=true","dayu.copilot.qwen.model=qwen3.8-flash",
                        "DASHSCOPE_API_KEY=configuration-test-only")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatClient.class)).isInstanceOf(RateLimitedChatClient.class);
                    var provider=context.getBean(AiClient.class);
                    assertThat(ReflectionTestUtils.getField(provider,"model")).isEqualTo("qwen3.8-flash");
                    assertThat(ReflectionTestUtils.getField(provider,"key")).isEqualTo("configuration-test-only");
                });
    }
}

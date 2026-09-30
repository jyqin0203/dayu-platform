package cn.edu.fudan.dayu.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.fudan.dayu.copilot.api.CopilotCommand;
import cn.edu.fudan.dayu.copilot.api.CopilotService;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 验证 Copilot 只根据 Catalog 和 Discovery 的真实 Mock 结果生成回答。
 */
@SpringBootTest
@ActiveProfiles("skeleton")
class CopilotJourneyTest {
    @Autowired CopilotService copilot;

    /** 验证降水查询回答中包含 Discovery 返回的索引结果数量。 */
    @Test
    void copilotAnswersFromDiscoveryResult() {
        var response = copilot.query(new CopilotCommand("查询PRECIP预报数据", ZoneId.of("Asia/Shanghai"),
                null, List.of()), Optional.empty());
        assertThat(response.degraded()).isFalse();
        assertThat(response.answer()).contains("1个真实索引结果");
    }
}

package cn.edu.fudan.dayu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 验证 skeleton Profile 下的 Spring 应用上下文能够成功启动。
 */
@SpringBootTest
@ActiveProfiles("skeleton")
class DayuApplicationTest {

    /** 验证所有空骨架组件可以被 Spring 正确创建和连接。 */
    @Test
    void contextLoads() {
    }
}

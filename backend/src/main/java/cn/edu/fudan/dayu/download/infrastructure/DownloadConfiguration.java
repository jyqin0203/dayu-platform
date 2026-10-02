package cn.edu.fudan.dayu.download.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.*;

/** 独立命名的时钟便于过期边界测试，不覆盖其他模块的 Clock。 */
@Configuration
@Profile("!skeleton")
public class DownloadConfiguration {
    @Bean("downloadClock") public Clock downloadClock() { return Clock.systemUTC(); }
}

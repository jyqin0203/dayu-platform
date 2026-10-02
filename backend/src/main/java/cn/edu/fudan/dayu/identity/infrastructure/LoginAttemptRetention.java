package cn.edu.fudan.dayu.identity.infrastructure;

import cn.edu.fudan.dayu.identity.application.UserRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded cleanup of login security metadata; deployment can configure retention and cleanup delay. */
@Component
@Profile("!skeleton")
@EnableScheduling
public class LoginAttemptRetention {
    private final UserRepository users;
    private final Duration retention;
    public LoginAttemptRetention(UserRepository users,
            @Value("${dayu.identity.attempt-retention:30d}") Duration retention,
            @Value("${dayu.identity.login-window:15m}") Duration window) {
        if (retention.compareTo(window) < 0) throw new IllegalArgumentException("Retention must cover login window");
        this.users = users; this.retention = retention;
    }
    @Scheduled(fixedDelayString = "${dayu.identity.attempt-cleanup-delay:PT1H}", initialDelayString = "PT1H")
    public void cleanup() { users.purgeAttemptsBefore(Instant.now().minus(retention)); }
}

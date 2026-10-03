package cn.edu.fudan.dayu.copilot.application;

import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 单实例、全局的模型调用保护器。
 *
 * <p>固定一分钟窗口只统计准备调用下游模型的请求；并发上限使用非阻塞许可，
 * 不让 HTTP 工作线程排队等待付费模型。该限制器不区分匿名用户，也不提供多实例共享配额。</p>
 */
public final class RateLimitedChatClient implements ChatClient {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final ChatClient delegate;
    private final int requestsPerMinute;
    private final Semaphore inFlight;
    private final Clock clock;
    private final Object windowLock = new Object();
    private Instant windowStartedAt;
    private int requestsInWindow;

    public RateLimitedChatClient(ChatClient delegate, int requestsPerMinute, int maxConcurrentCalls) {
        this(delegate, requestsPerMinute, maxConcurrentCalls, Clock.systemUTC());
    }

    /** 包级构造器允许测试使用确定性时钟，不把时钟暴露为部署配置。 */
    RateLimitedChatClient(ChatClient delegate, int requestsPerMinute, int maxConcurrentCalls, Clock clock) {
        if (requestsPerMinute < 1 || requestsPerMinute > 10_000
                || maxConcurrentCalls < 1 || maxConcurrentCalls > 100) {
            throw new IllegalArgumentException("Invalid Copilot model call limits");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.requestsPerMinute = requestsPerMinute;
        this.inFlight = new Semaphore(maxConcurrentCalls);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String complete(ChatRequest request) {
        if (!inFlight.tryAcquire()) throw limited(1);
        try {
            reserveRequest();
            return delegate.complete(request);
        } finally {
            // RuntimeException、Error 和正常返回都必须释放在途许可。
            inFlight.release();
        }
    }

    private void reserveRequest() {
        synchronized (windowLock) {
            Instant now = clock.instant();
            if (windowStartedAt == null || now.isBefore(windowStartedAt)
                    || !now.isBefore(windowStartedAt.plus(WINDOW))) {
                windowStartedAt = now;
                requestsInWindow = 0;
            }
            if (requestsInWindow >= requestsPerMinute) {
                long millis = Duration.between(now, windowStartedAt.plus(WINDOW)).toMillis();
                throw limited(Math.max(1, (millis + 999) / 1000));
            }
            requestsInWindow++;
        }
    }

    private static BusinessException limited(long retryAfterSeconds) {
        return new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后重试",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}

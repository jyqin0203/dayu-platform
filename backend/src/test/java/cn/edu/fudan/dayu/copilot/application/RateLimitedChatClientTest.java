package cn.edu.fudan.dayu.copilot.application;

import static org.assertj.core.api.Assertions.*;

import cn.edu.fudan.dayu.shared.kernel.BusinessException;
import cn.edu.fudan.dayu.shared.kernel.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

class RateLimitedChatClientTest {
    private static final ChatClient.ChatRequest REQUEST = new ChatClient.ChatRequest("system", "query");

    @Test void fixedWindowRejectsBeforeModelAndResetsWithDeterministicClock() {
        var clock = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));
        var calls = new AtomicInteger();
        var client = new RateLimitedChatClient(request -> "call-" + calls.incrementAndGet(), 2, 1, clock);

        assertThat(client.complete(REQUEST)).isEqualTo("call-1");
        assertThat(client.complete(REQUEST)).isEqualTo("call-2");
        assertLimited(() -> client.complete(REQUEST), 60);
        assertThat(calls).hasValue(2);

        clock.advanceSeconds(59);
        assertLimited(() -> client.complete(REQUEST), 1);
        assertThat(calls).hasValue(2);
        clock.advanceSeconds(1);
        assertThat(client.complete(REQUEST)).isEqualTo("call-3");
    }

    @Test void concurrentCallIsRejectedWithoutInvokingModelAndPermitReturnsAfterCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        ChatClient blocking = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return "call-" + call;
        };
        var client = new RateLimitedChatClient(blocking, 10, 1,
                Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> client.complete(REQUEST));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertLimited(() -> client.complete(REQUEST), 1);
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("call-1");
            assertThat(client.complete(REQUEST)).isEqualTo("call-2");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void downstreamFailureAlwaysReleasesConcurrentPermit() {
        var calls = new AtomicInteger();
        var client = new RateLimitedChatClient(request -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("provider failed");
            return "recovered";
        }, 10, 1, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(IllegalStateException.class);
        assertThat(client.complete(REQUEST)).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
    }

    private static void assertLimited(ThrowingCallable call, long retryAfter) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(error.details()).containsEntry("retryAfterSeconds", retryAfter);
        });
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void advanceSeconds(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}

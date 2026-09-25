package com.smartstaff.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final long MINUTE = 60_000;

    private final AtomicLong nowMillis = new AtomicLong(1_000_000);
    private final RateLimiter limiter = new RateLimiter(nowMillis::get);

    @Test
    @DisplayName("allows requests up to the limit, then blocks the next one")
    void blocksAfterLimit() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("ip", 3, MINUTE)).as("request %d", i + 1).isZero();
        }
        assertThat(limiter.tryAcquire("ip", 3, MINUTE)).isPositive();
    }

    @Test
    @DisplayName("tells the caller how many seconds remain until the window resets")
    void retryAfterCountsDown() {
        limiter.tryAcquire("ip", 1, MINUTE);

        assertThat(limiter.tryAcquire("ip", 1, MINUTE)).isEqualTo(60);
        nowMillis.addAndGet(45_000);
        assertThat(limiter.tryAcquire("ip", 1, MINUTE)).isEqualTo(15);
    }

    @Test
    @DisplayName("allows requests again once the window has elapsed")
    void windowResets() {
        limiter.tryAcquire("ip", 1, MINUTE);
        assertThat(limiter.tryAcquire("ip", 1, MINUTE)).isPositive();

        nowMillis.addAndGet(MINUTE);

        assertThat(limiter.tryAcquire("ip", 1, MINUTE)).isZero();
    }

    @Test
    @DisplayName("hammering while blocked doesn't push the reset time back")
    void blockedRequestsDoNotExtendTheWindow() {
        limiter.tryAcquire("ip", 1, MINUTE);
        for (int i = 0; i < 50; i++) {
            nowMillis.addAndGet(1_000);
            limiter.tryAcquire("ip", 1, MINUTE); // blocked each time
        }
        nowMillis.addAndGet(10_000); // now 60s after the first request

        assertThat(limiter.tryAcquire("ip", 1, MINUTE)).isZero();
    }

    @Test
    @DisplayName("each key has its own counter")
    void keysAreIndependent() {
        limiter.tryAcquire("auth:1.1.1.1", 1, MINUTE);

        assertThat(limiter.tryAcquire("auth:1.1.1.1", 1, MINUTE)).isPositive();
        assertThat(limiter.tryAcquire("auth:2.2.2.2", 1, MINUTE)).isZero();
        assertThat(limiter.tryAcquire("token:1.1.1.1", 1, MINUTE)).isZero();
    }

    @Test
    @DisplayName("memory stays bounded when a flood of distinct addresses arrives")
    void memoryIsBounded() {
        int flood = RateLimiter.MAX_ENTRIES + 1_000; // enough calls to trigger a purge cycle
        for (int i = 0; i < flood; i++) {
            limiter.tryAcquire("ip-" + i, 5, MINUTE);
        }
        assertThat(limiter.size()).isLessThan(RateLimiter.MAX_ENTRIES);
    }

    @Test
    @DisplayName("expired windows are purged, not kept forever")
    void expiredWindowsPurged() {
        for (int i = 0; i < 999; i++) limiter.tryAcquire("old-" + i, 5, MINUTE);
        nowMillis.addAndGet(2 * MINUTE);
        limiter.tryAcquire("trigger", 5, MINUTE); // 1000th call → purge

        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("is exact under concurrency: exactly `limit` of many parallel requests get through")
    void threadSafe() throws Exception {
        int limit = 100;
        int threads = 8;
        int perThread = 500;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    if (limiter.tryAcquire("shared", limit, MINUTE) == 0) allowed.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isEqualTo(limit);
    }
}

package com.smartstaff.filter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Fixed-window, in-memory request counter keyed by an arbitrary string
 *  (RateLimitFilter uses "bucket:clientIp"). Deliberately tiny — no
 *  dependency, no background thread:
 *
 *  - Single-node only. Counts live in this JVM, so with several instances
 *    behind a load balancer the effective limit is per-instance. That's an
 *    accepted trade-off for this app's single-node monolith design; swap for
 *    a shared store (Redis) if it ever scales out.
 *  - Bounded memory: expired windows are purged periodically, and if the map
 *    ever exceeds {@link #MAX_ENTRIES} live keys (an attack from a huge
 *    number of addresses) it is cleared outright — failing open briefly
 *    beats an unbounded heap.
 *
 *  The clock is injectable so tests don't have to sleep. */
public class RateLimiter {

    static final int MAX_ENTRIES = 50_000;
    private static final long PURGE_EVERY_N_CALLS = 1_000;

    private static final class Window {
        final long startMs;
        final long lengthMs;
        int count;

        Window(long startMs, long lengthMs) {
            this.startMs = startMs;
            this.lengthMs = lengthMs;
            this.count = 1;
        }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();
    private final LongSupplier clockMillis;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    public RateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Records one request against `key`. Returns 0 if it is allowed, or the
     *  number of seconds (>= 1) until the window resets if the limit is hit. */
    public long tryAcquire(String key, int maxPerWindow, long windowMillis) {
        long now = clockMillis.getAsLong();
        if (calls.incrementAndGet() % PURGE_EVERY_N_CALLS == 0) purge(now);

        long[] retryAfterSeconds = {0};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.startMs >= w.lengthMs) {
                return new Window(now, windowMillis);
            }
            if (w.count >= maxPerWindow) {
                retryAfterSeconds[0] = Math.max(1, (w.startMs + w.lengthMs - now + 999) / 1000);
                return w;
            }
            w.count++;
            return w;
        });
        return retryAfterSeconds[0];
    }

    int size() {
        return windows.size();
    }

    private void purge(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().startMs >= e.getValue().lengthMs);
        if (windows.size() > MAX_ENTRIES) windows.clear();
    }
}

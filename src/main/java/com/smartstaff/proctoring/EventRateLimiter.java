package com.smartstaff.proctoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** At most N integrity events per attempt per rolling minute (server time), so a
 *  client can't flood the timeline. In-memory: single node, like RateLimitFilter. */
@Component
public class EventRateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final int perMinute;
    private final Map<UUID, Deque<Long>> windows = new ConcurrentHashMap<>();

    public EventRateLimiter(@Value("${app.proctoring.events-per-minute:60}") int perMinute) {
        this.perMinute = perMinute;
    }

    public boolean tryAcquire(UUID attemptId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = windows.computeIfAbsent(attemptId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
            if (q.size() >= perMinute) return false;
            q.addLast(now);
        }
        if (windows.size() > 10_000) windows.entrySet().removeIf(en -> {
            synchronized (en.getValue()) { return en.getValue().isEmpty() || now - en.getValue().peekLast() >= WINDOW_MS; }
        });
        return true;
    }
}

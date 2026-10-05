package com.smartstaff.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Counts Gemini calls and tokens per UTC day and feature (table ai_usage_daily),
 *  and enforces the daily call cap for non-critical features. Writes are
 *  standalone upserts, so they count even when the caller's transaction rolls back. */
@Service
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);

    private final JdbcTemplate jdbc;
    private final AiConfigService config;
    /** The day the cap was last reported, so the WARN goes out once per day. */
    private final AtomicReference<LocalDate> capAlerted = new AtomicReference<>();

    public AiUsageService(JdbcTemplate jdbc, AiConfigService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public record DayFeature(LocalDate day, String feature, int calls, int failures, long prompt_tokens, long output_tokens) {}

    public record UsageView(LocalDate today, int calls_today, int daily_call_cap, boolean cap_reached, List<DayFeature> days) {}

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public void record(AiFeature feature, boolean ok, long promptTokens, long outputTokens) {
        try {
            jdbc.update("""
                    INSERT INTO ai_usage_daily (day, feature, calls, failures, prompt_tokens, output_tokens)
                    VALUES (?, ?, 1, ?, ?, ?)
                    ON CONFLICT (day, feature) DO UPDATE SET
                        calls = ai_usage_daily.calls + 1,
                        failures = ai_usage_daily.failures + EXCLUDED.failures,
                        prompt_tokens = ai_usage_daily.prompt_tokens + EXCLUDED.prompt_tokens,
                        output_tokens = ai_usage_daily.output_tokens + EXCLUDED.output_tokens
                    """, today(), feature.name(), ok ? 0 : 1, promptTokens, outputTokens);
        } catch (Exception e) {
            log.warn("Couldn't record AI usage: {}", e.toString());
        }
    }

    public int callsToday() {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(calls), 0) FROM ai_usage_daily WHERE day = ?",
                Integer.class, today());
        return n == null ? 0 : n;
    }

    /** Throws AI_CAP_REACHED for a non-critical call once today's calls reach the cap. */
    public void checkCap(AiFeature feature) {
        int cap = config.dailyCallCap();
        if (cap <= 0 || feature.critical()) return;
        int used = callsToday();
        if (used < cap) return;
        LocalDate day = today();
        if (!day.equals(capAlerted.getAndSet(day))) {
            log.warn("AI daily call cap reached ({} of {} calls today). Non-critical AI features are paused until "
                    + "tomorrow (UTC); raise the cap in Settings → AI provider if needed.", used, cap);
        }
        throw new GeminiException(GeminiException.CAP_REACHED,
                "Today's AI call limit (" + cap + ") is reached, so this AI feature is paused until tomorrow. "
                        + "An admin can raise the limit in Settings → AI provider.");
    }

    public UsageView view(int days) {
        int span = Math.max(1, Math.min(days, 90));
        List<DayFeature> rows = jdbc.query("""
                SELECT day, feature, calls, failures, prompt_tokens, output_tokens FROM ai_usage_daily
                WHERE day > ? ORDER BY day DESC, feature
                """, (rs, i) -> new DayFeature(rs.getDate("day").toLocalDate(), rs.getString("feature"),
                rs.getInt("calls"), rs.getInt("failures"), rs.getLong("prompt_tokens"), rs.getLong("output_tokens")),
                today().minusDays(span));
        int cap = config.dailyCallCap();
        int used = callsToday();
        return new UsageView(today(), used, cap, cap > 0 && used >= cap, rows);
    }
}

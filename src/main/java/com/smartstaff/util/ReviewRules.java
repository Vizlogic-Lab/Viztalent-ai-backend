package com.smartstaff.util;

import com.smartstaff.entity.ReviewPolicy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

/** F11/F12 pure rules: which results go to an SME, review due dates, reviewer suggestions. */
public final class ReviewRules {

    private ReviewRules() {}

    /** PROCEED and HR_REVIEW always; NOT_RECOMMENDED when theory is high enough or the attempt is sampled. */
    public static boolean needsReview(String recommendation, BigDecimal theory, UUID attemptId, ReviewPolicy policy) {
        ReviewPolicy p = ReviewPolicy.orDefaults(policy);
        if (!"NOT_RECOMMENDED".equals(recommendation)) return true;
        if (theory != null && theory.doubleValue() >= p.review_if_theory_at_least()) return true;
        return sampled(attemptId, p.sample_rate_not_recommended());
    }

    /** Deterministic: the same attempt id is always in or out for a given rate. */
    public static boolean sampled(UUID attemptId, double rate) {
        if (rate <= 0) return false;
        if (rate >= 1) return true;
        return bucket(attemptId) < rate;
    }

    /** First 32 bits of SHA-256(id) as a fraction in [0, 1). */
    static double bucket(UUID id) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(id.toString().getBytes(StandardCharsets.UTF_8));
            long v = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
            return v / 4294967296.0;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** `days` working days later (Mon-Fri, UTC); a weekend start counts from Monday. */
    public static Instant addWorkingDays(Instant from, int days) {
        ZonedDateTime t = from.atZone(ZoneOffset.UTC);
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            if (t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY) added++;
        }
        return t.toInstant();
    }

    public record Suggestion(UUID userId, int score, List<String> matched) {}

    /** Ranks reviewers by the summed weight of role-profile skills they cover
     *  (case-insensitive), highest first; ties by more matches, then input order. */
    public static List<Suggestion> rank(Map<UUID, List<String>> reviewerSkills, Map<String, Integer> skillWeights) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        skillWeights.forEach((k, v) -> weights.merge(k.trim().toLowerCase(Locale.ROOT), v == null ? 1 : v, Math::max));
        List<Suggestion> out = new ArrayList<>();
        reviewerSkills.forEach((id, skills) -> {
            int score = 0;
            List<String> matched = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String s : skills) {
                String k = s.trim().toLowerCase(Locale.ROOT);
                if (weights.containsKey(k) && seen.add(k)) {
                    score += weights.get(k);
                    matched.add(s.trim());
                }
            }
            out.add(new Suggestion(id, score, matched));
        });
        out.sort(Comparator.comparingInt(Suggestion::score).reversed()
                .thenComparing(Comparator.comparingInt((Suggestion s) -> s.matched().size()).reversed()));
        return out;
    }
}

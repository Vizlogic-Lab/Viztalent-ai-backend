package com.smartstaff.util;

import java.util.*;

/** F15 pure statistics: engine-vs-SME agreement, question quality and the
 *  sample-rate suggestion. "Within ±10 points" is measured in percentage
 *  points of the question's (or criterion's) maximum, since questions are
 *  worth 5 to 35 points. */
public final class CalibrationRules {

    public static final double TOLERANCE_PCT = 10.0;
    public static final double TARGET = 0.85;
    public static final int MIN_ATTEMPTS_FOR_FLAGS = 20;
    public static final double TOO_EASY_FULL_MARKS = 0.95;
    public static final double TOO_HARD_ANY_CREDIT = 0.05;
    public static final double LOW_DISCRIMINATION = 0.1;
    public static final int SAMPLE_WINDOW = 50;
    public static final double SAMPLE_AGREEMENT = 0.90;
    public static final double MIN_SAMPLE_RATE = 0.05;

    public static final String TOO_EASY = "TOO_EASY";
    public static final String TOO_HARD = "TOO_HARD";
    public static final String LOW_DISCRIMINATION_FLAG = "LOW_DISCRIMINATION";

    private static final double EPS = 1e-6;

    private CalibrationRules() {}

    public static boolean within(double engine, double sme, double max) {
        if (max <= 0) return Math.abs(engine - sme) < EPS;
        return Math.abs(engine - sme) / max * 100.0 <= TOLERANCE_PCT + EPS;
    }

    // ── agreement ───────────────────────────────────────────────────────

    /** Engine-vs-SME pairs of one group (a type, a criterion, a job...). */
    public static final class Agreement {
        private int pairs;
        private int within;
        private double absPct;
        private double signedPct;
        private int unattributed;

        public void add(double engine, double sme, double max) {
            pairs++;
            if (CalibrationRules.within(engine, sme, max)) within++;
            double pct = max <= 0 ? 0 : (engine - sme) / max * 100.0;
            absPct += Math.abs(pct);
            signedPct += pct;
        }

        /** An SME-adjusted question whose change can't be pinned on this criterion. */
        public void unattributed() {
            unattributed++;
        }

        public int pairs() {
            return pairs;
        }

        public Double share() {
            return pairs == 0 ? null : round(within / (double) pairs, 3);
        }

        /** engine − SME: positive means the engine was more generous. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pairs", pairs);
            m.put("within_tolerance", within);
            m.put("share", share());
            m.put("mean_abs_diff_pct", pairs == 0 ? null : round(absPct / pairs, 2));
            m.put("mean_signed_diff_pct", pairs == 0 ? null : round(signedPct / pairs, 2));
            m.put("meets_target", pairs == 0 ? null : within / (double) pairs + EPS >= TARGET);
            if (unattributed > 0) m.put("unattributed", unattributed);
            return m;
        }
    }

    /** Agreement per key, in first-seen order. */
    public static final class Groups {
        private final Map<String, Agreement> groups = new LinkedHashMap<>();

        public Agreement get(String key) {
            return groups.computeIfAbsent(key, k -> new Agreement());
        }

        /** [{keyName: key, ...agreement}] sorted by key. */
        public List<Map<String, Object>> toList(String keyName) {
            List<Map<String, Object>> out = new ArrayList<>();
            new TreeMap<>(groups).forEach((k, a) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(keyName, k);
                m.putAll(a.toMap());
                out.add(m);
            });
            return out;
        }
    }

    /** Grouping key of a rubric criterion: CODE_WRITE criteria by name, SCENARIO key points together. */
    public static String criterionKey(String type, String criterion) {
        if ("SCENARIO".equals(type)) return type + ":key_point";
        return type + ":" + (criterion == null ? "" : criterion.trim().toLowerCase(Locale.ROOT));
    }

    // ── question quality ────────────────────────────────────────────────

    /** One attempt's result on a question; restFraction = the attempt's share on its other questions. */
    public record Observation(double score, double points, double restFraction) {
        double fraction() {
            return points <= 0 ? 0 : Math.max(0, Math.min(1, score / points));
        }
    }

    public record QuestionStats(int attempts, Double meanScore, Double meanPercent, Double passRate,
                                Double fullMarksRate, Double anyCreditRate, Double discrimination, List<String> flags) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attempts", attempts);
            m.put("mean_score", meanScore);
            m.put("mean_percent", meanPercent);
            m.put("pass_rate", passRate);
            m.put("full_marks_rate", fullMarksRate);
            m.put("any_credit_rate", anyCreditRate);
            m.put("discrimination", discrimination);
            m.put("flags", flags);
            return m;
        }
    }

    /** Pass = at least half the points. Discrimination = Pearson correlation of the
     *  question's score with the attempt's score on the OTHER questions (item-rest,
     *  so the question doesn't correlate with itself). Flags only from 20 attempts. */
    public static QuestionStats questionStats(List<Observation> obs) {
        int n = obs.size();
        if (n == 0) return new QuestionStats(0, null, null, null, null, null, null, List.of());
        double sum = 0, pct = 0;
        int pass = 0, full = 0, credit = 0;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            Observation o = obs.get(i);
            double f = o.fraction();
            sum += o.score();
            pct += f * 100;
            if (f + EPS >= 0.5) pass++;
            if (f + EPS >= 1.0) full++;
            if (o.score() > EPS) credit++;
            x[i] = f;
            y[i] = o.restFraction();
        }
        Double disc = pearson(x, y);
        double fullRate = full / (double) n, creditRate = credit / (double) n;
        return new QuestionStats(n, round(sum / n, 2), round(pct / n, 2), round(pass / (double) n, 3),
                round(fullRate, 3), round(creditRate, 3), disc == null ? null : round(disc, 3),
                flags(n, fullRate, creditRate, disc));
    }

    public static List<String> flags(int attempts, double fullMarksRate, double anyCreditRate, Double discrimination) {
        if (attempts < MIN_ATTEMPTS_FOR_FLAGS) return List.of();
        List<String> out = new ArrayList<>();
        if (fullMarksRate > TOO_EASY_FULL_MARKS) out.add(TOO_EASY);
        if (anyCreditRate < TOO_HARD_ANY_CREDIT) out.add(TOO_HARD);
        if (discrimination != null && discrimination < LOW_DISCRIMINATION) out.add(LOW_DISCRIMINATION_FLAG);
        return out;
    }

    /** Null below two points; 0 when either side doesn't vary (nothing to discriminate on). */
    public static Double pearson(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) return null;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        if (sxx < 1e-12 || syy < 1e-12) return 0.0;
        return sxy / Math.sqrt(sxx * syy);
    }

    // ── sample rate ─────────────────────────────────────────────────────

    public record SampleSuggestion(int reviews, Double agreement, boolean suggest, Double suggestedRate, String reason) {}

    /** agreedNewestFirst: for each SME-reviewed NOT_RECOMMENDED result, newest first, whether the
     *  SME kept NOT_RECOMMENDED. Suggests only — the policy is never changed here. */
    public static SampleSuggestion sampleRate(List<Boolean> agreedNewestFirst, double currentRate) {
        List<Boolean> window = agreedNewestFirst.subList(0, Math.min(SAMPLE_WINDOW, agreedNewestFirst.size()));
        int n = window.size();
        Double agreement = n == 0 ? null : round(window.stream().filter(Boolean::booleanValue).count() / (double) n, 3);
        if (n < SAMPLE_WINDOW) {
            return new SampleSuggestion(n, agreement, false, null,
                    "Needs " + SAMPLE_WINDOW + " reviewed NOT_RECOMMENDED results; has " + n + ".");
        }
        if (agreement + EPS < SAMPLE_AGREEMENT) {
            return new SampleSuggestion(n, agreement, false, null, "SMEs agreed with " + pct(agreement)
                    + " of the last " + n + " NOT_RECOMMENDED results (below " + pct(SAMPLE_AGREEMENT) + "); keep the sample rate.");
        }
        if (currentRate <= MIN_SAMPLE_RATE + EPS) {
            return new SampleSuggestion(n, agreement, false, null, "SMEs agreed with " + pct(agreement)
                    + " of the last " + n + " NOT_RECOMMENDED results; the sample rate is already at the minimum.");
        }
        double suggested = Math.max(MIN_SAMPLE_RATE, round(currentRate / 2, 2));
        return new SampleSuggestion(n, agreement, true, suggested, "SMEs agreed with " + pct(agreement)
                + " of the last " + n + " NOT_RECOMMENDED results; consider lowering the sample rate from "
                + currentRate + " to " + suggested + ". Not changed automatically.");
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}

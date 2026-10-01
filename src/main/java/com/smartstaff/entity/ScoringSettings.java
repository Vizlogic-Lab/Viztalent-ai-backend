package com.smartstaff.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Per-job weights and gate thresholds for F10 results (jobs.scoring_settings).
 *  A job without settings uses {@link #DEFAULTS}. Field names are the JSON keys. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoringSettings(
        double theory_weight,
        double hands_on_weight,
        int proceed_hands_on_min,
        int proceed_theory_min,
        int review_hands_on_min,
        int max_tab_switches,
        int theory_practice_gap
) {
    public static final ScoringSettings DEFAULTS = new ScoringSettings(0.3, 0.7, 60, 50, 50, 5, 25);

    public static ScoringSettings orDefaults(ScoringSettings s) {
        return s == null ? DEFAULTS : s;
    }

    /** Null when valid, else what's wrong. */
    public String problem() {
        if (theory_weight < 0 || hands_on_weight < 0 || Math.abs(theory_weight + hands_on_weight - 1.0) > 1e-6) {
            return "theory_weight and hands_on_weight must be non-negative and add up to 1.";
        }
        for (int v : new int[] {proceed_hands_on_min, proceed_theory_min, review_hands_on_min, theory_practice_gap}) {
            if (v < 0 || v > 100) return "Thresholds must be between 0 and 100.";
        }
        if (review_hands_on_min > proceed_hands_on_min) return "review_hands_on_min can't exceed proceed_hands_on_min.";
        if (max_tab_switches < 0) return "max_tab_switches can't be negative.";
        return null;
    }
}

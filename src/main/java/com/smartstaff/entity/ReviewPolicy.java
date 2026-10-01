package com.smartstaff.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** F11 per-job SME review policy (jobs.review_policy). Field names are the JSON keys.
 *  The result-review part only applies once the job has at least one assigned reviewer. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewPolicy(
        boolean question_set_approval_required,
        double sample_rate_not_recommended,
        int review_if_theory_at_least
) {
    public static final ReviewPolicy DEFAULTS = new ReviewPolicy(false, 0.1, 70);

    public static ReviewPolicy orDefaults(ReviewPolicy p) {
        return p == null ? DEFAULTS : p;
    }

    /** Null when valid, else what's wrong. */
    public String problem() {
        if (sample_rate_not_recommended < 0 || sample_rate_not_recommended > 1) {
            return "sample_rate_not_recommended must be between 0 and 1.";
        }
        if (review_if_theory_at_least < 0 || review_if_theory_at_least > 101) {
            return "review_if_theory_at_least must be between 0 and 101.";
        }
        return null;
    }
}

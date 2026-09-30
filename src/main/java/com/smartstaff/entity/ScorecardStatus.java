package com.smartstaff.entity;

/** Lifecycle of an attempt's scorecard. */
public enum ScorecardStatus {
    /** Queued for scoring. */
    PENDING,
    /** Scoring in progress (running code, calling Gemini). */
    SCORING,
    /** Done — total_score/percent/passed are final (subject to manual review of AI parts). */
    SCORED,
    /** Scoring crashed; see error. */
    FAILED;

    public boolean inProgress() {
        return this == PENDING || this == SCORING;
    }
}

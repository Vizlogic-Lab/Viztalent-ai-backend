package com.smartstaff.entity;

/** Lifecycle of a candidate's assessment attempt. */
public enum AttemptStatus {
    /** The candidate is taking it; answers can still be saved. */
    IN_PROGRESS,
    /** Finalised — the candidate submitted, or the deadline auto-submitted it. */
    SUBMITTED,
    /** Reserved for an attempt abandoned past its deadline with nothing saved
     *  (currently the deadline path always SUBMITs what was saved). */
    EXPIRED;

    public boolean isFinal() {
        return this != IN_PROGRESS;
    }
}

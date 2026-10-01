package com.smartstaff.entity;

/** Lifecycle of one level-attempt:
 *  IN_PROGRESS → SUBMITTED → EVALUATING → EVALUATED → (AWAITING_REVIEW →) VALIDATED.
 *  EXPIRED is for a link that ran out before the attempt was started. */
public enum AttemptStatus {
    IN_PROGRESS, SUBMITTED, EVALUATING, EVALUATED, AWAITING_REVIEW, VALIDATED, EXPIRED;

    /** No more answers can be saved, run or submitted. */
    public boolean isFinal() {
        return this != IN_PROGRESS;
    }
}

package com.smartstaff.entity;

/** QUEUED -> GENERATING -> VALIDATING -> READY, or FAILED. */
public enum AssessmentStatus {
    QUEUED, GENERATING, VALIDATING, READY, FAILED;

    public boolean inProgress() {
        return this == QUEUED || this == GENERATING || this == VALIDATING;
    }
}

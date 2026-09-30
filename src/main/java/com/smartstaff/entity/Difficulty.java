package com.smartstaff.entity;

import java.util.Locale;

public enum Difficulty {
    EASY, MEDIUM, HARD;

    /** Stored lower-case in assessment_questions.difficulty, as before. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}

package com.smartstaff.entity;

import java.util.Locale;

public enum QuestionType {
    MCQ(Dimension.THEORY, Competency.CONCEPTS),
    MSQ(Dimension.THEORY, Competency.CONCEPTS),
    CODE_WRITE(Dimension.HANDS_ON, Competency.CODING),
    CODE_DEBUG(Dimension.HANDS_ON, Competency.DEBUGGING),
    CODE_OUTPUT(Dimension.HANDS_ON, Competency.CODE_READING),
    SCENARIO(Dimension.HANDS_ON, Competency.APPLICATION),
    LOGIC(Dimension.HANDS_ON, Competency.LOGIC);

    private final Dimension dimension;
    private final Competency competency;

    QuestionType(Dimension dimension, Competency competency) {
        this.dimension = dimension;
        this.competency = competency;
    }

    public Dimension dimension() { return dimension; }

    public Competency competency() { return competency; }

    /** Runs candidate code in the sandbox. */
    public boolean isCode() {
        return this == CODE_WRITE || this == CODE_DEBUG || this == CODE_OUTPUT;
    }

    /** Question-bank uploads still use the legacy names DESCRIPTIVE and CODING. */
    public static QuestionType fromBankType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "DESCRIPTIVE" -> SCENARIO;
            case "CODING" -> CODE_WRITE;
            default -> valueOf(t);
        };
    }
}

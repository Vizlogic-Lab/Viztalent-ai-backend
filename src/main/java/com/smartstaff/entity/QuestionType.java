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

    /** Question-bank uploads use the legacy names DESCRIPTIVE and CODING, and the
     *  section labels HR teams write in spreadsheets ("Subjective", "Code & Bug",
     *  "Logical / Output"). Punctuation and spacing are ignored. */
    public static QuestionType fromBankType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return switch (t) {
            case "DESCRIPTIVE", "SUBJECTIVE", "THEORY", "WRITTEN" -> SCENARIO;
            case "CODING", "CODE" -> CODE_WRITE;
            case "CODE_BUG", "CODE_AND_BUG", "BUG", "BUG_FIX", "BUG_FIXING", "DEBUG", "DEBUGGING" -> CODE_DEBUG;
            case "OUTPUT", "CODE_OUTPUT", "PREDICT_OUTPUT" -> CODE_OUTPUT;
            case "LOGICAL", "LOGICAL_OUTPUT", "LOGIC", "REASONING", "APTITUDE" -> LOGIC;
            case "MULTIPLE_CHOICE", "SINGLE_CHOICE" -> MCQ;
            case "MULTIPLE_SELECT", "MULTI_SELECT", "MULTIPLE_ANSWER" -> MSQ;
            default -> valueOf(t);
        };
    }
}

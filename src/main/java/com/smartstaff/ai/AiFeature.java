package com.smartstaff.ai;

/** What a Gemini call is for — the usage table's feature column. Critical
 *  features keep running after the daily call cap is reached (a candidate
 *  mid-assessment or mid-interview, or their grading, must never be blocked);
 *  the others stop, and their callers fall back (rule table, question bank). */
public enum AiFeature {
    PERSONAL_ASSESSMENT(true),
    GRADING(true),
    INTERVIEW(true),
    QUESTION_GENERATION(false),
    ROLE_PROFILE(false),
    SUMMARY(false),
    RECRUITER_CHAT(false),
    CONNECTION_TEST(true);

    private final boolean critical;

    AiFeature(boolean critical) {
        this.critical = critical;
    }

    public boolean critical() {
        return critical;
    }
}

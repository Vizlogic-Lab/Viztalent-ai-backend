package com.smartstaff.util;

import com.smartstaff.entity.Candidate;
import com.smartstaff.util.ScreeningDecision.Rule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreeningDecisionTest {

    private static Candidate c(String exp, int kw, String aiStatus, Integer ai) {
        Candidate c = new Candidate();
        c.setExperienceStatus(exp);
        c.setYearsExperience(5);
        c.setFitScore(kw);
        c.setAiMatchStatus(aiStatus);
        c.setAiMatchScore(ai);
        return c;
    }

    private static String d(Candidate c, Rule r) { return ScreeningDecision.decide(c, r).decision(); }

    @Test
    void experienceStatus() {
        assertEquals("UNKNOWN", ScreeningDecision.experienceStatus(0, 3, 6));
        assertEquals("FAIL", ScreeningDecision.experienceStatus(2, 3, 6));
        assertEquals("FAIL", ScreeningDecision.experienceStatus(9, 3, 6));
        assertEquals("PASS", ScreeningDecision.experienceStatus(3, 3, 6));
        assertEquals("PASS", ScreeningDecision.experienceStatus(6, 3, 6));
        assertEquals("PASS", ScreeningDecision.experienceStatus(20, 3, null));
        assertEquals("PASS", ScreeningDecision.experienceStatus(20, null, null));
        assertEquals("PASS", ScreeningDecision.experienceStatus(20, 3, 0)); // 0 max = no max
    }

    @Test
    void experienceFailRejectsUnderEveryRule() {
        for (Rule r : Rule.values()) {
            assertEquals("FAIL", d(c("FAIL", 100, "DONE", 100), r));
        }
    }

    @Test
    void overridePassesEvenAfterExperienceFail() {
        Candidate x = c("FAIL", 0, "SKIPPED", null);
        x.setOverridePassed(true);
        x.setOverrideReason("discussed");
        for (Rule r : Rule.values()) assertEquals("PASS", d(x, r));
        assertEquals("OVERRIDE", ScreeningDecision.decide(x, Rule.BOTH).step());
    }

    @Test
    void either() {
        assertEquals("PASS", d(c("PASS", 60, "PENDING", null), Rule.EITHER));
        assertEquals("PASS", d(c("PASS", 37, "DONE", 74), Rule.EITHER));
        assertEquals("PENDING", d(c("PASS", 37, "PENDING", null), Rule.EITHER));
        assertEquals("FAIL", d(c("PASS", 37, "DONE", 40), Rule.EITHER));
        assertEquals("FAIL", d(c("UNKNOWN", 37, "OFF", null), Rule.EITHER));
    }

    @Test
    void both() {
        assertEquals("FAIL", d(c("PASS", 37, "DONE", 90), Rule.BOTH));
        assertEquals("PENDING", d(c("PASS", 60, "PENDING", null), Rule.BOTH));
        assertEquals("PASS", d(c("PASS", 60, "DONE", 70), Rule.BOTH));
        assertEquals("FAIL", d(c("PASS", 60, "DONE", 40), Rule.BOTH));
        assertEquals("PASS", d(c("PASS", 60, "FAILED", null), Rule.BOTH)); // AI unavailable -> keyword decides
    }

    @Test
    void aiOnly() {
        assertEquals("PASS", d(c("PASS", 10, "DONE", 80), Rule.AI_ONLY));
        assertEquals("FAIL", d(c("PASS", 90, "DONE", 30), Rule.AI_ONLY));
        assertEquals("PENDING", d(c("PASS", 90, "PENDING", null), Rule.AI_ONLY));
        assertEquals("PASS", d(c("PASS", 90, "OFF", null), Rule.AI_ONLY));
    }

    @Test
    void keywordOnly() {
        assertEquals("FAIL", d(c("PASS", 37, "DONE", 99), Rule.KEYWORD_ONLY));
        assertEquals("PASS", d(c("PASS", 50, "PENDING", null), Rule.KEYWORD_ONLY));
    }

    @Test
    void nearMissesAreSentForReview() {
        // Divya: keyword 34, AI 48 -> 48 is 2 below the mark
        assertEquals("REVIEW", d(c("PASS", 34, "DONE", 48), Rule.EITHER));
        assertEquals("REVIEW", d(c("PASS", 34, "DONE", 45), Rule.EITHER));
        assertEquals("FAIL", d(c("PASS", 34, "DONE", 44), Rule.EITHER));
        assertEquals("REVIEW", d(c("PASS", 60, "DONE", 47), Rule.BOTH));
        assertEquals("FAIL", d(c("PASS", 40, "DONE", 90), Rule.BOTH));   // keyword 40 is not near
        assertEquals("REVIEW", d(c("PASS", 90, "DONE", 46), Rule.AI_ONLY));
        assertEquals("REVIEW", d(c("PASS", 49, "DONE", 99), Rule.KEYWORD_ONLY));
        // An experience fail is never softened.
        assertEquals("FAIL", d(c("FAIL", 49, "SKIPPED", null), Rule.EITHER));
    }

    @Test
    void parseFallsBackToEither() {
        assertEquals(Rule.EITHER, Rule.parse(null));
        assertEquals(Rule.EITHER, Rule.parse("nonsense"));
        assertEquals(Rule.BOTH, Rule.parse("both"));
    }
}

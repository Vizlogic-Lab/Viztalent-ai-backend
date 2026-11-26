package com.smartstaff.service.impl;

import com.smartstaff.entity.Candidate;
import com.smartstaff.util.ScreeningDecision.Rule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may enter through the shared assessment link. */
class ScreeningBatchCanEnterTest {

    private static Candidate cand(int keyword, Integer ai, String aiStatus, String experience) {
        Candidate c = new Candidate();
        c.setFitScore(keyword);
        c.setAiMatchScore(ai);
        c.setAiMatchStatus(aiStatus);
        c.setExperienceStatus(experience);
        return c;
    }

    @Test
    void highAiMatchLetsALowKeywordCandidateIn() {
        // The Genesys case: keyword 21, AI 95.
        assertThat(ScreeningBatchService.canEnter(cand(21, 95, "DONE", "PASS"), Rule.EITHER, 50)).isTrue();
    }

    @Test
    void recruiterPassAlwaysLetsTheCandidateIn() {
        Candidate c = cand(5, 0, "DONE", "FAIL");
        c.setOverridePassed(true);
        c.setOverrideReason("Referred by the hiring manager");
        assertThat(ScreeningBatchService.canEnter(c, Rule.EITHER, 80)).isTrue();
    }

    @Test
    void failedOrReviewOrPendingCandidatesStayOut() {
        assertThat(ScreeningBatchService.canEnter(cand(5, 0, "DONE", "PASS"), Rule.EITHER, 0)).isFalse();      // FAIL
        assertThat(ScreeningBatchService.canEnter(cand(47, 30, "DONE", "PASS"), Rule.EITHER, 0)).isFalse();    // REVIEW (45-49)
        assertThat(ScreeningBatchService.canEnter(cand(90, null, "PENDING", "PASS"), Rule.BOTH, 0)).isFalse(); // AI still running
        assertThat(ScreeningBatchService.canEnter(cand(90, 90, "DONE", "FAIL"), Rule.EITHER, 0)).isFalse();    // experience
    }

    @Test
    void theLinksMinimumStillApplies() {
        Candidate c = cand(40, 60, "DONE", "PASS");
        assertThat(ScreeningBatchService.canEnter(c, Rule.EITHER, 50)).isTrue();
        assertThat(ScreeningBatchService.canEnter(c, Rule.EITHER, 70)).isFalse();
    }

    @Test
    void withoutAiTheKeywordScoreDecides() {
        assertThat(ScreeningBatchService.canEnter(cand(60, null, "OFF", "PASS"), Rule.EITHER, 50)).isTrue();
        assertThat(ScreeningBatchService.canEnter(cand(30, null, "OFF", "PASS"), Rule.EITHER, 50)).isFalse();
    }
}

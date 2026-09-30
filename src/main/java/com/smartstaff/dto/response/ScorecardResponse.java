package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartstaff.entity.QuestionScoreBreakdown;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** HR-facing scorecard for one attempt. status is PENDING/SCORING/SCORED/FAILED
 *  (poll while in progress). needs_review is true when some AI part couldn't be
 *  graded automatically; passed is then provisional. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScorecardResponse(
        boolean ok,
        String status,
        String attempt_id,
        String candidate_email,
        String candidate_name,
        List<String> levels,
        Integer version,
        BigDecimal total_score,
        BigDecimal max_score,
        BigDecimal percent,
        int pass_threshold,
        boolean passed,
        boolean needs_review,
        BigDecimal review_points,
        String error,
        Instant submitted_at,
        Instant scored_at,
        List<QuestionScoreView> questions
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionScoreView(
            String question_id,
            String level,
            int seq,
            String type,
            String skill,
            int max_points,
            BigDecimal score,
            boolean auto_graded,
            boolean needs_review,
            boolean answered,
            Integer tests_passed,
            Integer tests_total,
            String detail,
            List<QuestionScoreBreakdown> breakdown
    ) {}

    /** No scorecard exists yet (attempt not submitted, or scoring not started). */
    public static ScorecardResponse none(String attemptId) {
        return new ScorecardResponse(true, "NONE", attemptId, null, null, null, null,
                null, null, null, 50, false, false, null, null, null, null, null);
    }
}

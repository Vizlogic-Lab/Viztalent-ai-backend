package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** GET /api/assessment/submissions/{jobId} and /submissions_all — one row per
 *  level-attempt. The F10 result fields (scores, bands, recommendation) are set
 *  once the attempt is EVALUATED; result_status is PROVISIONAL until SME
 *  sign-off (F12); review_status is the SME review's status when one exists. score_status/percent/passed/needs_review are the legacy
 *  scorecard projection, kept for the current Results page. */
public record AssessmentSubmissionsResponse(boolean ok, List<SubmissionSummary> submissions, int pass_threshold) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubmissionSummary(
            String attempt_id,
            String job_id,
            String job_title,
            String candidate_email,
            String candidate_name,
            String level,
            List<String> levels,
            boolean combined,
            String status,
            int answered,
            int num_questions,
            Instant started_at,
            Instant submitted_at,
            boolean auto_submitted,
            Integer version,
            BigDecimal theory_score,
            BigDecimal hands_on_score,
            BigDecimal overall_score,
            String theory_band,
            String hands_on_band,
            String recommendation,
            List<String> flags,
            String result_status,
            String review_status,
            String score_status,
            BigDecimal percent,
            Boolean passed,
            Boolean needs_review,
            // F12: who validated it, when, and what they said ("sampling" = validated without an SME).
            String review_id,
            String reviewer_name,
            Instant signed_off_at,
            String sme_comment,
            String validation_reason
    ) {}

    public static AssessmentSubmissionsResponse empty() {
        return new AssessmentSubmissionsResponse(true, List.of(), 50);
    }
}

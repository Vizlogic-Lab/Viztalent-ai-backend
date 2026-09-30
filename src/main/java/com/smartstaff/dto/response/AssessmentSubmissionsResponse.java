package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** GET /api/assessment/submissions/{jobId} — the candidates who have taken (or
 *  are taking) this job's assessment. Scores land in F9; until then each row
 *  reports status and how many questions were answered, not a mark. */
public record AssessmentSubmissionsResponse(boolean ok, List<SubmissionSummary> submissions, int pass_threshold) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubmissionSummary(
            String attempt_id,
            String candidate_email,
            String candidate_name,
            List<String> levels,
            boolean combined,
            String status,
            int answered,
            int num_questions,
            Instant started_at,
            Instant submitted_at,
            boolean auto_submitted,
            Integer version,
            String score_status,
            java.math.BigDecimal percent,
            Boolean passed,
            Boolean needs_review
    ) {}

    public static AssessmentSubmissionsResponse empty() {
        return new AssessmentSubmissionsResponse(true, List.of(), 50);
    }
}

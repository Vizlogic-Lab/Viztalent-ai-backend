package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** GET /api/assessment/attempts/{attemptId} — everything a reviewer needs for
 *  one level-attempt: the result, each question as the candidate saw it with
 *  their answer, code, run results, scores and rubric evidence, and the
 *  integrity events. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttemptReviewResponse(
        boolean ok,
        AssessmentSubmissionsResponse.SubmissionSummary attempt,
        Map<String, BigDecimal> competency_scores,
        List<String> evidence_lines,
        Map<String, Object> summary,
        List<QuestionReview> questions,
        Map<String, Long> event_counts,
        List<EventView> events
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionReview(
            String question_id,
            int seq,
            String type,
            String dimension,
            String competency,
            int points,
            Map<String, Object> question,
            List<Integer> selected_indices,
            String language,
            String code,
            String text_answer,
            BigDecimal auto_score,
            BigDecimal final_score,
            List<String> flags,
            List<Map<String, Object>> evidence,
            List<Map<String, Object>> rubric_runs,
            List<RunView> runs
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunView(String mode, String language, String compile_error, List<Map<String, Object>> results,
                          Instant at) {}

    public record EventView(String type, Map<String, Object> payload, Instant at) {}
}

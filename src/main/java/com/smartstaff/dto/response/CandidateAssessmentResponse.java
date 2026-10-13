package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** The candidate-facing payload (start/resume, autosave, submit). status is
 *  "in_progress" (questions of current_level are included) or "submitted"
 *  (that level is done; next_level is set when a combined invite has more).
 *  Questions are CandidateQuestionView only — never answers, solutions,
 *  hidden tests or rubric. server_time lets the page sync its countdown. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CandidateAssessmentResponse(
        boolean ok,
        String status,
        String job_title,
        String candidate_name,
        List<String> levels,
        boolean combined,
        String current_level,
        String next_level,
        List<LevelStatus> level_statuses,
        String attempt_id,
        int num_questions,
        int total_points,
        Instant started_at,
        Instant deadline,
        Instant deadline_at,
        Instant server_time,
        long seconds_remaining,
        Instant submitted_at,
        boolean auto_submitted,
        Integer run_limit,
        String message,
        List<CandidateQuestionView> questions,
        List<CandidateAnswerView> saved_answers,
        /** Proctoring warnings so far, counted by the server from stored events. */
        int warnings
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LevelStatus(String level, String status, Boolean auto_submitted, Instant submitted_at) {}
}

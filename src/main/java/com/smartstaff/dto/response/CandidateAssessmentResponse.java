package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** The candidate-facing assessment payload (by_token / save / submit).
 *  status is "in_progress" or "submitted". Questions are the candidate view
 *  (no answers, solutions, hidden tests or rubric) for the invited levels and
 *  are omitted once submitted; saved_answers lets the page resume. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CandidateAssessmentResponse(
        boolean ok,
        String status,
        String job_title,
        String candidate_name,
        List<String> levels,
        boolean combined,
        int num_questions,
        int total_points,
        Instant started_at,
        Instant deadline,
        long seconds_remaining,
        Instant submitted_at,
        boolean auto_submitted,
        String message,
        List<CandidateQuestionView> questions,
        List<CandidateAnswerView> saved_answers
) {}

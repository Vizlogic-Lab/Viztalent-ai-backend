package com.smartstaff.dto.response;

import com.smartstaff.entity.RubricCriterion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A hand-authored assessment with its answer key — admin-facing only.
 *  problems lists what still blocks publishing (empty = publishable).
 *  locked: a candidate has started it, so its questions can no longer change. */
public record CustomAssessmentResponse(
        UUID id,
        UUID job_id,
        String title,
        String lifecycle,
        int version,
        boolean current,
        int total_marks,
        int target_total,
        List<Section> sections,
        boolean locked,
        boolean publishable,
        List<String> problems,
        List<Question> questions,
        Instant created_at,
        Instant updated_at
) {
    public record Section(String type, int required, int present, int marks_each, int marks) {}

    public record Question(UUID id, int seq, String type, String prompt, List<String> options, Integer correct_index,
                           String answer, List<String> accepted_answers, String model_answer, List<String> key_points,
                           List<RubricCriterion> rubric, int marks, String origin, String skill, String difficulty) {}
}

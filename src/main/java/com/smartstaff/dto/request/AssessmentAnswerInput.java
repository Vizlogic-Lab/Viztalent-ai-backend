package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** One answer in a save/submit call. Only the fields for the question's type
 *  are read: selected_indices for MCQ/MSQ, language+code for coding types,
 *  text for SCENARIO/LOGIC/CODE_OUTPUT. */
public record AssessmentAnswerInput(
        @NotBlank String question_id,
        List<Integer> selected_indices,
        String language,
        String code,
        String text
) {}

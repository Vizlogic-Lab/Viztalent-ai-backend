package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Run the candidate's code for one question against its VISIBLE tests only. */
public record AssessmentRunRequest(
        @NotBlank String question_id,
        @NotBlank String language,
        String code
) {}

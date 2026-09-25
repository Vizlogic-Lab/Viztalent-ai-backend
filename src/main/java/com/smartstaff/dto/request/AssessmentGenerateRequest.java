package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AssessmentGenerateRequest(@NotBlank String session_id, @NotBlank String question_source) {}

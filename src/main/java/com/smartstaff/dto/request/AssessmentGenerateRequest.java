package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** question_source: ai | mix | custom. levels: which levels to build (default L1, L2, L3). */
public record AssessmentGenerateRequest(@NotBlank String session_id, @NotBlank String question_source, List<String> levels) {

    public AssessmentGenerateRequest(String session_id, String question_source) {
        this(session_id, question_source, null);
    }
}

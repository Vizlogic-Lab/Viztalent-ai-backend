package com.smartstaff.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** The employer's paper setup: how many questions of each type, the marks for each type,
 *  the total number of questions, the time limit and an optional passing mark.
 *  The question counts must add up to total_questions. There is no fixed total of
 *  marks; the paper's marks are the sum of its question marks. */
public record MarkingSchemeRequest(
        @Min(0) @Max(500) int mcq_count,
        @Min(0) @Max(100) int mcq_marks,
        @Min(0) @Max(500) int one_word_count,
        @Min(0) @Max(100) int one_word_marks,
        @Min(0) @Max(500) int scenario_count,
        @Min(0) @Max(100) int scenario_marks,
        @Min(0) @Max(500) int project_count,
        @Min(0) @Max(100) int project_marks,
        @Min(0) @Max(500) int debug_count,
        @Min(0) @Max(100) int debug_marks,
        @Min(1) @Max(500) int total_questions,
        @Min(1) @Max(600) int duration_minutes,
        @Min(0) @Max(100) Integer passing_percent
) {
    @AssertTrue(message = "The MCQ, one-word, scenario, project and debug counts must add up to total_questions.")
    public boolean isCountsMatchTotal() {
        return mcq_count + one_word_count + scenario_count + project_count + debug_count == total_questions;
    }
}

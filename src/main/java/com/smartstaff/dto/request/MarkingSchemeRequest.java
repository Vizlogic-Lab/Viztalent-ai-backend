package com.smartstaff.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Default marks for each question of a section, and the total the marks must reach.
 *  Section sizes are fixed (25 / 15 / 5 / 5); marks of single questions can be
 *  overridden afterwards, so the defaults only need to land on the target here. */
public record MarkingSchemeRequest(
        @Min(0) @Max(100) int mcq_marks,
        @Min(0) @Max(100) int one_word_marks,
        @Min(0) @Max(100) int scenario_marks,
        @Min(0) @Max(100) int project_marks,
        @Min(1) @Max(1000) int target_total
) {
    @AssertTrue(message = "25 MCQ, 15 one-word, 5 scenario and 5 project marks must add up to target_total.")
    public boolean isBalanced() {
        return 25 * mcq_marks + 15 * one_word_marks + 5 * scenario_marks + 5 * project_marks == target_total;
    }
}

package com.smartstaff.dto.request;

import com.smartstaff.entity.RubricCriterion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** PUT /api/assessment/questions/{questionId} — HR edits to a question of a
 *  version nobody has taken yet. Every field is optional; only the ones sent
 *  change. Coding edits are re-validated in the sandbox before they're saved. */
public record QuestionEditRequest(
        String title,
        String statement,
        List<String> options,
        List<Integer> correct_indices,
        Integer points,
        List<RubricCriterion> rubric,
        String model_answer,
        List<String> key_points,
        String explanation,
        String constraints,
        String input_format,
        String output_format,
        Map<String, String> starter_code,
        Map<String, String> reference_solution,
        Map<String, String> buggy_code,
        List<TestCaseInput> tests
) {
    public record TestCaseInput(String input, String expected_output, Boolean visible, String category,
                                BigDecimal weight, BigDecimal float_tolerance) {}
}

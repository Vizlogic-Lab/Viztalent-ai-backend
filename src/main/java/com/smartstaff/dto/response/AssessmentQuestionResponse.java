package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartstaff.entity.RubricCriterion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** One question of the HR/SME answer key. The first seven fields are the
 *  original shape Candidates.jsx's AnswerKeyQuestion reads (field "question",
 *  not "prompt") and stay verbatim; the rest is the full practical detail —
 *  solutions, every test (hidden ones marked), rubric and validation status.
 *  Never sent to candidates (they get CandidateQuestionView). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentQuestionResponse(
        String type,
        String question,
        List<String> options,
        Integer correct_index,
        List<Integer> correct_indices,
        String skill,
        String difficulty,
        String question_id,
        String level,
        Integer seq,
        String dimension,
        String competency,
        Integer points,
        Integer time_estimate_sec,
        String title,
        String statement,
        String constraints,
        String input_format,
        String output_format,
        List<String> languages,
        Map<String, String> starter_code,
        Map<String, String> reference_solution,
        Map<String, String> buggy_code,
        Map<String, String> fixed_code,
        List<String> bug_descriptions,
        String model_answer,
        List<String> key_points,
        String explanation,
        List<RubricCriterion> rubric,
        String expected_complexity,
        List<TestCaseView> tests,
        Boolean validated,
        String validation_log,
        String origin
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestCaseView(int seq, String input, String expected_output, boolean visible,
                               String category, BigDecimal weight, BigDecimal float_tolerance) {}
}

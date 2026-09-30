package com.smartstaff.dto.response;

import java.util.List;
import java.util.Map;

/** What a candidate may see of a question. Deliberately has no answer,
 *  solution, hidden-test or rubric fields at all, so nothing can leak by
 *  forgetting to null one out (CandidateQuestionViewTest enforces this).
 *  For CODE_DEBUG the starter code is the buggy snippet to fix; for
 *  CODE_OUTPUT it is the snippet whose output is predicted. */
public record CandidateQuestionView(
        String question_id,
        String level,
        int seq,
        String type,
        String dimension,
        String competency,
        int points,
        Integer time_estimate_sec,
        String title,
        String prompt,
        String constraints,
        String input_format,
        String output_format,
        List<String> options,
        String skill,
        String difficulty,
        List<String> languages,
        Map<String, String> starter_code,
        List<SampleTest> sample_tests
) {
    /** A visible test: the candidate may run their code against these. */
    public record SampleTest(int seq, String input, String expected_output) {}
}

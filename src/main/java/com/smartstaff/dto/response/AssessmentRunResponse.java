package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Result of running a candidate's code against a question's VISIBLE tests.
 *  status mirrors CodeRunnerService.RunStatus (OK / RUNNER_BUSY /
 *  RUNNER_UNAVAILABLE / UNSUPPORTED_LANGUAGE). Hidden tests are never run or
 *  reported here. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentRunResponse(
        boolean ok,
        String status,
        String message,
        String compile_error,
        int passed,
        int total,
        List<VisibleTestResult> tests
) {
    public record VisibleTestResult(
            int seq,
            String input,
            String expected_output,
            String actual_output,
            String stderr,
            boolean passed,
            String status,
            Long time_ms
    ) {}

    public static AssessmentRunResponse unavailable(String status, String message) {
        return new AssessmentRunResponse(false, status, message, null, 0, 0, List.of());
    }
}

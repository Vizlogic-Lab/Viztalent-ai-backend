package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** status: "queued" (generation started; poll GET /api/assessment/status/{jobId}) or "error". */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentGenerateResponse(
        String status,
        String message,
        String assessment_url,
        Map<String, String> assessment_urls,
        Map<String, Integer> counts,
        String assessment_id,
        Integer version
) {
    public static AssessmentGenerateResponse error(String message) {
        return new AssessmentGenerateResponse("error", message, null, null, null, null, null);
    }
}

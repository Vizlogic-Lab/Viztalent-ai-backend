package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionUploadResultResponse(boolean ok, Integer added, Integer total, List<String> warnings, String message) {
    public static QuestionUploadResultResponse success(int added, int total, List<String> warnings) {
        return new QuestionUploadResultResponse(true, added, total, warnings.isEmpty() ? null : warnings, null);
    }

    public static QuestionUploadResultResponse failure(String message) {
        return new QuestionUploadResultResponse(false, null, null, null, message);
    }
}

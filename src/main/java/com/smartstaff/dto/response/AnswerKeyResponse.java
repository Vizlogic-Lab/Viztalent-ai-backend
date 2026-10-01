package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** GET /api/assessment/answer_key/{jobId}[?version=]. ok/role_title/levels keep
 *  the original shape; version/status/assessment_id say which version this is. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerKeyResponse(boolean ok, String role_title, List<AnswerKeyLevelResponse> levels,
                                String assessment_id, Integer version, String status, Boolean is_current,
                                Boolean has_attempts) {

    public AnswerKeyResponse(boolean ok, String role_title, List<AnswerKeyLevelResponse> levels) {
        this(ok, role_title, levels, null, null, null, null, null);
    }
}

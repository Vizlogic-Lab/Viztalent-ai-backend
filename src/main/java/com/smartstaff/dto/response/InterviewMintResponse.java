package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** POST /api/interview/invites/mint response. See Candidates.jsx's
 *  PhoneConfirmModal "candidate_link" handler: success only requires a
 *  truthy `url` and status !== "error"; the prep step's own failure (no
 *  Gemini key, etc.) comes back as {status:"error", message}, HTTP 200 —
 *  not an HTTP error — exactly like AssessmentGenerateResponse. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterviewMintResponse(String status, String message, String url) {

    public static InterviewMintResponse success(String url) {
        return new InterviewMintResponse("success", null, url);
    }

    public static InterviewMintResponse error(String message) {
        return new InterviewMintResponse("error", message, null);
    }
}

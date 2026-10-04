package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartstaff.service.impl.EmailService;

/** POST /api/interview/invites/mint response. See Candidates.jsx's
 *  PhoneConfirmModal "candidate_link" handler: success only requires a
 *  truthy `url` and status !== "error"; the prep step's own failure (no
 *  Gemini key, etc.) comes back as {status:"error", message}, HTTP 200 —
 *  not an HTTP error — exactly like AssessmentGenerateResponse.
 *  email (F17) is set only when the mint asked for send_email. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterviewMintResponse(String status, String message, String url, EmailService.Queued email) {

    public static InterviewMintResponse success(String url, EmailService.Queued email) {
        return new InterviewMintResponse("success", null, url, email);
    }

    public static InterviewMintResponse error(String message) {
        return new InterviewMintResponse("error", message, null, null);
    }
}

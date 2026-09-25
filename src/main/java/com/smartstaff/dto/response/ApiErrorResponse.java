package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Matches every error shape the frontend already knows how to render
 *  (ok:false + message, optionally a machine-readable code like
 *  "pending_approval"). See src/lib/auth.jsx describeError() in the frontend.
 *
 *  `detail` repeats `message` on purpose: the frontend was written against a
 *  FastAPI backend, whose errors are {detail: "..."}, and a few call sites
 *  (InterviewRoom.jsx's phone-call flow) only read `detail` — without it they
 *  fall back to axios' generic "Request failed with status code 502". */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(boolean ok, String message, String code, String detail) {
    public static ApiErrorResponse of(String message) {
        return new ApiErrorResponse(false, message, null, message);
    }

    public static ApiErrorResponse of(String message, String code) {
        return new ApiErrorResponse(false, message, code, message);
    }
}

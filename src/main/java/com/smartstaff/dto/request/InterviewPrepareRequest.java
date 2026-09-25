package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** POST /api/interview/prepare — see InterviewRoom.jsx (both the browser-mode
 *  useEffect and PhoneCallRoom's phone-mode flow reuse this same call). */
public record InterviewPrepareRequest(
        @NotBlank String session_id,
        String candidate_name,
        String phone,
        String file_name,
        String language
) {}

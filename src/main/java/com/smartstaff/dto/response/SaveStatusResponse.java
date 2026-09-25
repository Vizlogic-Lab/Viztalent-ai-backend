package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** POST /api/config/gemini — see Settings.jsx's saveGeminiKey, which only
 *  checks `status === 'error'`. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveStatusResponse(String status, String message) {
    public static final SaveStatusResponse OK = new SaveStatusResponse("ok", null);
}

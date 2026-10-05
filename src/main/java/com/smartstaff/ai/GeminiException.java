package com.smartstaff.ai;

import com.smartstaff.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A Gemini call that failed for a known reason. Messages are written for
 *  admins and never contain the API key. */
public class GeminiException extends ApiException {

    public static final String QUOTA_EXCEEDED = "AI_QUOTA_EXCEEDED";
    public static final String MODEL_UNAVAILABLE = "AI_MODEL_UNAVAILABLE";
    public static final String KEY_INVALID = "AI_KEY_INVALID";
    public static final String CAP_REACHED = "AI_CAP_REACHED";
    public static final String REQUEST_REJECTED = "AI_REQUEST_REJECTED";
    public static final String UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String NOT_CONFIGURED = "AI_NOT_CONFIGURED";

    public GeminiException(String code, String message) {
        super(statusFor(code), message, code);
    }

    /** Quota, key, model, cap or setup problems: retrying soon won't help, so callers
     *  should use their fallback for the rest of the run. */
    public boolean unusable() {
        String c = getCode();
        return QUOTA_EXCEEDED.equals(c) || MODEL_UNAVAILABLE.equals(c) || KEY_INVALID.equals(c)
                || CAP_REACHED.equals(c) || NOT_CONFIGURED.equals(c);
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case NOT_CONFIGURED -> HttpStatus.CONFLICT;
            case CAP_REACHED, QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}

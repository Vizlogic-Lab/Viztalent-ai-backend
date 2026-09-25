package com.smartstaff.exception;

import org.springframework.http.HttpStatus;

/** Thrown anywhere in the app to produce the frontend's expected error shape:
 *  {"ok": false, "message": "...", "code": "..."} — see auth.jsx's describeError()
 *  and Login.jsx's handling of code === 'pending_approval'. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}

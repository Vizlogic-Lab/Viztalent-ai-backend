package com.smartstaff.exception;

import com.smartstaff.dto.response.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // A route not implemented yet (later build phases) lands here rather than
    // as a scary 500 — keeps logs readable while modules are still being built.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoRoute(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of("No such endpoint (not implemented yet): " + ex.getResourcePath()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiErrorResponse.of(ex.getMessage(), ex.getCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("Some required fields are missing or invalid.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse.of(message));
    }

    /** Malformed JSON, or a value of the wrong kind (e.g. an unknown question type). */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String message = "The request body isn't valid JSON for this endpoint.";
        if (ex.getCause() instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException f && !f.getPath().isEmpty()) {
            message = "Invalid value for '" + f.getPath().get(f.getPath().size() - 1).getFieldName() + "'.";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse.of(message, "bad_request_body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("Invalid value for '" + ex.getName() + "'."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of("Missing required part: " + ex.getRequestPartName()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorResponse.of("Incorrect credentials."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("You don't have permission to do that."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);
        String requestId = org.slf4j.MDC.get(com.smartstaff.filter.RequestIdFilter.MDC_KEY);
        String message = requestId != null
                ? "Something went wrong (ref " + requestId + ")"
                : "Something went wrong.";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(message));
    }
}

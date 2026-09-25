package com.smartstaff.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.dto.response.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Per-IP, per-minute limits on the endpoints an anonymous caller can hit:
 *
 *  - "auth"  — POST /api/auth/login and /api/auth/signup (password guessing,
 *              signup spam); shared counter.
 *  - "token" — GET /api/interview/by_token/** and POST .../save_by_token/**
 *              (invite-token guessing).
 *
 *  Twilio's webhooks aren't limited here — they're already gated by request
 *  signature, and a single call legitimately produces a burst of them.
 *
 *  Registered inside the Spring Security chain right after CorsFilter (see
 *  SecurityConfig), NOT as a bare servlet filter: that way a 429 still
 *  carries the CORS headers, so the browser lets the frontend read the
 *  message instead of reporting an opaque network error.
 *
 *  Client address is the socket's remote address. Set
 *  app.rate-limit.trust-forwarded-for=true only when running behind a
 *  reverse proxy you control that overwrites X-Forwarded-For — otherwise a
 *  caller could dodge the limit by forging the header. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000;

    private final RateLimiter limiter = new RateLimiter();
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean trustForwardedFor;
    private final int authPerMinute;
    private final int tokenPerMinute;

    public RateLimitFilter(
            ObjectMapper objectMapper,
            @Value("${app.rate-limit.enabled}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-for}") boolean trustForwardedFor,
            @Value("${app.rate-limit.auth-per-minute}") int authPerMinute,
            @Value("${app.rate-limit.token-per-minute}") int tokenPerMinute
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        this.authPerMinute = authPerMinute;
        this.tokenPerMinute = tokenPerMinute;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {
        if (enabled) {
            String bucket = bucketFor(request);
            if (bucket != null) {
                int max = bucket.equals("auth") ? authPerMinute : tokenPerMinute;
                long retryAfter = limiter.tryAcquire(bucket + ":" + clientAddress(request), max, WINDOW_MILLIS);
                if (retryAfter > 0) {
                    log.warn("Rate limit hit on '{}' bucket from {}", bucket, clientAddress(request));
                    response.setStatus(429);
                    response.setHeader("Retry-After", String.valueOf(retryAfter));
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8"); // messages contain non-ASCII (—)
                    response.getWriter().write(objectMapper.writeValueAsString(ApiErrorResponse.of(
                            "Too many requests — please wait a minute and try again.", "rate_limited")));
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** Which limit (if any) applies to this request. */
    private static String bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("POST".equals(method) && (path.equals("/api/auth/login") || path.equals("/api/auth/signup"))) {
            return "auth";
        }
        if (("GET".equals(method) && path.startsWith("/api/interview/by_token/"))
                || ("POST".equals(method) && path.startsWith("/api/interview/save_by_token/"))) {
            return "token";
        }
        return null;
    }

    private String clientAddress(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}

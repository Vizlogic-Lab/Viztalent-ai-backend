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
 *  - "token" — the invite-token endpoints of the interview and assessment
 *              candidate flows (by_token / save_by_token / submit_by_token /
 *              run_by_token), to slow invite-token guessing.
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
 *  reverse proxy you control. Each proxy APPENDS the address it received the
 *  request from, so only the right-hand end of X-Forwarded-For is trustworthy:
 *  the client is the entry app.rate-limit.trusted-proxies positions from the
 *  right (1 = a single proxy such as Dokploy's Traefik). Anything a caller
 *  puts at the front of the header is ignored, so forging it can't reset
 *  their budget. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000;

    private final RateLimiter limiter = new RateLimiter();
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean trustForwardedFor;
    private final int trustedProxies;
    private final int authPerMinute;
    private final int tokenPerMinute;
    private final int runPerMinute;

    public RateLimitFilter(
            ObjectMapper objectMapper,
            @Value("${app.rate-limit.enabled}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-for}") boolean trustForwardedFor,
            @Value("${app.rate-limit.trusted-proxies:1}") int trustedProxies,
            @Value("${app.rate-limit.auth-per-minute}") int authPerMinute,
            @Value("${app.rate-limit.token-per-minute}") int tokenPerMinute,
            @Value("${app.rate-limit.run-per-minute:10}") int runPerMinute
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        this.trustedProxies = Math.max(1, trustedProxies);
        this.authPerMinute = authPerMinute;
        this.tokenPerMinute = tokenPerMinute;
        this.runPerMinute = runPerMinute;
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
                int max = switch (bucket) {
                    case "auth" -> authPerMinute;
                    case "run" -> runPerMinute;
                    default -> tokenPerMinute;
                };
                // Code runs are limited per invite token (one candidate), the rest per client address.
                String key = bucket.equals("run") ? "run:" + runToken(request.getRequestURI()) : bucket + ":" + clientAddress(request);
                long retryAfter = limiter.tryAcquire(key, max, WINDOW_MILLIS);
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
        if ("POST".equals(method) && (path.startsWith("/api/assessment/run_by_token/")
                || (path.startsWith(CANDIDATE) && path.contains("/run/")))) {
            return "run";
        }
        if (("GET".equals(method) && path.startsWith("/api/interview/by_token/"))
                || ("POST".equals(method) && path.startsWith("/api/interview/save_by_token/"))
                || ("GET".equals(method) && path.startsWith("/api/assessment/by_token/"))
                || ("POST".equals(method) && (path.startsWith("/api/assessment/save_by_token/")
                        || path.startsWith("/api/assessment/submit_by_token/")))
                || path.startsWith(CANDIDATE)) {
            return "token";
        }
        return null;
    }

    private static final String CANDIDATE = "/api/candidate/assessment/";

    /** The invite token segment of a run path. */
    static String runToken(String path) {
        String rest = path.startsWith(CANDIDATE) ? path.substring(CANDIDATE.length())
                : path.substring("/api/assessment/run_by_token/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private String clientAddress(HttpServletRequest request) {
        if (trustForwardedFor) {
            String client = clientFromForwarded(request.getHeader("X-Forwarded-For"), trustedProxies);
            if (client != null) return client;
        }
        return request.getRemoteAddr();
    }

    /** The client in an X-Forwarded-For chain: the entry {@code trustedProxies}
     *  positions from the right (or the leftmost, if the chain is shorter).
     *  Null when the header is absent or has no usable entry. */
    static String clientFromForwarded(String header, int trustedProxies) {
        if (header == null || header.isBlank()) return null;
        String[] parts = java.util.Arrays.stream(header.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (parts.length == 0) return null;
        return parts[Math.max(0, parts.length - Math.max(1, trustedProxies))];
    }
}

package com.smartstaff.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Gives every request an id (the caller's X-Request-Id if it's a sane
 *  value, else a fresh one), puts it in the logging MDC — application.yml's
 *  log pattern prints it on every line — echoes it back as a response
 *  header, and writes one access-log line per request:
 *
 *  - 5xx           → WARN
 *  - 4xx or slow   → INFO
 *  - everything else (the frontend polls constantly) → DEBUG
 *
 *  Runs first, ahead of Spring Security, so even auth failures are correlated.
 *
 *  Only the request *path* is ever logged — never the query string or
 *  headers — and invite tokens, which are the credential in
 *  /api/interview/by_token/{token} and .../save_by_token/{token}, are masked. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final long SLOW_REQUEST_MILLIS = 2_000;
    // A client-supplied id goes straight into log lines — allow only a safe alphabet.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String[] SECRET_PATH_PREFIXES = {
            "/api/interview/by_token/",
            "/api/interview/save_by_token/",
    };

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String requestId = (supplied != null && SAFE_ID.matcher(supplied).matches())
                ? supplied
                : UUID.randomUUID().toString().substring(0, 8);

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long startedNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
            logAccess(request, response.getStatus(), elapsedMillis);
            MDC.remove(MDC_KEY);
        }
    }

    private void logAccess(HttpServletRequest request, int status, long elapsedMillis) {
        String path = redact(request.getRequestURI());
        if (status >= 500) {
            log.warn("{} {} -> {} ({} ms)", request.getMethod(), path, status, elapsedMillis);
        } else if (status >= 400 || elapsedMillis >= SLOW_REQUEST_MILLIS) {
            log.info("{} {} -> {} ({} ms)", request.getMethod(), path, status, elapsedMillis);
        } else if (log.isDebugEnabled()) {
            log.debug("{} {} -> {} ({} ms)", request.getMethod(), path, status, elapsedMillis);
        }
    }

    static String redact(String path) {
        for (String prefix : SECRET_PATH_PREFIXES) {
            if (path.startsWith(prefix)) return prefix + "***";
        }
        return path;
    }
}

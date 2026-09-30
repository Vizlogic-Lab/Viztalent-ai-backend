package com.smartstaff.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.dto.response.ApiErrorResponse;
import com.smartstaff.service.DownloadSignatureService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates signatures on download endpoints that require signed URLs.
 * Protects: GET /api/jd/{id}/download, /api/resumes/**, /api/download_report, /api/scorecard/**
 */
@Component
public class DownloadSignatureFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = new HashSet<>(Arrays.asList(
            "/api/jd/",
            "/api/resumes/",
            "/api/download_report",
            "/api/scorecard/"
    ));

    private final DownloadSignatureService signatureService;
    private final ObjectMapper objectMapper;

    public DownloadSignatureFilter(DownloadSignatureService signatureService, ObjectMapper objectMapper) {
        this.signatureService = signatureService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Only protect GET requests on download paths
        if ("GET".equals(method) && isProtectedDownloadPath(path)) {
            String signature = request.getParameter("sig");
            String expiresAt = request.getParameter("exp");

            // Remove query params from path for signature validation
            String pathWithoutQuery = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

            if (signature == null || expiresAt == null || !signatureService.isSignatureValid(pathWithoutQuery, expiresAt, signature)) {
                writeJsonError(response, HttpStatus.FORBIDDEN, "Download link has expired or is invalid.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isProtectedDownloadPath(String path) {
        return PROTECTED_PATHS.stream().anyMatch(path::contains);
    }

    private void writeJsonError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiErrorResponse.of(message)));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        // Don't filter non-GET requests
        return !request.getMethod().equals("GET");
    }
}

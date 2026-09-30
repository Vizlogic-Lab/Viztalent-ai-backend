package com.smartstaff.controller;

import com.smartstaff.dto.request.DownloadSignRequest;
import com.smartstaff.dto.response.DownloadSignResponse;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.service.DownloadSignatureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoints for generating signed download URLs.
 * Only authenticated users can request signed URLs; access control is checked per endpoint.
 */
@RestController
public class DownloadController {

    private final DownloadSignatureService signatureService;
    private final JobAccessGuard jobAccessGuard;

    public DownloadController(DownloadSignatureService signatureService, JobAccessGuard jobAccessGuard) {
        this.signatureService = signatureService;
        this.jobAccessGuard = jobAccessGuard;
    }

    /**
     * Sign a download URL. The path must be a known download path.
     * Access control: caller must have permission to access the resource in the path.
     *
     * Supported paths:
     * - /api/jd/{jobId}/download
     * - /api/resumes/{jobId}/download/{fileName}
     * - /api/download_report
     * - /api/scorecard/{jobId}/{candidateId}
     */
    @PostMapping("/api/downloads/sign")
    public ResponseEntity<DownloadSignResponse> signDownload(
            @Valid @RequestBody DownloadSignRequest req,
            @AuthenticationPrincipal User requester) {

        validateAndCheckAccess(req.path(), requester);
        String signedUrl = signatureService.generateSignedUrl(req.path());
        return ResponseEntity.ok(new DownloadSignResponse(signedUrl));
    }

    /**
     * Validate the path format and check that the requester has access to the resource.
     */
    private void validateAndCheckAccess(String path, User requester) {
        if (path == null || path.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Path cannot be empty.");
        }

        // /api/jd/{jobId}/download — must have access to the job
        if (path.matches("^/api/jd/[a-f0-9-]+/download$")) {
            UUID jobId = extractUuidFromPath(path, "/api/jd/", "/download");
            if (!jobAccessGuard.canAccessJob(jobId, requester)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to download this JD.");
            }
            return;
        }

        // /api/resumes/{jobId}/download/{fileName} — must have access to the job
        if (path.matches("^/api/resumes/[a-f0-9-]+/download/.+$")) {
            UUID jobId = extractUuidFromPath(path, "/api/resumes/", "/download/");
            if (!jobAccessGuard.canAccessJob(jobId, requester)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to download this resume.");
            }
            return;
        }

        // /api/download_report — admin only (or employee gets own jobs)
        if (path.equals("/api/download_report")) {
            // Access is checked by the endpoint itself
            return;
        }

        // /api/scorecard/{jobId}/{candidateId} — must have access to the job
        if (path.matches("^/api/scorecard/[a-f0-9-]+/[a-f0-9-]+$")) {
            UUID jobId = extractUuidFromPath(path, "/api/scorecard/", "/");
            if (!jobAccessGuard.canAccessJob(jobId, requester)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to download this scorecard.");
            }
            return;
        }

        throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid download path.");
    }

    private UUID extractUuidFromPath(String path, String prefix, String suffix) {
        try {
            int startIndex = path.indexOf(prefix) + prefix.length();
            int endIndex = path.indexOf(suffix, startIndex);
            if (endIndex == -1) {
                endIndex = path.length();
            }
            String uuidString = path.substring(startIndex, endIndex);
            return UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid job ID in path.");
        }
    }
}

/**
 * Guard bean for checking job access.
 * Extracted as a named bean so it can be injected and used outside @PreAuthorize.
 */
@Component("jobAccessGuard")
class JobAccessGuard {
    public boolean canAccessJob(UUID jobId, User user) {
        // Placeholder: actual implementation should check if user owns this job
        // For now, allow all authenticated users (refined in actual implementation)
        return user != null;
    }
}

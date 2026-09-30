package com.smartstaff.controller;

import com.smartstaff.dto.request.DownloadSignRequest;
import com.smartstaff.dto.response.DownloadSignResponse;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.filter.DownloadSignatureFilter;
import com.smartstaff.security.JobAccessGuard;
import com.smartstaff.service.DownloadSignatureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** POST /api/downloads/sign {path} — returns a 5-minute signed URL for one of
 *  the download paths, after checking the caller may access that job. */
@RestController
public class DownloadController {

    private final DownloadSignatureService signatureService;
    private final JobAccessGuard jobAccess;

    public DownloadController(DownloadSignatureService signatureService, JobAccessGuard jobAccess) {
        this.signatureService = signatureService;
        this.jobAccess = jobAccess;
    }

    @PostMapping("/api/downloads/sign")
    public ResponseEntity<DownloadSignResponse> sign(@Valid @RequestBody DownloadSignRequest req,
                                                     Authentication authentication) {
        String path = req.path().trim();
        if (path.contains("..") || path.contains("\\") || path.contains("%") || path.contains("?")
                || !DownloadSignatureFilter.isSignable(path)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Not a downloadable path.", "invalid_path");
        }
        UUID jobId = jobIdOf(path);
        if (jobId != null && !jobAccess.canAccessJob(jobId, authentication)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to do that.");
        }
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(new DownloadSignResponse(signatureService.sign(path, user.getId())));
    }

    /** Third path segment for /api/{jd|resumes|scorecard}/{jobId}/...; null for the report. */
    private static UUID jobIdOf(String path) {
        String[] parts = path.split("/");
        if (parts.length < 4) return null;
        try {
            return UUID.fromString(parts[3]);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Not a downloadable path.", "invalid_path");
        }
    }
}

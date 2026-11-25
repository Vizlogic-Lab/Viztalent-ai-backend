package com.smartstaff.controller;

import com.smartstaff.dto.request.RunScreeningRequest;
import com.smartstaff.dto.request.UniversalExecuteRequest;
import com.smartstaff.dto.response.ProgressResponse;
import com.smartstaff.dto.response.RunScreeningResponse;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.service.RecruiterChatService;
import com.smartstaff.service.ScreeningService;
import com.smartstaff.service.impl.ReportXlsxService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ScreeningController {

    private final ScreeningService screeningService;
    private final RecruiterChatService recruiterChatService;
    private final ReportXlsxService reportService;

    public ScreeningController(ScreeningService screeningService, RecruiterChatService recruiterChatService,
                               ReportXlsxService reportService) {
        this.reportService = reportService;
        this.screeningService = screeningService;
        this.recruiterChatService = recruiterChatService;
    }

    @PostMapping("/api/run_screening")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<RunScreeningResponse> runScreening(@Valid @RequestBody RunScreeningRequest req) {
        UUID jobId = parseJobId(req.session_id());
        return ResponseEntity.ok(screeningService.runScreening(jobId));
    }

    /** Re-score the job's candidates with the AI match only; the keyword score is unchanged. */
    @PostMapping("/api/ai_match")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<java.util.Map<String, Object>> aiMatch(@Valid @RequestBody RunScreeningRequest req) {
        int n = screeningService.requestAiMatch(parseJobId(req.session_id()));
        return ResponseEntity.ok(java.util.Map.of("queued", n));
    }

    /** Recruiter override after discussion: body {file_name, passed, reason}. A reason is required to pass. */
    @PostMapping("/api/jobs/{jobId}/candidates/override")
    // Admins and the job's owner (the employer) only; reviewers never override screening.
    @PreAuthorize("hasAnyRole('ADMIN','USER') and @jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<com.smartstaff.dto.response.CandidateRowResponse> overrideCandidate(
            @PathVariable UUID jobId, @RequestBody java.util.Map<String, Object> body,
            @AuthenticationPrincipal User requester) {
        String fileName = body == null ? null : (String) body.get("file_name");
        if (fileName == null || fileName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_name is required");
        }
        boolean passed = !Boolean.FALSE.equals(body.get("passed"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return ResponseEntity.ok(screeningService.overrideCandidate(jobId, fileName, passed, reason, requester));
    }

    @GetMapping("/api/progress")
    public ResponseEntity<ProgressResponse> progress() {
        return ResponseEntity.ok(screeningService.progress());
    }

    @PostMapping("/api/universal_execute")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<RunScreeningResponse> universalExecute(@Valid @RequestBody UniversalExecuteRequest req,
                                                                   @AuthenticationPrincipal User requester) {
        return ResponseEntity.ok(recruiterChatService.execute(req, requester));
    }

    @GetMapping("/api/download_report")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> downloadReport(@AuthenticationPrincipal User requester) {
        // F14: .xlsx, scoped by the service: admin = all jobs, employee = own jobs only.
        byte[] xlsx = reportService.report(requester);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"viztalent-report.xlsx\"")
                .body(xlsx);
    }

    private UUID parseJobId(String sessionId) {
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No job description found for this session — upload a JD first.");
        }
    }
}

package com.smartstaff.controller;

import com.smartstaff.dto.request.RunScreeningRequest;
import com.smartstaff.dto.request.UniversalExecuteRequest;
import com.smartstaff.dto.response.ProgressResponse;
import com.smartstaff.dto.response.RunScreeningResponse;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.service.RecruiterChatService;
import com.smartstaff.service.ScreeningService;
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

    public ScreeningController(ScreeningService screeningService, RecruiterChatService recruiterChatService) {
        this.screeningService = screeningService;
        this.recruiterChatService = recruiterChatService;
    }

    @PostMapping("/api/run_screening")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<RunScreeningResponse> runScreening(@Valid @RequestBody RunScreeningRequest req) {
        UUID jobId = parseJobId(req.session_id());
        return ResponseEntity.ok(screeningService.runScreening(jobId));
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
    public ResponseEntity<byte[]> downloadReport(@AuthenticationPrincipal User requester) {
        byte[] csv = screeningService.downloadReportCsv(requester);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"viztalent-report.csv\"")
                .body(csv);
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

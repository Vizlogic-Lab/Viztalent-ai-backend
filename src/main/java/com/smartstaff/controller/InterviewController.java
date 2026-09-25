package com.smartstaff.controller;

import com.smartstaff.dto.request.InterviewInviteMintRequest;
import com.smartstaff.dto.request.InterviewPrepareRequest;
import com.smartstaff.dto.request.InterviewSaveByTokenRequest;
import com.smartstaff.dto.request.InterviewSaveRequest;
import com.smartstaff.dto.request.PlaceCallRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;
import com.smartstaff.service.InterviewService;
import com.smartstaff.service.PhoneInterviewService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private final InterviewService interviewService;
    private final PhoneInterviewService phoneInterviewService;

    public InterviewController(InterviewService interviewService, PhoneInterviewService phoneInterviewService) {
        this.interviewService = interviewService;
        this.phoneInterviewService = phoneInterviewService;
    }

    @GetMapping("/config")
    public ResponseEntity<InterviewConfigResponse> config() {
        return ResponseEntity.ok(interviewService.config());
    }

    @PostMapping("/prepare")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<InterviewPrepareResponse> prepare(@Valid @RequestBody InterviewPrepareRequest req) {
        return ResponseEntity.ok(interviewService.prepare(req));
    }

    @PostMapping("/invites/mint")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<InterviewMintResponse> mintInvite(@Valid @RequestBody InterviewInviteMintRequest req,
                                                              @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(interviewService.mintInvite(req, admin));
    }

    @PostMapping("/save")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<SimpleResponse> save(@Valid @RequestBody InterviewSaveRequest req) {
        return ResponseEntity.ok(interviewService.save(req));
    }

    @GetMapping("/transcripts/{jobId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<InterviewTranscriptsResponse> transcripts(@PathVariable UUID jobId) {
        return ResponseEntity.ok(interviewService.transcripts(jobId));
    }

    @PostMapping("/place_call")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication) and @jobAccess.canAccessInterviewId(#req.interview_id(), authentication)")
    public ResponseEntity<PlaceCallResponse> placeCall(@Valid @RequestBody PlaceCallRequest req) {
        return ResponseEntity.ok(phoneInterviewService.placeCall(req));
    }

    @GetMapping("/call_status/{interviewId}")
    @PreAuthorize("@jobAccess.canAccessInterview(#interviewId, authentication)")
    public ResponseEntity<CallStatusResponse> callStatus(@PathVariable UUID interviewId) {
        return ResponseEntity.ok(phoneInterviewService.callStatus(interviewId));
    }

    // ── Public, token-based (candidate-facing) ─────────────────────────

    @GetMapping("/by_token/{token}")
    public ResponseEntity<InterviewPrepareResponse> byToken(@PathVariable String token) {
        return ResponseEntity.ok(interviewService.byToken(token));
    }

    @PostMapping("/save_by_token/{token}")
    public ResponseEntity<SimpleResponse> saveByToken(@PathVariable String token,
                                                        @Valid @RequestBody InterviewSaveByTokenRequest req) {
        return ResponseEntity.ok(interviewService.saveByToken(token, req));
    }
}

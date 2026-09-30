package com.smartstaff.controller;

import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.request.AssessmentRunRequest;
import com.smartstaff.dto.request.AssessmentSaveRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;
import com.smartstaff.service.AssessmentService;
import com.smartstaff.service.CandidateAssessmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class AssessmentController {

    private final AssessmentService assessmentService;
    private final CandidateAssessmentService candidateAssessmentService;

    public AssessmentController(AssessmentService assessmentService,
                                CandidateAssessmentService candidateAssessmentService) {
        this.assessmentService = assessmentService;
        this.candidateAssessmentService = candidateAssessmentService;
    }

    @GetMapping("/api/assessment/submissions/{jobId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<AssessmentSubmissionsResponse> submissions(@PathVariable UUID jobId) {
        return ResponseEntity.ok(assessmentService.submissions(jobId));
    }

    @GetMapping("/api/assessment/status/{jobId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<AssessmentStatusResponse> status(@PathVariable UUID jobId) {
        return ResponseEntity.ok(assessmentService.status(jobId));
    }

    @PostMapping("/api/assessment/generate")
    @PreAuthorize("@jobAccess.canAccessSession(#req.session_id(), authentication)")
    public ResponseEntity<AssessmentGenerateResponse> generate(@Valid @RequestBody AssessmentGenerateRequest req,
                                                                 @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(assessmentService.generate(req, admin));
    }

    @GetMapping("/api/assessment/answer_key/{jobId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<AnswerKeyResponse> answerKey(@PathVariable UUID jobId) {
        return ResponseEntity.ok(assessmentService.answerKey(jobId));
    }

    @GetMapping("/api/assessment/scorecard/{jobId}/{attemptId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<ScorecardResponse> scorecard(@PathVariable UUID jobId, @PathVariable UUID attemptId) {
        return ResponseEntity.ok(assessmentService.scorecard(jobId, attemptId));
    }

    @PostMapping("/api/assessment/rescore/{jobId}/{attemptId}")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<SimpleResponse> rescore(@PathVariable UUID jobId, @PathVariable UUID attemptId) {
        assessmentService.rescore(jobId, attemptId);
        return ResponseEntity.ok(SimpleResponse.OK);
    }

    // ── Public, token-based (candidate-facing) ──────────────────────────

    @GetMapping("/api/assessment/by_token/{token}")
    public ResponseEntity<CandidateAssessmentResponse> byToken(@PathVariable String token) {
        return ResponseEntity.ok(candidateAssessmentService.byToken(token));
    }

    @PostMapping("/api/assessment/save_by_token/{token}")
    public ResponseEntity<CandidateAssessmentResponse> saveByToken(@PathVariable String token,
                                                                    @Valid @RequestBody AssessmentSaveRequest req) {
        return ResponseEntity.ok(candidateAssessmentService.save(token, req));
    }

    @PostMapping("/api/assessment/submit_by_token/{token}")
    public ResponseEntity<CandidateAssessmentResponse> submitByToken(@PathVariable String token,
                                                                      @Valid @RequestBody AssessmentSaveRequest req) {
        return ResponseEntity.ok(candidateAssessmentService.submit(token, req));
    }

    @PostMapping("/api/assessment/run_by_token/{token}")
    public ResponseEntity<AssessmentRunResponse> runByToken(@PathVariable String token,
                                                             @Valid @RequestBody AssessmentRunRequest req) {
        return ResponseEntity.ok(candidateAssessmentService.run(token, req));
    }
}

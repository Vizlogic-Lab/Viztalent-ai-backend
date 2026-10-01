package com.smartstaff.controller;

import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.request.AssessmentRunRequest;
import com.smartstaff.dto.request.AssessmentSaveRequest;
import com.smartstaff.dto.request.QuestionEditRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;
import com.smartstaff.service.AssessmentQuestionService;
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
    private final AssessmentQuestionService questionService;

    public AssessmentController(AssessmentService assessmentService,
                                CandidateAssessmentService candidateAssessmentService,
                                AssessmentQuestionService questionService) {
        this.assessmentService = assessmentService;
        this.candidateAssessmentService = candidateAssessmentService;
        this.questionService = questionService;
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
    public ResponseEntity<AnswerKeyResponse> answerKey(@PathVariable UUID jobId,
                                                       @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok(assessmentService.answerKey(jobId, version));
    }

    @PostMapping("/api/assessment/questions/{questionId}/regenerate")
    @PreAuthorize("@jobAccess.canAccessQuestion(#questionId, authentication)")
    public ResponseEntity<AssessmentQuestionResponse> regenerateQuestion(@PathVariable UUID questionId) {
        return ResponseEntity.ok(questionService.regenerate(questionId));
    }

    @PutMapping("/api/assessment/questions/{questionId}")
    @PreAuthorize("@jobAccess.canAccessQuestion(#questionId, authentication)")
    public ResponseEntity<AssessmentQuestionResponse> editQuestion(@PathVariable UUID questionId,
                                                                   @RequestBody QuestionEditRequest req) {
        return ResponseEntity.ok(questionService.edit(questionId, req));
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

    // Aliases kept from before the /api/candidate/assessment/{token} API (F7);
    // they call the same service so both stay consistent.

    @GetMapping("/api/assessment/by_token/{token}")
    public ResponseEntity<CandidateAssessmentResponse> byToken(@PathVariable String token) {
        return ResponseEntity.ok(candidateAssessmentService.start(token));
    }

    @PostMapping("/api/assessment/save_by_token/{token}")
    public ResponseEntity<CandidateAssessmentResponse> saveByToken(@PathVariable String token,
                                                                    @Valid @RequestBody AssessmentSaveRequest req) {
        return ResponseEntity.ok(candidateAssessmentService.saveAll(token, req));
    }

    @PostMapping("/api/assessment/submit_by_token/{token}")
    public ResponseEntity<CandidateAssessmentResponse> submitByToken(@PathVariable String token,
                                                                      @Valid @RequestBody AssessmentSaveRequest req) {
        return ResponseEntity.ok(candidateAssessmentService.submit(token, req));
    }

    @PostMapping("/api/assessment/run_by_token/{token}")
    public ResponseEntity<AssessmentRunResponse> runByToken(@PathVariable String token,
                                                             @Valid @RequestBody AssessmentRunRequest req) {
        UUID questionId;
        try {
            questionId = UUID.fromString(req.question_id().trim());
        } catch (IllegalArgumentException e) {
            throw new com.smartstaff.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown question.");
        }
        return ResponseEntity.ok(candidateAssessmentService.run(token, questionId, req.language(), req.code()));
    }
}

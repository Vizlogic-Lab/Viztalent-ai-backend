package com.smartstaff.controller;

import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.response.AnswerKeyResponse;
import com.smartstaff.dto.response.AssessmentGenerateResponse;
import com.smartstaff.dto.response.AssessmentStatusResponse;
import com.smartstaff.dto.response.AssessmentSubmissionsResponse;
import com.smartstaff.entity.User;
import com.smartstaff.service.AssessmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class AssessmentController {

    private final AssessmentService assessmentService;

    public AssessmentController(AssessmentService assessmentService) {
        this.assessmentService = assessmentService;
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
}

package com.smartstaff.controller;

import com.smartstaff.dto.request.CustomAssessmentCreateRequest;
import com.smartstaff.dto.request.CustomQuestionRequest;
import com.smartstaff.dto.request.MarkingSchemeRequest;
import com.smartstaff.dto.request.SectionGenerateRequest;
import com.smartstaff.dto.response.CustomAssessmentResponse;
import com.smartstaff.dto.response.CustomAssessmentSummary;
import com.smartstaff.entity.User;
import com.smartstaff.service.impl.CustomAssessmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Hand-authored 50-question assessments of a job (see {@link CustomAssessmentService}).
 *  Candidate links for a published one come from the usual invite endpoint at level L1. */
@RestController
@RequestMapping("/api/jobs/{jobId}/custom-assessments")
@PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
public class CustomAssessmentController {

    private final CustomAssessmentService service;

    public CustomAssessmentController(CustomAssessmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<CustomAssessmentSummary> list(@PathVariable UUID jobId) {
        return service.list(jobId);
    }

    @PostMapping
    public ResponseEntity<CustomAssessmentResponse> create(@PathVariable UUID jobId,
                                                           @Valid @RequestBody CustomAssessmentCreateRequest req,
                                                           @AuthenticationPrincipal User admin) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(jobId, req, admin));
    }

    @GetMapping("/{id}")
    public CustomAssessmentResponse get(@PathVariable UUID jobId, @PathVariable UUID id) {
        return service.get(jobId, id);
    }

    @PutMapping("/{id}/marking-scheme")
    public CustomAssessmentResponse markingScheme(@PathVariable UUID jobId, @PathVariable UUID id,
                                                  @Valid @RequestBody MarkingSchemeRequest req) {
        return service.updateScheme(jobId, id, req);
    }

    @PostMapping("/{id}/questions")
    public ResponseEntity<CustomAssessmentResponse> addQuestion(@PathVariable UUID jobId, @PathVariable UUID id,
                                                                @Valid @RequestBody CustomQuestionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addQuestion(jobId, id, req));
    }

    @PutMapping("/{id}/questions/{questionId}")
    public CustomAssessmentResponse updateQuestion(@PathVariable UUID jobId, @PathVariable UUID id,
                                                   @PathVariable UUID questionId,
                                                   @Valid @RequestBody CustomQuestionRequest req) {
        return service.updateQuestion(jobId, id, questionId, req);
    }

    @DeleteMapping("/{id}/questions/{questionId}")
    public CustomAssessmentResponse deleteQuestion(@PathVariable UUID jobId, @PathVariable UUID id,
                                                   @PathVariable UUID questionId) {
        return service.deleteQuestion(jobId, id, questionId);
    }

    /** "Generate by AI" for one section (MCQ, ONE_WORD, SCENARIO or PROJECT). */
    @PostMapping("/{id}/sections/generate")
    public CustomAssessmentResponse generateSection(@PathVariable UUID jobId, @PathVariable UUID id,
                                                    @Valid @RequestBody SectionGenerateRequest req) {
        return service.generateSection(jobId, id, req);
    }

    @PostMapping("/{id}/publish")
    public CustomAssessmentResponse publish(@PathVariable UUID jobId, @PathVariable UUID id,
                                            @AuthenticationPrincipal User admin) {
        return service.publish(jobId, id, admin);
    }

    @PostMapping("/{id}/archive")
    public CustomAssessmentResponse archive(@PathVariable UUID jobId, @PathVariable UUID id,
                                            @AuthenticationPrincipal User admin) {
        return service.archive(jobId, id, admin);
    }
}

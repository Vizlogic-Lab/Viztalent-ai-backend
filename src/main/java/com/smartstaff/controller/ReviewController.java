package com.smartstaff.controller;

import com.smartstaff.dto.request.QuestionEditRequest;
import com.smartstaff.dto.response.AssessmentQuestionResponse;
import com.smartstaff.entity.User;
import com.smartstaff.service.impl.ReviewService;
import com.smartstaff.service.impl.ReviewerAssignmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** F11 reviewer assignment (HR/admin, per job) and the F12 SME review endpoints.
 *  /api/review/** is open to ADMIN and REVIEWER only (SecurityConfig); each
 *  review action is further limited to reviewers assigned to that job. */
@RestController
public class ReviewController {

    private final ReviewerAssignmentService assignments;
    private final ReviewService reviews;

    public ReviewController(ReviewerAssignmentService assignments, ReviewService reviews) {
        this.assignments = assignments;
        this.reviews = reviews;
    }

    // ── F11: job ↔ reviewers (job owner or admin) ───────────────────────

    @GetMapping("/api/jobs/{jobId}/reviewers")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<Map<String, Object>> reviewers(@PathVariable UUID jobId) {
        return ResponseEntity.ok(assignments.get(jobId));
    }

    @PutMapping("/api/jobs/{jobId}/reviewers")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<Map<String, Object>> assign(@PathVariable UUID jobId,
                                                      @RequestBody ReviewerAssignmentService.AssignRequest req,
                                                      @AuthenticationPrincipal User requester) {
        return ResponseEntity.ok(assignments.assign(jobId, req, requester));
    }

    @GetMapping("/api/jobs/{jobId}/reviewers/suggest")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<Map<String, Object>> suggest(@PathVariable UUID jobId) {
        return ResponseEntity.ok(assignments.suggest(jobId));
    }

    // ── F12: the SME's queue and actions ────────────────────────────────

    @GetMapping("/api/review/queue")
    public ResponseEntity<Map<String, Object>> queue(@AuthenticationPrincipal User actor,
                                                     @RequestParam(name = "job_id", required = false) UUID jobId,
                                                     @RequestParam(name = "subject_type", required = false) String subjectType,
                                                     @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(reviews.queue(actor, jobId, subjectType, status));
    }

    @GetMapping("/api/review/overdue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> overdue() {
        return ResponseEntity.ok(reviews.overdue());
    }

    @GetMapping("/api/review/{reviewId}")
    @PreAuthorize("@jobAccess.canReviewReview(#reviewId, authentication)")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID reviewId) {
        return ResponseEntity.ok(reviews.get(reviewId));
    }

    @PostMapping("/api/review/{reviewId}/claim")
    @PreAuthorize("@jobAccess.canReviewReview(#reviewId, authentication)")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable UUID reviewId, @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(reviews.claim(reviewId, actor));
    }

    @PostMapping("/api/review/attempts/{reviewId}")
    @PreAuthorize("@jobAccess.canReviewReview(#reviewId, authentication)")
    public ResponseEntity<Map<String, Object>> decideAttempt(@PathVariable UUID reviewId,
                                                             @RequestBody ReviewService.AttemptDecision req,
                                                             @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(reviews.decideAttempt(reviewId, req, actor));
    }

    @PostMapping("/api/review/question_sets/{reviewId}")
    @PreAuthorize("@jobAccess.canReviewReview(#reviewId, authentication)")
    public ResponseEntity<Map<String, Object>> decideQuestionSet(@PathVariable UUID reviewId,
                                                                 @RequestBody ReviewService.QuestionSetDecision req,
                                                                 @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(reviews.decideQuestionSet(reviewId, req, actor));
    }

    @PutMapping("/api/review/question_sets/{reviewId}/questions/{questionId}")
    @PreAuthorize("@jobAccess.canReviewReview(#reviewId, authentication)")
    public ResponseEntity<AssessmentQuestionResponse> editQuestion(@PathVariable UUID reviewId, @PathVariable UUID questionId,
                                                                   @RequestBody QuestionEditRequest req,
                                                                   @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(reviews.editQuestion(reviewId, questionId, req, actor));
    }

    @PostMapping("/api/review/question_sets/{reviewId}/questions/{questionId}/regenerate")
    @PreAuthorize("@jobAccess.canReviewReview(#reviewId, authentication)")
    public ResponseEntity<AssessmentQuestionResponse> regenerateQuestion(@PathVariable UUID reviewId, @PathVariable UUID questionId,
                                                                         @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(reviews.regenerateQuestion(reviewId, questionId, actor));
    }

    @PostMapping("/api/review/{reviewId}/reopen")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> reopen(@PathVariable UUID reviewId, @RequestBody ReviewService.Reopen req,
                                                      @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(reviews.reopen(reviewId, req, actor));
    }
}

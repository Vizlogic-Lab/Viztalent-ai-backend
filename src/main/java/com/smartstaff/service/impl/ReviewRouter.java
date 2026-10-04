package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.JobReviewerRepository;
import com.smartstaff.repository.ReviewAuditRepository;
import com.smartstaff.repository.SmeReviewRepository;
import com.smartstaff.util.ReviewRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** F12 hooks the generation, invite and evaluation code calls (inside their own
 *  transactions): open a question-set review, refuse invites until it is
 *  approved, and route a fresh result to an SME or validate it by sampling.
 *  Repositories only, so it can sit under AssessmentGenerationJob without a cycle. */
@Component
public class ReviewRouter {

    static final int REVIEW_WORKING_DAYS = 2;

    private final ActivityRecorder activity;
    private final SmeReviewRepository reviews;
    private final ReviewAuditRepository audit;
    private final JobReviewerRepository jobReviewers;

    public ReviewRouter(SmeReviewRepository reviews, ReviewAuditRepository audit, JobReviewerRepository jobReviewers,
            ActivityRecorder activity) {
        this.activity = activity;
        this.reviews = reviews;
        this.audit = audit;
        this.jobReviewers = jobReviewers;
    }

    /** A version just became READY. Applies to versions that become READY after the policy is switched on. */
    public void questionSetReady(Assessment assessment) {
        Job job = assessment.getJob();
        if (!ReviewPolicy.orDefaults(job.getReviewPolicy()).question_set_approval_required()) return;
        if (reviews.findBySubjectTypeAndSubjectId(SmeReview.Subject.QUESTION_SET, assessment.getId()).isPresent()) return;
        SmeReview r = reviews.save(new SmeReview(SmeReview.Subject.QUESTION_SET, assessment.getId(), job.getId(),
                ReviewRules.addWorkingDays(Instant.now(), REVIEW_WORKING_DAYS)));
        audit.save(new ReviewAudit(r.getId(), null, "CREATED", null, Map.of("version", assessment.getVersion())));
    }

    /** 409 while the version's question-set review is open (or was returned). */
    public void requireApproved(Assessment assessment) {
        reviews.findBySubjectTypeAndSubjectId(SmeReview.Subject.QUESTION_SET, assessment.getId())
                .filter(r -> r.getStatus() != SmeReview.Status.APPROVED)
                .ifPresent(r -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "This question set is awaiting expert approval.", "awaiting_expert_approval");
                });
    }

    /** Called when an attempt has just become EVALUATED (result computed). Jobs
     *  without an assigned reviewer keep the provisional result as before. */
    public void routeResult(AssessmentAttempt a) {
        switch (route(a.getJob(), SmeReview.Subject.ATTEMPT, a.getId(), a.getRecommendation(), a.getTheoryScore())) {
            case NONE -> { }
            case REVIEW -> {
                a.setStatus(AttemptStatus.AWAITING_REVIEW);
                a.setValidationReason(null);
            }
            case SAMPLED -> {
                a.setStatus(AttemptStatus.VALIDATED);
                a.setValidationReason("sampling");
                activity.record(a.getJob().getId(), ActivityRecorder.VALIDATION, ActivityRecorder.who(a.getCandidateName(), a.getCandidateEmail())
                        + " result validated without SME review (sampling): " + a.getRecommendation(), a.getId());
            }
        }
    }

    /** F16: the same routing for a freshly graded L1 interview. */
    public void routeInterview(Interview iv) {
        switch (route(iv.getJob(), SmeReview.Subject.INTERVIEW, iv.getId(), iv.getRecommendation(), iv.getTheoryScore())) {
            case NONE -> { }
            case REVIEW -> {
                iv.setEvaluationStatus("AWAITING_REVIEW");
                iv.setValidationReason(null);
            }
            case SAMPLED -> {
                iv.setEvaluationStatus("VALIDATED");
                iv.setValidationReason("sampling");
                activity.record(iv.getJob().getId(), ActivityRecorder.VALIDATION, "Interview with " + iv.getCandidateName()
                        + " validated without SME review (sampling): " + iv.getRecommendation(), iv.getId());
            }
        }
    }

    private enum Route { NONE, REVIEW, SAMPLED }

    /** NONE: no reviewer on the job. REVIEW: an open review exists (a re-grade keeps it) or one was
     *  just opened by policy. SAMPLED: validated without a reviewer. */
    private Route route(Job job, SmeReview.Subject subject, UUID subjectId, String recommendation, BigDecimal theory) {
        if (!jobReviewers.existsByJobId(job.getId())) return Route.NONE;
        SmeReview open = reviews.findBySubjectTypeAndSubjectId(subject, subjectId).orElse(null);
        if (open != null && !open.getStatus().isClosed()) return Route.REVIEW;
        if (!ReviewRules.needsReview(recommendation, theory, subjectId, job.getReviewPolicy())) return Route.SAMPLED;
        SmeReview r = open != null ? open : new SmeReview(subject, subjectId, job.getId(), null);
        Map<String, Object> before = open == null ? null : Map.of("status", open.getStatus().name());
        r.setStatus(SmeReview.Status.PENDING);
        r.setReviewerId(null);
        r.setSignedOffAt(null);
        r.setDueAt(ReviewRules.addWorkingDays(Instant.now(), REVIEW_WORKING_DAYS));
        r = reviews.save(r);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("recommendation", recommendation);
        after.put("theory_score", theory);
        audit.save(new ReviewAudit(r.getId(), null, "CREATED", before, after));
        return Route.REVIEW;
    }
}

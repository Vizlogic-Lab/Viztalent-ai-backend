package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.JobReviewerRepository;
import com.smartstaff.repository.ReviewAuditRepository;
import com.smartstaff.repository.SmeReviewRepository;
import com.smartstaff.util.ReviewRules;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** F12 hooks the generation, invite and evaluation code calls (inside their own
 *  transactions): open a question-set review, refuse invites until it is
 *  approved, and route a fresh result to an SME or validate it by sampling.
 *  Repositories only, so it can sit under AssessmentGenerationJob without a cycle. */
@Component
public class ReviewRouter {

    static final int REVIEW_WORKING_DAYS = 2;

    private final SmeReviewRepository reviews;
    private final ReviewAuditRepository audit;
    private final JobReviewerRepository jobReviewers;

    public ReviewRouter(SmeReviewRepository reviews, ReviewAuditRepository audit, JobReviewerRepository jobReviewers) {
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
        Job job = a.getJob();
        if (!jobReviewers.existsByJobId(job.getId())) return;
        SmeReview open = reviews.findBySubjectTypeAndSubjectId(SmeReview.Subject.ATTEMPT, a.getId()).orElse(null);
        if (open != null && !open.getStatus().isClosed()) {
            a.setStatus(AttemptStatus.AWAITING_REVIEW);   // re-score while waiting: keep the same review
            return;
        }
        if (ReviewRules.needsReview(a.getRecommendation(), a.getTheoryScore(), a.getId(), job.getReviewPolicy())) {
            a.setStatus(AttemptStatus.AWAITING_REVIEW);
            a.setValidationReason(null);
            SmeReview r = open != null ? open : new SmeReview(SmeReview.Subject.ATTEMPT, a.getId(), job.getId(), null);
            Map<String, Object> before = open == null ? null : Map.of("status", open.getStatus().name());
            r.setStatus(SmeReview.Status.PENDING);
            r.setReviewerId(null);
            r.setSignedOffAt(null);
            r.setDueAt(ReviewRules.addWorkingDays(Instant.now(), REVIEW_WORKING_DAYS));
            r = reviews.save(r);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("recommendation", a.getRecommendation());
            after.put("theory_score", a.getTheoryScore());
            audit.save(new ReviewAudit(r.getId(), null, "CREATED", before, after));
        } else {
            a.setStatus(AttemptStatus.VALIDATED);
            a.setValidationReason("sampling");
        }
    }
}

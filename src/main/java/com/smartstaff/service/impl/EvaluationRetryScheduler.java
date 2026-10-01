package com.smartstaff.service.impl;

import com.smartstaff.repository.AssessmentAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Keeps evaluation moving (F9), once a minute:
 *  - attempts stuck in SUBMITTED for more than 5 minutes are evaluated;
 *  - evaluations waiting on Gemini or the code runner are retried when due
 *    (backoff, up to the retry window, then flagged *_UNAVAILABLE);
 *  - evaluations that started long ago and never finished are redone.
 *  Off in tests, which call sweep() directly. */
@Component
public class EvaluationRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRetryScheduler.class);

    private final AssessmentAttemptRepository attempts;
    private final EvaluationJob job;
    private final boolean enabled;
    private final Duration stuckAfter;

    public EvaluationRetryScheduler(AssessmentAttemptRepository attempts, EvaluationJob job,
                                    @Value("${app.evaluation.retry-enabled:true}") boolean enabled,
                                    @Value("${app.evaluation.stuck-after-minutes:5}") long stuckAfterMinutes) {
        this.attempts = attempts;
        this.job = job;
        this.enabled = enabled;
        this.stuckAfter = Duration.ofMinutes(stuckAfterMinutes);
    }

    @Scheduled(fixedDelayString = "${app.evaluation.sweep-interval-ms:60000}",
               initialDelayString = "${app.evaluation.sweep-interval-ms:60000}")
    public void scheduled() {
        if (!enabled) return;
        try {
            int n = sweep();
            if (n > 0) log.info("Evaluation sweep graded {} attempt(s)", n);
        } catch (Exception e) {
            log.error("Evaluation sweep failed", e);
        }
    }

    /** One pass; returns how many attempts were graded. */
    public int sweep() {
        Instant now = Instant.now();
        int n = 0;
        for (UUID id : attempts.findStuckSubmitted(now.minus(stuckAfter))) {
            if (job.evaluateNow(id, EvaluationJob.Mode.FULL)) n++;
        }
        for (UUID id : attempts.findDueRetries(now)) {
            if (job.evaluateNow(id, EvaluationJob.Mode.RETRY_PENDING)) n++;
        }
        for (UUID id : attempts.findStaleEvaluations(now.minus(Duration.ofMinutes(15)))) {
            if (job.evaluateNow(id, EvaluationJob.Mode.FULL)) n++;
        }
        return n;
    }
}

package com.smartstaff.service.impl;

import com.smartstaff.service.CandidateAssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Submits in-progress attempts whose deadline has passed, once a minute —
 *  so an abandoned tab still ends up graded. Off in tests, which call
 *  CandidateAssessmentService.autoSubmitOverdue() directly. */
@Component
public class AttemptDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(AttemptDeadlineScheduler.class);

    private final CandidateAssessmentService service;
    private final boolean enabled;

    public AttemptDeadlineScheduler(CandidateAssessmentService service,
                                    @Value("${app.assessment.auto-submit-enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.assessment.auto-submit-interval-ms:60000}",
               initialDelayString = "${app.assessment.auto-submit-interval-ms:60000}")
    public void autoSubmit() {
        if (!enabled) return;
        try {
            int n = service.autoSubmitOverdue();
            if (n > 0) log.info("Auto-submitted {} assessment attempt(s) past their deadline", n);
        } catch (Exception e) {
            log.error("Auto-submit sweep failed", e);
        }
    }
}

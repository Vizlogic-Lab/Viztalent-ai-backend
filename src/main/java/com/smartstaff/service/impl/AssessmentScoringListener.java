package com.smartstaff.service.impl;

import com.smartstaff.service.AttemptSubmittedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AssessmentScoringListener {

    private final AssessmentScoringJob job;

    public AssessmentScoringListener(AssessmentScoringJob job) {
        this.job = job;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(AttemptSubmittedEvent event) {
        job.run(event.attemptId());
    }
}

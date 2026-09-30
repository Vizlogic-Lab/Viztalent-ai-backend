package com.smartstaff.service.impl;

import com.smartstaff.service.AssessmentQueuedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AssessmentGenerationListener {

    private final AssessmentGenerationJob job;

    public AssessmentGenerationListener(AssessmentGenerationJob job) {
        this.job = job;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(AssessmentQueuedEvent event) {
        job.run(event.assessmentId());
    }
}

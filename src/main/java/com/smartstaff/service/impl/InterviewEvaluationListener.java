package com.smartstaff.service.impl;

import com.smartstaff.service.InterviewCompletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class InterviewEvaluationListener {

    private final InterviewEvaluationJob job;

    public InterviewEvaluationListener(InterviewEvaluationJob job) {
        this.job = job;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompleted(InterviewCompletedEvent event) {
        job.run(event.interviewId());
    }
}

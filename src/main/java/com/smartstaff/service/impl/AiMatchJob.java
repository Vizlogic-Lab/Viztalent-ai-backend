package com.smartstaff.service.impl;

import com.smartstaff.entity.Candidate;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.service.AiMatchRequestedEvent;
import com.smartstaff.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runs the AI CV match for a job's PENDING candidates in the background, one at a time, after
 *  screening has committed. A failure marks only that candidate FAILED; with no Gemini key every
 *  pending candidate is marked OFF. The keyword score is never touched. */
@Component
public class AiMatchJob {

    private static final Logger log = LoggerFactory.getLogger(AiMatchJob.class);

    public static final String PENDING = "PENDING", DONE = "DONE", FAILED = "FAILED", OFF = "OFF",
            /** Not AI-matched because the candidate failed step 1 (experience). */
            SKIPPED = "SKIPPED";

    private final CandidateRepository candidates;
    private final SettingsService settings;
    private final CvMatcher matcher;
    private final TransactionTemplate tx;

    public AiMatchJob(CandidateRepository candidates, SettingsService settings, CvMatcher matcher,
                      PlatformTransactionManager txManager) {
        this.candidates = candidates;
        this.settings = settings;
        this.matcher = matcher;
        this.tx = new TransactionTemplate(txManager);
    }

    @Async("scoringExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(AiMatchRequestedEvent event) {
        run(event.jobId());
    }

    record Work(UUID candidateId, String jd, String cv) {}

    public void run(UUID jobId) {
        List<Work> work = tx.execute(s -> candidates.findByJobIdOrderByFitScoreDesc(jobId).stream()
                .filter(c -> PENDING.equals(c.getAiMatchStatus()))
                .map(c -> new Work(c.getId(), c.getJob().getJdText(), c.getResume().getExtractedText()))
                .toList());
        if (work == null || work.isEmpty()) return;
        String apiKey = settings.getGeminiApiKeyOrNull();
        for (Work w : work) {
            if (apiKey == null || apiKey.isBlank()) {
                save(w.candidateId(), OFF, null);
                continue;
            }
            try {
                save(w.candidateId(), DONE, matcher.match(apiKey, w.jd(), w.cv()));
            } catch (Exception e) {
                log.warn("AI CV match failed for candidate {}: {}", w.candidateId(), e.getMessage());
                save(w.candidateId(), FAILED, null);
            }
        }
    }

    private void save(UUID candidateId, String status, CvMatcher.Result r) {
        tx.executeWithoutResult(s -> candidates.findById(candidateId).ifPresent(c -> {
            c.setAiMatchStatus(status);
            if (r != null) {
                c.setAiMatchScore(r.score());
                c.setAiMatchSummary(r.summary());
                c.setAiMatchStrengths(r.strengths());
                c.setAiMatchGaps(r.gaps());
            }
        }));
    }

    /** Marks candidates PENDING and clears old AI results. Call inside a transaction. */
    public static void markPending(List<Candidate> list) {
        for (Candidate c : list) {
            c.setAiMatchStatus(PENDING);
            c.setAiMatchScore(null);
            c.setAiMatchSummary(null);
            c.setAiMatchStrengths(new ArrayList<>());
            c.setAiMatchGaps(new ArrayList<>());
        }
    }
}

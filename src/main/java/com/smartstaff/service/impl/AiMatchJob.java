package com.smartstaff.service.impl;

import com.smartstaff.entity.Candidate;
import com.smartstaff.entity.Job;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.service.AiMatchRequestedEvent;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.SkillEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Runs the AI check for a job's PENDING candidates in the background, one at a time, after
 *  screening has committed. One AI call per CV gives the per-skill check and the overall AI
 *  match. When it succeeds, the skill match % (fit_score) is replaced by the AI per-skill score
 *  (source AI). A failure marks only that candidate FAILED and with no Gemini key every pending
 *  candidate is OFF; in both cases the keyword check from screening stays (source KEYWORD).
 *
 *  A check lives in memory, so a restart (e.g. a deploy) during screening would leave candidates
 *  PENDING — and a PENDING candidate is neither passed nor failed. So on startup, and every few
 *  minutes, {@link #sweep()} re-runs every job that still has PENDING candidates. Single node: a
 *  job already being checked here is not started twice. Off in tests, which call sweep() directly. */
@Component
public class AiMatchJob {

    private static final Logger log = LoggerFactory.getLogger(AiMatchJob.class);

    /** One run re-reads the PENDING list this many times, so a re-score that came in while the
     *  job was being checked is done in the same run. The sweep catches anything later. */
    static final int MAX_PASSES = 5;

    public static final String PENDING = "PENDING", DONE = "DONE", FAILED = "FAILED", OFF = "OFF",
            /** Not AI-matched because the candidate failed step 1 (experience). */
            SKIPPED = "SKIPPED";

    /** Where a candidate's skill match % came from. */
    public static final String SOURCE_AI = "AI", SOURCE_KEYWORD = "KEYWORD";

    private final CandidateRepository candidates;
    private final SettingsService settings;
    private final CvMatcher matcher;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final boolean sweepEnabled;
    /** Jobs being checked right now on this node. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public AiMatchJob(CandidateRepository candidates, SettingsService settings, CvMatcher matcher,
                      PlatformTransactionManager txManager, ApplicationEventPublisher events,
                      @Value("${app.ai-match.sweep-enabled:true}") boolean sweepEnabled) {
        this.candidates = candidates;
        this.settings = settings;
        this.matcher = matcher;
        this.tx = new TransactionTemplate(txManager);
        // REQUIRES_NEW: when run inline after the screening commit, that finished transaction is
        // still bound to the thread and would swallow writes (same as AssessmentGenerationJob).
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.events = events;
        this.sweepEnabled = sweepEnabled;
    }

    @Async("scoringExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(AiMatchRequestedEvent event) {
        run(event.jobId());
    }

    /** Single node: anything still PENDING at startup was cut off by a restart. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        if (!sweepEnabled) return;
        int n = sweep();
        if (n > 0) log.warn("Resumed the AI check for {} job(s) left pending by a restart", n);
    }

    @Scheduled(fixedDelayString = "${app.ai-match.sweep-interval-ms:180000}",
               initialDelayString = "${app.ai-match.sweep-interval-ms:180000}")
    public void scheduled() {
        if (!sweepEnabled) return;
        try {
            int n = sweep();
            if (n > 0) log.info("AI check sweep restarted {} job(s) with pending candidates", n);
        } catch (Exception e) {
            log.error("AI check sweep failed", e);
        }
    }

    /** Starts a check for every job that still has PENDING candidates and isn't being checked
     *  on this node. Returns how many jobs were started. */
    public int sweep() {
        List<UUID> jobs = tx.execute(s -> candidates.findJobIdsByAiMatchStatus(PENDING));
        int n = 0;
        for (UUID jobId : jobs == null ? List.<UUID>of() : jobs) {
            if (running.contains(jobId)) continue;
            events.publishEvent(new AiMatchRequestedEvent(jobId));
            n++;
        }
        return n;
    }

    record Work(UUID candidateId, String jd, String cv, List<String> mustHave, List<String> niceToHave) {}

    public void run(UUID jobId) {
        if (!running.add(jobId)) return;   // already being checked; that run re-reads the list
        try {
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                List<Work> work = pending(jobId);
                if (work == null || work.isEmpty()) return;
                check(work);
            }
        } finally {
            running.remove(jobId);
        }
    }

    private List<Work> pending(UUID jobId) {
        return tx.execute(s -> candidates.findByJobIdOrderByFitScoreDesc(jobId).stream()
                .filter(c -> PENDING.equals(c.getAiMatchStatus()))
                .map(c -> {
                    Job job = c.getJob();
                    return new Work(c.getId(), job.getJdText(), c.getResume().getExtractedText(),
                            List.copyOf(SkillEvidence.mustHave(job)), List.copyOf(SkillEvidence.niceToHave(job)));
                })
                .toList());
    }

    private void check(List<Work> work) {
        String apiKey = settings.getGeminiApiKeyOrNull();
        for (Work w : work) {
            if (apiKey == null || apiKey.isBlank()) {
                save(w, OFF, null);
                continue;
            }
            try {
                List<String> all = new ArrayList<>(w.mustHave());
                all.addAll(w.niceToHave());
                save(w, DONE, matcher.match(apiKey, w.jd(), w.cv(), all));
            } catch (Exception e) {
                log.warn("AI CV check failed for candidate {}: {}", w.candidateId(), e.getMessage());
                save(w, FAILED, null);
            }
        }
    }

    private void save(Work w, String status, CvMatcher.Result r) {
        tx.executeWithoutResult(s -> candidates.findById(w.candidateId()).ifPresent(c -> {
            c.setAiMatchStatus(status);
            if (r == null) return;   // keyword check from screening stays
            c.setAiMatchScore(r.score());
            c.setAiMatchSummary(r.summary());
            c.setAiMatchStrengths(r.strengths());
            c.setAiMatchGaps(r.gaps());
            // An answer with no per-skill list is incomplete: keep the keyword backup rather than
            // scoring every skill as missing.
            boolean hasSkills = !w.mustHave().isEmpty() || !w.niceToHave().isEmpty();
            if (hasSkills && (r.skills() == null || r.skills().isEmpty())) {
                log.warn("AI CV check for candidate {} returned no skill list; keeping the keyword backup", w.candidateId());
                return;
            }
            applySkills(c, w.cv(), w.mustHave(), w.niceToHave(), r.skills());
        }));
    }

    /** Replaces the keyword skill match with the checked AI per-skill result. */
    static void applySkills(Candidate c, String cv, List<String> mustHave, List<String> niceToHave,
                            List<SkillEvidence.Claim> claims) {
        List<SkillEvidence.SkillResult> results = SkillEvidence.check(cv, mustHave, niceToHave, claims);
        int score = SkillEvidence.score(results);
        List<String> matched = new ArrayList<>();
        List<String> missingMust = new ArrayList<>();
        int matchedMust = 0;
        for (SkillEvidence.SkillResult r : results) {
            boolean found = !SkillEvidence.MISSING.equals(r.level());
            if (found) matched.add(r.skill());
            if (r.mustHave()) {
                if (found) matchedMust++; else missingMust.add(r.skill());
            }
        }
        c.setFitScore(score);
        c.setSkillMatchSource(SOURCE_AI);
        c.setSkillResults(new ArrayList<>(results.stream().map(SkillEvidence.SkillResult::toMap).toList()));
        c.setMatchedSkills(matched);
        c.setMissingSkills(missingMust);
        c.setMatchedRequiredCount(matchedMust);
        c.setTotalRequiredCount(mustHave.size());
        long strong = results.stream().filter(r -> SkillEvidence.STRONG.equals(r.level())).count();
        long partial = results.stream().filter(r -> SkillEvidence.PARTIAL.equals(r.level())).count();
        c.setScoreBreakdown(String.format("AI skill check: %d strong, %d partial, %d missing of %d skills"
                        + " · must-have %d/%d · %d yrs experience · skill match %d/100",
                strong, partial, results.size() - strong - partial, results.size(),
                matchedMust, mustHave.size(), c.getYearsExperience(), score));
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

package com.smartstaff.service.impl;

import com.smartstaff.entity.Candidate;
import com.smartstaff.entity.Job;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.service.AiMatchRequestedEvent;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.SkillEvidence;
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

/** Runs the AI check for a job's PENDING candidates in the background, one at a time, after
 *  screening has committed. One AI call per CV gives the per-skill check and the overall AI
 *  match. When it succeeds, the skill match % (fit_score) is replaced by the AI per-skill score
 *  (source AI). A failure marks only that candidate FAILED and with no Gemini key every pending
 *  candidate is OFF; in both cases the keyword check from screening stays (source KEYWORD). */
@Component
public class AiMatchJob {

    private static final Logger log = LoggerFactory.getLogger(AiMatchJob.class);

    public static final String PENDING = "PENDING", DONE = "DONE", FAILED = "FAILED", OFF = "OFF",
            /** Not AI-matched because the candidate failed step 1 (experience). */
            SKIPPED = "SKIPPED";

    /** Where a candidate's skill match % came from. */
    public static final String SOURCE_AI = "AI", SOURCE_KEYWORD = "KEYWORD";

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

    record Work(UUID candidateId, String jd, String cv, List<String> mustHave, List<String> niceToHave) {}

    public void run(UUID jobId) {
        List<Work> work = tx.execute(s -> candidates.findByJobIdOrderByFitScoreDesc(jobId).stream()
                .filter(c -> PENDING.equals(c.getAiMatchStatus()))
                .map(c -> {
                    Job job = c.getJob();
                    return new Work(c.getId(), job.getJdText(), c.getResume().getExtractedText(),
                            List.copyOf(SkillEvidence.mustHave(job)), List.copyOf(SkillEvidence.niceToHave(job)));
                })
                .toList());
        if (work == null || work.isEmpty()) return;
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

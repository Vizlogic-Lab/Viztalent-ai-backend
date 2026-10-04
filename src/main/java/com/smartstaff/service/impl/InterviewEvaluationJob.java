package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.repository.InterviewRepository;
import com.smartstaff.repository.InterviewTurnRepository;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.ScoringRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** F16: grades a completed L1 interview (browser, self-service or phone).
 *
 *  Each answer is graded against its expected points with the F9 rubric
 *  machinery (RubricGrader.gradeConsistently: Gemini at temperature 0, evidence
 *  quoted from the transcript, two runs and a third + median on disagreement),
 *  0 / 1 / 2 per point. An empty or very short answer scores 0 without a
 *  Gemini call. Practical prompts count as HANDS_ON. The result uses the F10
 *  Theory/Hands-on scoring and gates and goes to the F12 SME review. When
 *  Gemini can't be reached the answer is RUBRIC_PENDING and retried with
 *  backoff for the retry window, then RUBRIC_UNAVAILABLE (forces HR_REVIEW).
 *  No Gemini call runs inside a transaction. */
@Component
public class InterviewEvaluationJob {

    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluationJob.class);
    static final int MIN_ANSWER_WORDS = 3;
    private static final Duration STALE_EVALUATION = Duration.ofMinutes(15);
    private static final String EVALUATING = "EVALUATING", EVALUATED = "EVALUATED";

    public enum Mode { FIRST, FORCE, RETRY_PENDING }

    private final InterviewRepository interviews;
    private final InterviewTurnRepository turns;
    private final SettingsService settings;
    private final RubricGrader grader;
    private final ReviewRouter reviewRouter;
    private final ActivityRecorder activity;
    private final TransactionTemplate tx;
    private final double disagreementThreshold;
    private final Duration retryWindow;

    public InterviewEvaluationJob(InterviewRepository interviews, InterviewTurnRepository turns, SettingsService settings,
                                  RubricGrader grader, ReviewRouter reviewRouter, ActivityRecorder activity,
                                  PlatformTransactionManager transactionManager,
                                  @Value("${app.evaluation.disagreement-threshold:0.15}") double disagreementThreshold,
                                  @Value("${app.evaluation.retry-window-minutes:60}") long retryWindowMinutes) {
        this.interviews = interviews;
        this.turns = turns;
        this.settings = settings;
        this.grader = grader;
        this.reviewRouter = reviewRouter;
        this.activity = activity;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.disagreementThreshold = disagreementThreshold;
        this.retryWindow = Duration.ofMinutes(retryWindowMinutes);
    }

    /** After the interview is COMPLETED (async). */
    @Async("scoringExecutor")
    public void run(UUID interviewId) {
        evaluateNow(interviewId, Mode.FIRST);
    }

    /** HR re-grade: replaces the engine's scores (async). SME scores already given stay. */
    @Async("scoringExecutor")
    public void rerun(UUID interviewId) {
        evaluateNow(interviewId, Mode.FORCE);
    }

    /** Synchronous entry for the sweep. Returns true if it graded. */
    public boolean evaluateNow(UUID interviewId, Mode mode) {
        try {
            return evaluate(interviewId, mode);
        } catch (Exception e) {
            log.error("Grading of interview {} failed", interviewId, e);
            return false;
        }
    }

    // ── snapshot ────────────────────────────────────────────────────────

    private record Turn(UUID id, int seq, String question, String answer, Dimension dimension, List<String> expectedPoints,
                        List<String> storedFlags, BigDecimal storedScore, BigDecimal storedMax,
                        List<Map<String, Object>> storedEvidence, List<Map<String, Object>> storedRuns) {
        boolean pending() {
            return storedFlags.contains("RUBRIC_PENDING");
        }
    }

    private record Graded(Turn turn, boolean keep, BigDecimal score, BigDecimal max, List<Map<String, Object>> evidence,
                          List<Map<String, Object>> runs, List<String> flags) {
        static Graded keep(Turn t) {
            return new Graded(t, true, t.storedScore(), t.storedMax(), t.storedEvidence(), t.storedRuns(), t.storedFlags());
        }
    }

    private boolean evaluate(UUID interviewId, Mode mode) {
        List<Turn> snapshot = tx.execute(s -> begin(interviewId, mode));
        if (snapshot == null) return false;
        String apiKey = settings.getGeminiApiKeyOrNull();
        List<Graded> results = new ArrayList<>();
        for (Turn t : snapshot) results.add(mode == Mode.RETRY_PENDING && !t.pending() ? Graded.keep(t) : grade(t, apiKey));
        tx.executeWithoutResult(s -> finish(interviewId, results));
        return true;
    }

    private List<Turn> begin(UUID interviewId, Mode mode) {
        Interview iv = interviews.lockById(interviewId).orElse(null);
        if (iv == null || !"COMPLETED".equals(iv.getStatus())) return null;
        Instant now = Instant.now();
        String st = iv.getEvaluationStatus();
        boolean eligible = switch (mode) {
            case FIRST -> st == null || (EVALUATING.equals(st) && iv.getNextEvaluationAt() == null
                    && iv.getEvaluationStartedAt() != null && iv.getEvaluationStartedAt().isBefore(now.minus(STALE_EVALUATION)));
            case FORCE -> st == null || EVALUATED.equals(st) || "AWAITING_REVIEW".equals(st) || EVALUATING.equals(st);
            case RETRY_PENDING -> EVALUATING.equals(st) && iv.getNextEvaluationAt() != null && !iv.getNextEvaluationAt().isAfter(now);
        };
        if (!eligible) return null;
        iv.setEvaluationStatus(EVALUATING);
        iv.setNextEvaluationAt(null);
        if (mode != Mode.RETRY_PENDING) {
            iv.setEvaluationStartedAt(now);
            iv.setRubricPendingSince(null);
            iv.setEvaluationTries(0);
        }
        interviews.save(iv);
        return turns.findByInterviewIdOrderBySeqAsc(interviewId).stream()
                .map(t -> new Turn(t.getId(), t.getSeq(), t.getQuestion(), t.getAnswer(), t.getDimension(),
                        List.copyOf(t.getExpectedPoints()), List.copyOf(t.getFlags()), t.getScore(), t.getMaxScore(),
                        List.copyOf(t.getEvidence()), t.getRubricRuns() == null ? List.of() : List.copyOf(t.getRubricRuns())))
                .toList();
    }

    // ── grading one answer (outside any transaction) ────────────────────

    private Graded grade(Turn t, String apiKey) {
        List<String> points = t.expectedPoints().stream().filter(p -> p != null && !p.isBlank()).toList();
        if (points.isEmpty()) {
            return new Graded(t, false, zero(), zero(), List.of(note("rule", "No expected points to grade against — not scored.")),
                    List.of(), List.of());
        }
        BigDecimal max = BigDecimal.valueOf(2L * points.size()).setScale(2, RoundingMode.HALF_UP);
        String answer = t.answer() == null ? "" : t.answer().strip();
        if (words(answer) < MIN_ANSWER_WORDS) {
            String why = answer.isEmpty() ? "Not answered — scored 0." : "Answer too short to grade — scored 0.";
            return new Graded(t, false, zero(), max, List.of(note("rule", why)), List.of(), List.of());
        }
        List<RubricGrader.Criterion> criteria = RubricGrader.expectedPointCriteria(points);
        RubricGrader.Outcome o = grader.gradeConsistently(apiKey, RubricGrader.interviewPrompt(t.question(), criteria, answer),
                criteria, answer, max.doubleValue(), max.doubleValue(), disagreementThreshold);
        if (o.unavailable()) {
            return new Graded(t, false, zero(), max, List.of(note("pending", "AI grader unavailable — this answer is pending.")),
                    List.of(), List.of("RUBRIC_PENDING"));
        }
        BigDecimal score = BigDecimal.valueOf(o.fraction() * max.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> evidence = o.chosen().criteria().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "criterion");
            m.putAll(c.toMap());
            return m;
        }).toList();
        return new Graded(t, false, score, max, evidence, o.runs(), o.disagreement() ? List.of("GRADER_DISAGREEMENT") : List.of());
    }

    static int words(String s) {
        String t = s == null ? "" : s.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static Map<String, Object> note(String kind, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("text", text);
        return m;
    }

    // ── writing results (one short transaction) ─────────────────────────

    private void finish(UUID interviewId, List<Graded> results) {
        Interview iv = interviews.findById(interviewId).orElse(null);
        if (iv == null) return;
        Instant now = Instant.now();
        boolean pending = results.stream().anyMatch(g -> g.flags().contains("RUBRIC_PENDING"));
        if (pending && iv.getRubricPendingSince() == null) iv.setRubricPendingSince(now);
        boolean giveUp = pending && !now.isBefore(iv.getRubricPendingSince().plus(retryWindow));

        Map<UUID, InterviewTurn> byId = new LinkedHashMap<>();
        turns.findByInterviewIdOrderBySeqAsc(interviewId).forEach(t -> byId.put(t.getId(), t));
        Set<String> flags = new TreeSet<>();
        for (Graded g : results) {
            List<String> f = new ArrayList<>(g.flags());
            if (giveUp) f.replaceAll(x -> x.equals("RUBRIC_PENDING") ? "RUBRIC_UNAVAILABLE" : x);
            flags.addAll(f);
            InterviewTurn t = byId.get(g.turn().id());
            if (t == null || (g.keep() && !giveUp)) continue;
            t.setScore(g.score());
            t.setMaxScore(g.max());
            t.setEvidence(new ArrayList<>(g.evidence()));
            t.setRubricRuns(g.runs().isEmpty() ? null : new ArrayList<>(g.runs()));
            t.setFlags(f);
            turns.save(t);
        }

        if (pending && !giveUp) {
            iv.setFlags(new ArrayList<>(flags));
            iv.setEvaluationStatus(EVALUATING);
            iv.setEvaluationTries(iv.getEvaluationTries() + 1);
            long delay = Math.min(60L << Math.min(iv.getEvaluationTries() - 1, 5), 30L * 60);
            Instant next = now.plusSeconds(delay);
            Instant last = iv.getRubricPendingSince().plus(retryWindow);
            iv.setNextEvaluationAt(next.isAfter(last) ? last : next);
            interviews.save(iv);
            return;
        }
        iv.setRubricPendingSince(null);
        iv.setNextEvaluationAt(null);
        iv.setEvaluatedAt(now);
        iv.setEvaluationStatus(EVALUATED);
        result(iv, byId.values(), Set.of());
        iv.setEngineRecommendation(iv.getRecommendation());
        if (iv.getRecommendation() != null) {
            activity.record(iv.getJob().getId(), ActivityRecorder.EVALUATION, "Interview with " + iv.getCandidateName()
                    + " evaluated: " + iv.getRecommendation(), iv.getId());
            reviewRouter.routeInterview(iv);
        }
        interviews.save(iv);
    }

    /** Theory / Hands-on result from the turns' final scores (the SME's where set). Flags of
     *  turns in `adjusted` no longer force HR_REVIEW: an SME has looked at them. Also used by
     *  the SME sign-off. Sets recommendation null (flag NOT_GRADABLE) when nothing was gradable. */
    static void result(Interview iv, Collection<InterviewTurn> all, Set<UUID> adjusted) {
        List<ScoringRules.Scored> scored = new ArrayList<>();
        Set<String> gateFlags = new HashSet<>();
        Set<String> flags = new TreeSet<>();
        for (InterviewTurn t : all) {
            flags.addAll(t.getFlags());
            if (t.getMaxScore() == null || t.getMaxScore().signum() <= 0) continue;
            BigDecimal fin = t.finalScore();
            Competency competency = t.getDimension() == Dimension.HANDS_ON ? Competency.APPLICATION : Competency.CONCEPTS;
            scored.add(new ScoringRules.Scored(QuestionType.SCENARIO, t.getDimension(), competency,
                    fin == null ? 0 : fin.doubleValue(), t.getMaxScore().intValue()));
            if (!adjusted.contains(t.getId())) gateFlags.addAll(t.getFlags());
        }
        if (scored.isEmpty()) {
            iv.setTheoryScore(null);
            iv.setHandsOnScore(null);
            iv.setOverallScore(null);
            iv.setTheoryBand(null);
            iv.setHandsOnBand(null);
            iv.setRecommendation(null);
            flags.add("NOT_GRADABLE");
            iv.setFlags(new ArrayList<>(flags));
            return;
        }
        ScoringRules.Result r = ScoringRules.score(scored, iv.getJob().getScoringSettings(), gateFlags, 0);
        iv.setTheoryScore(r.theory());
        iv.setHandsOnScore(r.handsOn());
        iv.setOverallScore(r.overall());
        iv.setTheoryBand(r.theoryBand());
        iv.setHandsOnBand(r.handsOnBand());
        iv.setRecommendation(r.recommendation());
        flags.addAll(r.flags());
        iv.setFlags(new ArrayList<>(flags));
    }
}

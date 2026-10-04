package com.smartstaff.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.*;
import com.smartstaff.mapper.AssessmentMapper;
import com.smartstaff.repository.*;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.CodeRunnerService.RunResult;
import com.smartstaff.service.CodeRunnerService.RunStatus;
import com.smartstaff.service.CodeRunnerService.TestInput;
import com.smartstaff.service.CodeRunnerService.TestResult;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.EvaluationRules;
import com.smartstaff.util.EvaluationRules.TestOutcome;
import com.smartstaff.util.ResultNarrator;
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

/** Grades one submitted attempt (F9). SUBMITTED → EVALUATING → EVALUATED.
 *
 *  Objective layer (EvaluationRules): MCQ/MSQ/LOGIC/CODE_OUTPUT, and for the
 *  coding types the final code is run against ALL tests (a SUBMIT code_run)
 *  with correctness = weighted pass share of the HIDDEN tests; a compile error
 *  or no hidden pass scores the question 0. Rubric layer (RubricGrader,
 *  temperature 0): CODE_WRITE's 30% and SCENARIO's 100%, run twice — a third
 *  run and the median when the two differ by more than 15% of the question's
 *  points (flag GRADER_DISAGREEMENT). Stringency: rubric ×0.5 below 50% hidden
 *  share, ×0 below 25%; hard-coded answers score 0 (flag HARDCODED); comments
 *  or the untouched starter earn nothing.
 *
 *  Re-running replaces the attempt's scores (idempotent). When Gemini or the
 *  code runner can't be reached, the objective scores are kept, the question
 *  is flagged *_PENDING and retried with backoff; after the window it becomes
 *  *_UNAVAILABLE for human review. No sandbox or Gemini call runs inside a
 *  transaction. The legacy scorecard rows are kept in step so the HR results
 *  screen and PDF keep working until they move onto these fields. */
@Component
public class EvaluationJob {

    private static final Logger log = LoggerFactory.getLogger(EvaluationJob.class);
    static final int PASS_THRESHOLD = 50;
    private static final Duration STALE_EVALUATION = Duration.ofMinutes(15);

    public enum Mode { FULL, RETRY_PENDING }

    private final ActivityRecorder activity;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentAnswerRepository answers;
    private final AssessmentQuestionRepository questions;
    private final CodeRunRepository codeRuns;
    private final AssessmentScorecardRepository scorecards;
    private final SettingsService settings;
    private final CodeRunnerService runner;
    private final RubricGrader grader;
    private final AssessmentMapper mapper;
    private final ObjectMapper json;
    private final JobRoleProfileRepository roleProfiles;
    private final AttemptEventRepository events;
    private final ResultSummarizer summarizer;
    private final ReviewRouter reviewRouter;
    private final TransactionTemplate tx;

    private final double codeWriteTestShare;
    private final double rubricHalfBelow;
    private final double rubricZeroBelow;
    private final double disagreementThreshold;
    private final int hardcodeMinLiteral;
    private final Duration retryWindow;

    public EvaluationJob(AssessmentAttemptRepository attempts, AssessmentAnswerRepository answers,
                         AssessmentQuestionRepository questions, CodeRunRepository codeRuns,
                         AssessmentScorecardRepository scorecards, SettingsService settings, CodeRunnerService runner,
                         RubricGrader grader, AssessmentMapper mapper, ObjectMapper json,
                         JobRoleProfileRepository roleProfiles, AttemptEventRepository events, ResultSummarizer summarizer,
                         ReviewRouter reviewRouter, PlatformTransactionManager transactionManager,
                         @Value("${app.evaluation.code-write-test-share:0.7}") double codeWriteTestShare,
                         @Value("${app.evaluation.rubric-half-below:0.5}") double rubricHalfBelow,
                         @Value("${app.evaluation.rubric-zero-below:0.25}") double rubricZeroBelow,
                         @Value("${app.evaluation.disagreement-threshold:0.15}") double disagreementThreshold,
                         @Value("${app.evaluation.hardcode-min-literal-length:3}") int hardcodeMinLiteral,
                         @Value("${app.evaluation.retry-window-minutes:60}") long retryWindowMinutes,
            ActivityRecorder activity) {
        this.activity = activity;
        this.attempts = attempts;
        this.answers = answers;
        this.questions = questions;
        this.codeRuns = codeRuns;
        this.scorecards = scorecards;
        this.settings = settings;
        this.runner = runner;
        this.grader = grader;
        this.mapper = mapper;
        this.json = json;
        this.roleProfiles = roleProfiles;
        this.events = events;
        this.summarizer = summarizer;
        this.reviewRouter = reviewRouter;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.codeWriteTestShare = codeWriteTestShare;
        this.rubricHalfBelow = rubricHalfBelow;
        this.rubricZeroBelow = rubricZeroBelow;
        this.disagreementThreshold = disagreementThreshold;
        this.hardcodeMinLiteral = hardcodeMinLiteral;
        this.retryWindow = Duration.ofMinutes(retryWindowMinutes);
    }

    /** After submit (async). */
    @Async("scoringExecutor")
    public void run(UUID attemptId) {
        safely(attemptId, Mode.FULL, false);
    }

    /** HR "re-score": a full re-evaluation that replaces the scores (async). */
    @Async("scoringExecutor")
    public void rerun(UUID attemptId) {
        safely(attemptId, Mode.FULL, true);
    }

    /** Synchronous entry for the retry scheduler. Returns true if it graded. */
    public boolean evaluateNow(UUID attemptId, Mode mode) {
        return safely(attemptId, mode, false);
    }

    private boolean safely(UUID attemptId, Mode mode, boolean force) {
        try {
            return evaluate(attemptId, mode, force);
        } catch (Exception e) {
            log.error("Evaluation of attempt {} failed", attemptId, e);
            return false;
        }
    }

    // ── snapshot (read in one short transaction) ────────────────────────

    record TestData(int seq, String input, String expected, boolean visible, TestCaseCategory category,
                    BigDecimal weight, BigDecimal tolerance) {}

    record Item(UUID questionId, String level, int seq, QuestionType type, Dimension dimension, Competency competency,
                int points, String skill,
                List<Integer> correct, String modelAnswer, List<String> keyPoints, String prompt,
                String expectedComplexity, List<RubricCriterion> rubric, Map<String, String> starter,
                Map<String, String> reference, List<String> languages, List<TestData> tests,
                Map<String, Object> candidateView,
                UUID answerId, List<Integer> selected, String language, String code, String text,
                List<String> storedFlags, BigDecimal storedAuto, BigDecimal storedFinal,
                List<Map<String, Object>> storedEvidence, List<Map<String, Object>> storedRubric) {

        boolean pending() {
            return storedFlags.contains("RUBRIC_PENDING") || storedFlags.contains("RUNNER_PENDING");
        }
    }

    private record Snapshot(UUID attemptId, String apiKey, List<Item> items) {}

    private boolean evaluate(UUID attemptId, Mode mode, boolean force) {
        Snapshot snap = tx.execute(s -> begin(attemptId, mode, force));
        if (snap == null) return false;

        List<Graded> results = new ArrayList<>();
        for (Item item : snap.items()) {
            results.add(mode == Mode.RETRY_PENDING && !item.pending() ? Graded.keep(item) : grade(item, snap.apiKey()));
        }
        ResultNarrator.Facts facts = tx.execute(s -> finish(attemptId, results));
        if (facts != null) {
            // The AI summary runs outside any transaction; the template is already stored.
            Map<String, Object> summary = summarizer.summarize(snap.apiKey(), facts);
            if ("AI".equals(summary.get("source"))) {
                tx.executeWithoutResult(s -> attempts.findById(attemptId).ifPresent(a -> {
                    if (a.getStatus() == AttemptStatus.EVALUATED || a.getStatus() == AttemptStatus.AWAITING_REVIEW
                            || (a.getStatus() == AttemptStatus.VALIDATED && "sampling".equals(a.getValidationReason()))) {
                        a.setSummary(summary);
                        attempts.save(a);
                    }
                }));
            }
        }
        return true;
    }

    private Snapshot begin(UUID attemptId, Mode mode, boolean force) {
        AssessmentAttempt a = attempts.lockById(attemptId).orElse(null);
        if (a == null) return null;
        Instant now = Instant.now();
        boolean eligible = switch (mode) {
            case FULL -> force
                    ? a.getStatus() == AttemptStatus.SUBMITTED || a.getStatus() == AttemptStatus.EVALUATING
                        || a.getStatus() == AttemptStatus.EVALUATED || a.getStatus() == AttemptStatus.AWAITING_REVIEW
                    : a.getStatus() == AttemptStatus.SUBMITTED
                        || (a.getStatus() == AttemptStatus.EVALUATING && a.getNextEvaluationAt() == null
                            && a.getEvaluationStartedAt() != null
                            && a.getEvaluationStartedAt().isBefore(now.minus(STALE_EVALUATION)));
            case RETRY_PENDING -> a.getStatus() == AttemptStatus.EVALUATING && a.getNextEvaluationAt() != null
                    && !a.getNextEvaluationAt().isAfter(now);
        };
        if (!eligible) return null;

        a.setStatus(AttemptStatus.EVALUATING);
        a.setNextEvaluationAt(null);   // claimed — a concurrent sweep won't pick it up again
        if (mode == Mode.FULL) {
            a.setEvaluationStartedAt(now);
            if (force) {
                a.setRubricPendingSince(null);
                a.setEvaluationTries(0);
            }
        }
        attempts.save(a);

        Map<UUID, AssessmentAnswer> byQuestion = new HashMap<>();
        for (AssessmentAnswer ans : answers.findByAttemptId(attemptId)) byQuestion.put(ans.getQuestion().getId(), ans);

        List<Item> items = new ArrayList<>();
        for (AssessmentQuestion q : questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getAssessment().getId())) {
            if (!a.getLevel().equals(q.getLevel())) continue;
            items.add(item(q, byQuestion.get(q.getId())));
        }
        return new Snapshot(attemptId, settings.getGeminiApiKeyOrNull(), items);
    }

    /** Must run inside a transaction (reads the question's test cases). ans may be null or unsaved. */
    Item item(AssessmentQuestion q, AssessmentAnswer ans) {
        List<TestData> tests = q.getTestCases().stream()
                .map(t -> new TestData(t.getSeq(), t.getInput(), t.getExpectedOutput(), t.isVisible(), t.getCategory(),
                        t.getWeight(), t.getFloatTolerance()))
                .toList();
        return new Item(q.getId(), q.getLevel(), q.getSeq(), q.getType(), q.getDimension(), q.getCompetency(),
                q.getPoints(), q.getSkill(),
                List.copyOf(q.getCorrectIndices()), q.getModelAnswer(), List.copyOf(q.getKeyPoints()), q.getPrompt(),
                q.getExpectedComplexity(), List.copyOf(q.getRubric()), Map.copyOf(q.getStarterCode()),
                Map.copyOf(q.getReferenceSolution()), List.copyOf(q.getLanguages()), tests,
                json.convertValue(mapper.toCandidateView(q), new TypeReference<Map<String, Object>>() {}),
                ans == null ? null : ans.getId(),
                ans == null ? List.of() : List.copyOf(ans.getSelectedIndices()),
                ans == null ? null : ans.getLanguage(), ans == null ? null : ans.getCode(),
                ans == null ? null : ans.getTextAnswer(),
                ans == null ? List.of() : List.copyOf(ans.getFlags()),
                ans == null ? null : ans.getAutoScore(), ans == null ? null : ans.getFinalScore(),
                ans == null ? List.of() : List.copyOf(ans.getEvidence()),
                ans == null || ans.getRubricScore() == null ? List.of() : List.copyOf(ans.getRubricScore()));
    }

    // ── F15 calibration: the same grading, nothing stored ───────────────

    public record DryRun(BigDecimal score, List<String> flags, List<Map<String, Object>> evidence) {}

    /** Grades an item built by {@link #item} exactly as evaluation would. Call outside any transaction. */
    DryRun dryRun(Item item) {
        Graded g = grade(item, settings.getGeminiApiKeyOrNull());
        return new DryRun(g.fin(), g.flags(), g.evidence());
    }

    // ── grading one question (outside any transaction) ──────────────────

    private record SubmitRun(String language, List<Map<String, Object>> results, String compileError) {}

    private record Graded(Item item, boolean keep, BigDecimal auto, BigDecimal fin, List<Map<String, Object>> rubricRuns,
                          List<Map<String, Object>> evidence, List<String> flags, SubmitRun submit) {
        static Graded keep(Item item) {
            return new Graded(item, true, item.storedAuto(), item.storedFinal(), item.storedRubric(),
                    item.storedEvidence(), item.storedFlags(), null);
        }
    }

    private Graded grade(Item it, String apiKey) {
        return switch (it.type()) {
            case MCQ -> objective(it, it.selected().isEmpty() ? null : EvaluationRules.mcq(it.correct(), it.selected()));
            case MSQ -> objective(it, it.selected().isEmpty() ? null : EvaluationRules.msq(it.correct(), it.selected()));
            case LOGIC -> objective(it, blank(it.text()) ? null : EvaluationRules.logicMatches(it.modelAnswer(), it.text()) ? 1.0 : 0.0);
            case CODE_OUTPUT -> objective(it, blank(it.text()) ? null : EvaluationRules.codeOutputMatches(it.modelAnswer(), it.text()) ? 1.0 : 0.0);
            case CODE_WRITE, CODE_DEBUG -> gradeCode(it, apiKey);
            case SCENARIO -> gradeScenario(it, apiKey);
        };
    }

    /** fraction null = not answered. */
    private Graded objective(Item it, Double fraction) {
        if (fraction == null) return zero(it, "Not answered.", List.of(), null, null);
        BigDecimal score = EvaluationRules.points(fraction, it.points());
        String note = fraction >= 1.0 ? "Correct." : fraction <= 0 ? "Incorrect." : "Partly correct.";
        return new Graded(it, false, score, score, List.of(), List.of(note("objective", note)), List.of(), null);
    }

    private Graded gradeCode(Item it, String apiKey) {
        String lang = it.language() != null && it.languages().contains(it.language()) ? it.language()
                : it.languages().isEmpty() ? null : it.languages().get(0);
        String starter = lang == null ? null : it.starter().get(lang);
        if (lang == null || !EvaluationRules.hasRealCode(lang, it.code(), starter)) {
            return zero(it, blank(it.code()) ? "Not answered." : "No code beyond the starter or comments — no credit.",
                    List.of(), null, null);
        }
        List<String> visibleExpected = it.tests().stream().filter(TestData::visible).map(TestData::expected).toList();
        boolean testsHaveInput = it.tests().stream().anyMatch(t -> !blank(t.input()));
        if (EvaluationRules.isHardcoded(lang, it.code(), visibleExpected, testsHaveInput, hardcodeMinLiteral)) {
            return zero(it, "Hard-coded: the code ignores its input or embeds the expected outputs — scored 0.",
                    List.of("HARDCODED"), null, null);
        }
        if (it.tests().isEmpty() || !runner.availableLanguages().containsKey(lang)) {
            return pending(it, "RUNNER_PENDING", "Code runner unavailable — the tests couldn't be run yet.", BigDecimal.ZERO, null, List.of());
        }

        List<TestInput> inputs = it.tests().stream()
                .map(t -> new TestInput(t.seq(), t.input(), t.expected(), t.tolerance())).toList();
        RunResult r = runner.runTests(lang, it.code(), inputs, CodeRunnerService.Limits.DEFAULT);
        if (r.status() != RunStatus.OK) {
            return pending(it, "RUNNER_PENDING", "Code runner " + r.status() + " — the tests couldn't be run yet.",
                    BigDecimal.ZERO, null, List.of());
        }

        Map<Integer, TestData> bySeq = new HashMap<>();
        for (TestData t : it.tests()) bySeq.put(t.seq(), t);
        List<TestOutcome> outcomes = new ArrayList<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (TestResult tr : r.tests()) {
            TestData t = bySeq.get(tr.seq());
            if (t == null) continue;
            outcomes.add(new TestOutcome(t.visible(), t.category().name(), t.weight(), tr.passed(), tr.status().name()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", t.seq());
            m.put("visible", t.visible());
            m.put("category", t.category().name());
            m.put("status", tr.status().name());
            m.put("passed", tr.passed());
            m.put("time_ms", tr.timeMs());
            results.add(m);
        }
        SubmitRun submit = new SubmitRun(lang, results, r.compileError());
        int hiddenPassed = EvaluationRules.hiddenPassed(outcomes);
        int hiddenTotal = EvaluationRules.hiddenTotal(outcomes);
        double share = EvaluationRules.hiddenShare(outcomes);
        Map<String, Object> testEvidence = testEvidence(outcomes, hiddenPassed, hiddenTotal, share, r.compileError());

        if (r.compileError() != null || hiddenPassed == 0) {
            String why = r.compileError() != null ? "Compile error — scored 0." : "No hidden test passed — scored 0.";
            return new Graded(it, false, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), List.of(),
                    List.of(testEvidence, note("rule", why)), List.of(), submit);
        }

        if (it.type() == QuestionType.CODE_DEBUG) {
            BigDecimal score = EvaluationRules.points(share, it.points());
            return new Graded(it, false, score, score, List.of(), List.of(testEvidence), List.of(), submit);
        }

        // CODE_WRITE: tests (70%) + rubric quality (30%).
        BigDecimal auto = EvaluationRules.points(codeWriteTestShare * share, it.points());
        double multiplier = EvaluationRules.rubricMultiplier(share, rubricHalfBelow, rubricZeroBelow);
        if (multiplier == 0) {
            return new Graded(it, false, auto, auto, List.of(),
                    List.of(testEvidence, note("rule", "Rubric credit removed: under 25% of hidden tests passed.")),
                    List.of(), submit);
        }
        double rubricPoints = (1 - codeWriteTestShare) * it.points();
        String testSummary = "passed " + hiddenPassed + " of " + hiddenTotal + " hidden tests";
        String prompt = RubricGrader.codePrompt(it.prompt(), lang, it.reference().get(lang), it.expectedComplexity(),
                testSummary, RubricGrader.codeCriteria(it.rubric()), it.code());
        RubricGrader.Outcome ro = rubric(apiKey, prompt, RubricGrader.codeCriteria(it.rubric()), it.code(), rubricPoints, it.points());
        if (ro.unavailable()) {
            return pending(it, "RUBRIC_PENDING", "AI grader unavailable — the 30% quality rubric is pending.", auto, submit,
                    List.of(testEvidence));
        }
        BigDecimal fin = auto.add(BigDecimal.valueOf(ro.fraction() * multiplier * rubricPoints)).setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> evidence = new ArrayList<>(List.of(testEvidence));
        if (multiplier < 1) evidence.add(note("rule", "Rubric credit halved: under 50% of hidden tests passed."));
        evidence.addAll(ro.chosen().criteria().stream().map(c -> withKind("criterion", c.toMap())).toList());
        return new Graded(it, false, auto, fin, ro.runs(), evidence,
                ro.disagreement() ? List.of("GRADER_DISAGREEMENT") : List.of(), submit);
    }

    private Graded gradeScenario(Item it, String apiKey) {
        if (blank(it.text())) return zero(it, "Not answered.", List.of(), null, null);
        if (it.keyPoints().isEmpty()) return zero(it, "No key points to grade against.", List.of(), null, null);
        List<RubricGrader.Criterion> criteria = RubricGrader.keyPointCriteria(it.keyPoints());
        String prompt = RubricGrader.scenarioPrompt(it.prompt(), it.modelAnswer(), criteria, it.text());
        RubricGrader.Outcome ro = rubric(apiKey, prompt, criteria, it.text(), it.points(), it.points());
        if (ro.unavailable()) {
            return pending(it, "RUBRIC_PENDING", "AI grader unavailable — this written answer is pending.", BigDecimal.ZERO,
                    null, List.of());
        }
        BigDecimal fin = EvaluationRules.points(ro.fraction(), it.points());
        List<Map<String, Object>> evidence = new ArrayList<>(
                ro.chosen().criteria().stream().map(c -> withKind("criterion", c.toMap())).toList());
        return new Graded(it, false, BigDecimal.ZERO.setScale(2), fin, ro.runs(), evidence,
                ro.disagreement() ? List.of("GRADER_DISAGREEMENT") : List.of(), null);
    }

    // ── rubric runs: twice, third + median on disagreement ──────────────

    private RubricGrader.Outcome rubric(String apiKey, String prompt, List<RubricGrader.Criterion> criteria, String answer,
                                        double rubricPoints, int questionPoints) {
        return grader.gradeConsistently(apiKey, prompt, criteria, answer, rubricPoints, questionPoints, disagreementThreshold);
    }

    private Graded zero(Item it, String why, List<String> flags, SubmitRun submit, Map<String, Object> extra) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (extra != null) evidence.add(extra);
        evidence.add(note("rule", why));
        return new Graded(it, false, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), List.of(), evidence, flags, submit);
    }

    private Graded pending(Item it, String flag, String why, BigDecimal known, SubmitRun submit, List<Map<String, Object>> evidence) {
        List<Map<String, Object>> ev = new ArrayList<>(evidence);
        ev.add(note("pending", why));
        BigDecimal k = known.setScale(2, RoundingMode.HALF_UP);
        return new Graded(it, false, k, k, List.of(), ev, List.of(flag), submit);
    }

    private static Map<String, Object> testEvidence(List<TestOutcome> outcomes, int passed, int total, double share,
                                                    String compileError) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "tests");
        m.put("hidden_passed", passed);
        m.put("hidden_total", total);
        m.put("hidden_share", Math.round(share * 1000) / 1000.0);
        m.put("failed", outcomes.stream().filter(o -> !o.visible() && !o.passed())
                .map(o -> Map.of("category", o.category(), "status", o.status())).toList());
        if (compileError != null) m.put("compile_error", compileError.length() > 500 ? compileError.substring(0, 500) : compileError);
        return m;
    }

    private static Map<String, Object> note(String kind, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("text", text);
        return m;
    }

    private static Map<String, Object> withKind(String kind, Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.putAll(body);
        return m;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ── writing results (one short transaction) ─────────────────────────

    /** Returns the summary facts when the attempt became EVALUATED, else null. */
    private ResultNarrator.Facts finish(UUID attemptId, List<Graded> results) {
        AssessmentAttempt a = attempts.findById(attemptId).orElse(null);
        if (a == null) return null;
        Instant now = Instant.now();

        boolean pending = results.stream().anyMatch(g -> g.flags().contains("RUBRIC_PENDING") || g.flags().contains("RUNNER_PENDING"));
        if (pending && a.getRubricPendingSince() == null) a.setRubricPendingSince(now);
        boolean giveUp = pending && !now.isBefore(a.getRubricPendingSince().plus(retryWindow));

        List<Graded> written = new ArrayList<>();
        for (Graded g : results) {
            List<String> flags = new ArrayList<>(g.flags());
            if (giveUp) {
                flags.replaceAll(f -> f.equals("RUBRIC_PENDING") ? "RUBRIC_UNAVAILABLE"
                        : f.equals("RUNNER_PENDING") ? "RUNNER_UNAVAILABLE" : f);
            }
            Graded out = new Graded(g.item(), g.keep(), g.auto(), g.fin(), g.rubricRuns(), g.evidence(), flags, g.submit());
            written.add(out);
            if (g.keep() && !giveUp) continue;
            save(a, out);
        }

        Set<String> attemptFlags = new TreeSet<>();
        written.forEach(g -> attemptFlags.addAll(g.flags()));
        a.setFlags(new ArrayList<>(attemptFlags));

        if (pending && !giveUp) {
            a.setStatus(AttemptStatus.EVALUATING);
            a.setEvaluationTries(a.getEvaluationTries() + 1);
            long delay = Math.min(60L << Math.min(a.getEvaluationTries() - 1, 5), 30L * 60);
            Instant next = now.plusSeconds(delay);
            Instant last = a.getRubricPendingSince().plus(retryWindow);
            a.setNextEvaluationAt(next.isAfter(last) ? last : next);
        } else {
            a.setStatus(AttemptStatus.EVALUATED);
            a.setEvaluatedAt(now);
            a.setNextEvaluationAt(null);
            if (!pending) a.setRubricPendingSince(null);
        }
        ResultNarrator.Facts facts = a.getStatus() == AttemptStatus.EVALUATED ? result(a, written) : null;
        if (facts != null) {
            activity.record(a.getJob().getId(), ActivityRecorder.EVALUATION, ActivityRecorder.who(a.getCandidateName(), a.getCandidateEmail())
                    + " evaluated (" + a.getLevel() + "): " + a.getRecommendation(), a.getId());
            reviewRouter.routeResult(a);   // F12: to an SME, or validated by sampling
        }
        attempts.save(a);
        project(a, written);
        return facts;
    }

    // ── F10: theory vs hands-on result (provisional until SME sign-off) ─

    private ResultNarrator.Facts result(AssessmentAttempt a, List<Graded> results) {
        boolean technical = roleProfiles.findById(a.getJob().getId())
                .map(p -> !Boolean.FALSE.equals(p.getIsTechnical())).orElse(true);
        List<ScoringRules.Scored> scored = new ArrayList<>();
        List<ResultNarrator.Question> narrated = new ArrayList<>();
        List<String> weak = new ArrayList<>();
        Set<String> questionFlags = new HashSet<>();
        List<Graded> ordered = results.stream().sorted(Comparator.comparingInt(g -> g.item().seq())).toList();
        for (Graded g : ordered) {
            Item it = g.item();
            double earned = g.fin() == null ? 0 : g.fin().doubleValue();
            scored.add(new ScoringRules.Scored(it.type(), ScoringRules.dimensionFor(it.type(), it.dimension(), technical),
                    it.competency() != null ? it.competency() : it.type().competency(), earned, it.points()));
            narrated.add(new ResultNarrator.Question(it.seq(), it.type(), earned, it.points(), g.evidence(), g.flags()));
            questionFlags.addAll(g.flags());
        }
        List<String> lines = ResultNarrator.evidenceLines(narrated);
        for (int i = 0; i < narrated.size(); i++) {
            if (narrated.get(i).earned() < narrated.get(i).points() / 2.0) weak.add(lines.get(i));
        }
        long tabs = events.countByAttemptIdAndType(a.getId(), AttemptEventType.TAB_SWITCH);
        ScoringRules.Result r = ScoringRules.score(scored, a.getJob().getScoringSettings(), questionFlags, tabs);

        a.setTheoryScore(r.theory());
        a.setHandsOnScore(r.handsOn());
        a.setOverallScore(r.overall());
        a.setTheoryBand(r.theoryBand());
        a.setHandsOnBand(r.handsOnBand());
        a.setCompetencyScores(new LinkedHashMap<>(r.competencies()));
        a.setRecommendation(r.recommendation());
        a.setEngineRecommendation(r.recommendation());
        a.setEvidenceLines(new ArrayList<>(lines));
        Set<String> flags = new TreeSet<>(a.getFlags());
        flags.addAll(r.flags());
        a.setFlags(new ArrayList<>(flags));

        ResultNarrator.Facts facts = new ResultNarrator.Facts(r.theory(), r.handsOn(), r.overall(), r.theoryBand(),
                r.handsOnBand(), r.recommendation(), List.copyOf(flags), r.competencies(), lines, weak);
        a.setSummary(ResultNarrator.template(facts));
        return facts;
    }

    private void save(AssessmentAttempt attempt, Graded g) {
        Item it = g.item();
        AssessmentAnswer ans = it.answerId() == null ? null : answers.findById(it.answerId()).orElse(null);
        if (ans == null) {
            // Unanswered question: a graded row anyway, so reviewers see every question.
            ans = new AssessmentAnswer();
            ans.setAttempt(attempt);
            ans.setQuestion(questions.getReferenceById(it.questionId()));
            ans.setQuestionSnapshot(new LinkedHashMap<>(it.candidateView()));
        }
        ans.setAutoScore(g.auto());
        ans.setRubricScore(g.rubricRuns().isEmpty() ? null : new ArrayList<>(g.rubricRuns()));
        ans.setFinalScore(g.fin());
        ans.setEngineScore(g.fin());
        ans.setEvidence(new ArrayList<>(g.evidence()));
        ans.setFlags(new ArrayList<>(g.flags()));
        ans = answers.save(ans);

        if (g.submit() != null) {
            codeRuns.deleteByAnswerIdAndMode(ans.getId(), CodeRunMode.SUBMIT);
            CodeRun run = new CodeRun();
            run.setAnswer(ans);
            run.setMode(CodeRunMode.SUBMIT);
            run.setLanguage(g.submit().language());
            run.setResults(new ArrayList<>(g.submit().results()));
            run.setCompileError(g.submit().compileError());
            codeRuns.save(run);
        } else if (ans.getId() != null) {
            codeRuns.deleteByAnswerIdAndMode(ans.getId(), CodeRunMode.SUBMIT);
        }
    }

    // ── legacy scorecard rows (HR results screen + PDF) ─────────────────

    private void project(AssessmentAttempt a, List<Graded> results) {
        AssessmentScorecard card = scorecards.findByAttemptId(a.getId()).orElseGet(AssessmentScorecard::new);
        card.setAttempt(a);
        card.setJob(a.getJob());
        card.setAssessment(a.getAssessment());
        card.getQuestionScores().clear();
        scorecards.saveAndFlush(card);   // old rows go before the new ones (unique per question)

        BigDecimal total = BigDecimal.ZERO, max = BigDecimal.ZERO, review = BigDecimal.ZERO;
        boolean needsReview = false;
        for (Graded g : results) {
            Item it = g.item();
            boolean waiting = g.flags().stream().anyMatch(f -> f.endsWith("_PENDING") || f.endsWith("_UNAVAILABLE"));
            AssessmentQuestionScore qs = new AssessmentQuestionScore();
            qs.setScorecard(card);
            qs.setQuestion(questions.getReferenceById(it.questionId()));
            qs.setLevel(it.level());
            qs.setSeq(it.seq());
            qs.setType(it.type().name());
            qs.setMaxPoints(it.points());
            BigDecimal score = g.fin() == null ? BigDecimal.ZERO : g.fin();
            qs.setScore(score);
            qs.setAutoGraded(!waiting);
            qs.setNeedsReview(waiting);
            qs.setAnswered(answered(it));
            Map<String, Object> tests = g.evidence().stream().filter(e -> "tests".equals(e.get("kind"))).findFirst().orElse(null);
            if (tests != null) {
                qs.setTestsPassed(((Number) tests.get("hidden_passed")).intValue());
                qs.setTestsTotal(((Number) tests.get("hidden_total")).intValue());
            }
            qs.setBreakdown(g.evidence().stream().filter(e -> "criterion".equals(e.get("kind")))
                    .map(e -> new QuestionScoreBreakdown(String.valueOf(e.get("criterion")),
                            ((Number) e.get("score")).doubleValue(), ((Number) e.get("max")).doubleValue(),
                            String.valueOf(e.getOrDefault("comment", ""))))
                    .toList());
            qs.setDetail(detail(g, tests));
            card.getQuestionScores().add(qs);

            total = total.add(score);
            max = max.add(BigDecimal.valueOf(it.points()));
            if (waiting) {
                needsReview = true;
                double owed = it.type() == QuestionType.CODE_WRITE && g.flags().stream().anyMatch(f -> f.startsWith("RUBRIC"))
                        ? (1 - codeWriteTestShare) * it.points() : it.points() - score.doubleValue();
                review = review.add(BigDecimal.valueOf(Math.max(0, owed)));
            }
        }
        card.setTotalScore(total.setScale(2, RoundingMode.HALF_UP));
        card.setMaxScore(max.setScale(2, RoundingMode.HALF_UP));
        card.setPercent(EvaluationRules.percent(total, max));
        card.setPassThreshold(PASS_THRESHOLD);
        card.setPassed(card.getPercent().compareTo(BigDecimal.valueOf(PASS_THRESHOLD)) >= 0);
        card.setNeedsReview(needsReview);
        card.setReviewPoints(review.setScale(2, RoundingMode.HALF_UP));
        card.setStatus(ScorecardStatus.SCORED);
        card.setError(null);
        card.setScoredAt(Instant.now());
        scorecards.save(card);
    }

    private static boolean answered(Item it) {
        return switch (it.type()) {
            case MCQ, MSQ -> !it.selected().isEmpty();
            case CODE_WRITE, CODE_DEBUG -> !blank(it.code());
            case CODE_OUTPUT, SCENARIO, LOGIC -> !blank(it.text());
        };
    }

    private static String detail(Graded g, Map<String, Object> tests) {
        StringBuilder sb = new StringBuilder();
        if (tests != null) sb.append("Passed ").append(tests.get("hidden_passed")).append('/').append(tests.get("hidden_total"))
                .append(" hidden tests. ");
        g.evidence().stream().filter(e -> "rule".equals(e.get("kind")) || "pending".equals(e.get("kind")) || "objective".equals(e.get("kind")))
                .forEach(e -> sb.append(e.get("text")).append(' '));
        if (g.flags().contains("GRADER_DISAGREEMENT")) sb.append("Graders disagreed; median of three runs used. ");
        if (g.flags().contains("RUBRIC_UNAVAILABLE")) sb.append("AI grader unavailable — needs manual review. ");
        if (g.flags().contains("RUNNER_UNAVAILABLE")) sb.append("Code runner unavailable — needs manual review. ");
        return sb.toString().trim();
    }
}

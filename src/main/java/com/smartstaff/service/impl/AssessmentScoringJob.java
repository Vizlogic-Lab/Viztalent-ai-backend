package com.smartstaff.service.impl;

import com.smartstaff.entity.*;
import com.smartstaff.repository.*;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.CodeRunnerService.RunResult;
import com.smartstaff.service.CodeRunnerService.RunStatus;
import com.smartstaff.service.CodeRunnerService.TestInput;
import com.smartstaff.service.CodeRunnerService.TestResult;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.ScoringRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Grades one submitted attempt in the background. Deterministic parts
 *  (MCQ/MSQ/LOGIC/CODE_OUTPUT and the coding test runs) are always scored; the
 *  AI parts (CODE_WRITE rubric, SCENARIO) go through AnswerGrader and, when
 *  Gemini is down, are flagged for manual review instead of guessed.
 *
 *  Like the generation job, no sandbox or Gemini call happens inside a
 *  transaction: attempt data is read in one short transaction, scored outside
 *  any transaction, and written back in another. */
@Component
public class AssessmentScoringJob {

    private static final Logger log = LoggerFactory.getLogger(AssessmentScoringJob.class);
    static final int PASS_THRESHOLD = 50;

    private final AssessmentAttemptRepository attempts;
    private final AssessmentQuestionRepository questions;
    private final AssessmentAnswerRepository answers;
    private final AssessmentScorecardRepository scorecards;
    private final SettingsService settings;
    private final CodeRunnerService runner;
    private final AnswerGrader grader;
    private final TransactionTemplate tx;

    public AssessmentScoringJob(AssessmentAttemptRepository attempts, AssessmentQuestionRepository questions,
                                AssessmentAnswerRepository answers, AssessmentScorecardRepository scorecards,
                                SettingsService settings, CodeRunnerService runner, AnswerGrader grader,
                                PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.questions = questions;
        this.answers = answers;
        this.scorecards = scorecards;
        this.settings = settings;
        this.runner = runner;
        this.grader = grader;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Any scorecard left mid-scoring by a restart is retried on the next start. */
    @EventListener(ApplicationReadyEvent.class)
    public void rescoreInterrupted() {
        List<UUID> stuck = tx.execute(s -> scorecards.findAll().stream()
                .filter(c -> c.getStatus().inProgress())
                .map(c -> c.getAttempt().getId())
                .toList());
        if (stuck != null) stuck.forEach(this::run);
    }

    @Async("scoringExecutor")
    public void run(UUID attemptId) {
        try {
            score(attemptId);
        } catch (Exception e) {
            log.error("Scoring attempt {} failed", attemptId, e);
            markFailed(attemptId, "Scoring stopped unexpectedly. Try re-scoring.");
        }
    }

    // ── loaded, transaction-free view of what to score ──────────────────

    private record TestData(int seq, String input, String expected, BigDecimal weight, BigDecimal tolerance) {}

    private record Item(UUID questionId, String level, int seq, QuestionType type, int points,
                        List<Integer> correctIndices, String modelAnswer, List<String> keyPoints,
                        String prompt, String expectedComplexity, List<RubricCriterion> rubric,
                        List<String> languages, List<TestData> tests,
                        boolean answered, List<Integer> selected, String codeLanguage, String code, String textAnswer) {}

    private record Loaded(UUID scorecardId, String apiKey, List<Item> items) {}

    private void score(UUID attemptId) {
        Loaded loaded = tx.execute(s -> beginScoring(attemptId));
        if (loaded == null) return;

        String apiKey = loaded.apiKey();
        List<Scored> results = new ArrayList<>();
        for (Item item : loaded.items()) {
            results.add(scoreItem(item, apiKey));
        }
        tx.executeWithoutResult(s -> writeScorecard(loaded.scorecardId(), results));
    }

    /** Creates (or reuses) the scorecard, marks it SCORING, and snapshots the
     *  questions and answers to score. Returns null if the attempt is gone or
     *  wasn't submitted. */
    private Loaded beginScoring(UUID attemptId) {
        AssessmentAttempt attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getStatus() != AttemptStatus.SUBMITTED) return null;

        AssessmentScorecard card = scorecards.findByAttemptId(attemptId).orElseGet(AssessmentScorecard::new);
        card.setAttempt(attempt);
        card.setJob(attempt.getJob());
        card.setAssessment(attempt.getAssessment());
        card.setStatus(ScorecardStatus.SCORING);
        card.setError(null);
        card.getQuestionScores().clear();
        scorecards.save(card);

        Set<String> levels = new HashSet<>(attempt.getLevels());
        Map<UUID, AssessmentAnswer> byQuestion = new HashMap<>();
        for (AssessmentAnswer a : answers.findByAttemptId(attemptId)) byQuestion.put(a.getQuestion().getId(), a);

        List<Item> items = new ArrayList<>();
        for (AssessmentQuestion q : questions.findByAssessmentIdOrderByLevelAscSeqAsc(attempt.getAssessment().getId())) {
            if (!levels.contains(q.getLevel())) continue;
            List<TestData> tests = q.getTestCases().stream()
                    .map(t -> new TestData(t.getSeq(), t.getInput(), t.getExpectedOutput(), t.getWeight(), t.getFloatTolerance()))
                    .toList();
            AssessmentAnswer a = byQuestion.get(q.getId());
            items.add(new Item(q.getId(), q.getLevel(), q.getSeq(), q.getType(), q.getPoints(),
                    List.copyOf(q.getCorrectIndices()), q.getModelAnswer(), List.copyOf(q.getKeyPoints()),
                    q.getPrompt(), q.getExpectedComplexity(), List.copyOf(q.getRubric()), List.copyOf(q.getLanguages()),
                    tests,
                    a != null, a == null ? List.of() : List.copyOf(a.getSelectedIndices()),
                    a == null ? null : a.getCodeLanguage(), a == null ? null : a.getCode(),
                    a == null ? null : a.getTextAnswer()));
        }
        return new Loaded(card.getId(), settings.getGeminiApiKeyOrNull(), items);
    }

    // ── per-question scoring ────────────────────────────────────────────

    private record Scored(UUID questionId, String level, int seq, QuestionType type, int maxPoints,
                          BigDecimal score, boolean autoGraded, boolean needsReview, boolean answered,
                          Integer testsPassed, Integer testsTotal, List<QuestionScoreBreakdown> breakdown, String detail,
                          BigDecimal reviewPoints) {}

    private Scored scoreItem(Item item, String apiKey) {
        boolean answered = item.answered() && hasContent(item);
        if (!answered) {
            return new Scored(item.questionId(), item.level(), item.seq(), item.type(), item.points(),
                    BigDecimal.ZERO, true, false, false, null, null, List.of(), "No answer.", BigDecimal.ZERO);
        }
        return switch (item.type()) {
            case MCQ -> deterministic(item, ScoringRules.mcq(item.correctIndices(), item.selected()), "Multiple choice.");
            case MSQ -> deterministic(item, ScoringRules.msq(item.correctIndices(), item.selected()), "Multiple select (partial credit).");
            case LOGIC -> deterministic(item, ScoringRules.textMatches(item.modelAnswer(), item.textAnswer()) ? 1.0 : 0.0,
                    "Exact-value answer.");
            case CODE_OUTPUT -> deterministic(item, ScoringRules.textMatches(item.modelAnswer(), item.textAnswer()) ? 1.0 : 0.0,
                    "Predicted output vs actual.");
            case CODE_DEBUG -> scoreCodeDebug(item);
            case CODE_WRITE -> scoreCodeWrite(item, apiKey);
            case SCENARIO -> scoreScenario(item, apiKey);
        };
    }

    private Scored scoreScenario(Item item, String apiKey) {
        AnswerGrader.Grade grade = grader.gradeScenario(apiKey, rebuildQuestion(item), item.textAnswer());
        if (!grade.graded()) {
            return needsReview(item, "Written answer needs manual review (AI grader unavailable).");
        }
        BigDecimal score = ScoringRules.points(grade.ratio(), item.points());
        long covered = grade.lines().stream().filter(l -> l.awarded() > 0).count();
        return new Scored(item.questionId(), item.level(), item.seq(), item.type(), item.points(),
                score, true, false, true, null, null, grade.lines(),
                "Covered " + covered + "/" + grade.lines().size() + " key points.", BigDecimal.ZERO);
    }

    private Scored deterministic(Item item, double fraction, String detail) {
        BigDecimal score = ScoringRules.points(fraction, item.points());
        return new Scored(item.questionId(), item.level(), item.seq(), item.type(), item.points(),
                score, true, false, true, null, null, List.of(), detail, BigDecimal.ZERO);
    }

    private Scored scoreCodeDebug(Item item) {
        RunOutcome run = runCode(item);
        if (run.unavailable()) return needsReview(item, run.detail());
        if (run.compileError()) return codeFail(item, run.total(), "The submitted code did not compile.");
        double ratio = ScoringRules.testRatio(run.passedWeight(), run.totalWeight());
        BigDecimal score = ScoringRules.points(ratio, item.points());
        return new Scored(item.questionId(), item.level(), item.seq(), item.type(), item.points(),
                score, true, false, true, run.passed(), run.total(), List.of(),
                "Passed " + run.passed() + "/" + run.total() + " tests.", BigDecimal.ZERO);
    }

    private Scored scoreCodeWrite(Item item, String apiKey) {
        RunOutcome run = runCode(item);
        if (run.unavailable()) return needsReview(item, run.detail());
        double testRatio = run.compileError() ? 0.0 : ScoringRules.testRatio(run.passedWeight(), run.totalWeight());

        // Rubric quality (30%): only worth AI-grading code that compiles.
        AnswerGrader.Grade rubric = run.compileError()
                ? new AnswerGrader.Grade(List.of(), false)
                : grader.gradeCodeWrite(apiKey, rebuildQuestion(item), runLanguage(item), item.code());
        boolean rubricGraded = rubric.graded();

        double fraction = ScoringRules.codeWrite(testRatio, rubric.ratio(), rubricGraded);
        BigDecimal score = ScoringRules.points(fraction, item.points());

        boolean needsReview = !rubricGraded && !run.compileError();
        BigDecimal reviewPoints = needsReview
                ? BigDecimal.valueOf(ScoringRules.CODE_WRITE_RUBRIC_SHARE * item.points()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        String detail = run.compileError()
                ? "Did not compile — no test or quality credit."
                : "Passed " + run.passed() + "/" + run.total() + " tests (70%)"
                    + (rubricGraded ? "; rubric " + pct(rubric.ratio()) + " (30%)." : "; rubric needs manual review (30%).");
        return new Scored(item.questionId(), item.level(), item.seq(), item.type(), item.points(),
                score, rubricGraded || run.compileError(), needsReview, true,
                run.passed(), run.total(), rubric.lines(), detail, reviewPoints);
    }

    private Scored needsReview(Item item, String detail) {
        return new Scored(item.questionId(), item.level(), item.seq(), item.type(), item.points(),
                BigDecimal.ZERO, false, true, true, null, null, List.of(), detail,
                BigDecimal.valueOf(item.points()).setScale(2, RoundingMode.HALF_UP));
    }

    private Scored codeFail(Item item, Integer total, String detail) {
        return new Scored(item.questionId(), item.level(), item.seq(), item.type(), item.points(),
                BigDecimal.ZERO, true, false, true, 0, total, List.of(), detail, BigDecimal.ZERO);
    }

    // ── running the candidate's code against ALL tests ──────────────────

    private record RunOutcome(boolean unavailable, boolean compileError, int passed, int total,
                              double passedWeight, double totalWeight, String detail) {}

    private RunOutcome runCode(Item item) {
        if (item.tests().isEmpty()) {
            return new RunOutcome(true, false, 0, 0, 0, 0, "No tests to score against — needs manual review.");
        }
        String language = runLanguage(item);
        if (language == null) {
            return new RunOutcome(true, false, 0, 0, 0, 0, "No code submitted — needs manual review.");
        }
        if (!runner.availableLanguages().containsKey(language)) {
            return new RunOutcome(true, false, 0, 0, 0, 0, "Code runner unavailable for " + language + " — needs manual review.");
        }
        List<TestInput> inputs = new ArrayList<>();
        for (TestData t : item.tests()) inputs.add(new TestInput(t.seq(), t.input(), t.expected(), t.tolerance()));
        RunResult result = runner.runTests(language, item.code(), inputs, CodeRunnerService.Limits.DEFAULT);
        if (result.status() != RunStatus.OK) {
            return new RunOutcome(true, false, 0, item.tests().size(), 0, 0,
                    "Code runner " + result.status() + " — needs manual review.");
        }
        if (result.compileError() != null) {
            return new RunOutcome(false, true, 0, item.tests().size(), 0,
                    item.tests().stream().mapToDouble(t -> t.weight().doubleValue()).sum(), "compile error");
        }
        double passedWeight = 0, totalWeight = 0;
        int passed = 0;
        Map<Integer, BigDecimal> weightBySeq = new HashMap<>();
        for (TestData t : item.tests()) weightBySeq.put(t.seq(), t.weight());
        for (TestResult tr : result.tests()) {
            double w = weightBySeq.getOrDefault(tr.seq(), BigDecimal.ONE).doubleValue();
            totalWeight += w;
            if (tr.passed()) {
                passed++;
                passedWeight += w;
            }
        }
        return new RunOutcome(false, false, passed, result.tests().size(), passedWeight, totalWeight, "ok");
    }

    private static String runLanguage(Item item) {
        if (item.code() == null || item.code().isBlank()) return null;
        if (item.codeLanguage() != null && item.languages().contains(item.codeLanguage())) return item.codeLanguage();
        return item.languages().isEmpty() ? null : item.languages().get(0);
    }

    /** A throwaway question carrying just what AnswerGrader reads (prompt,
     *  complexity, rubric) — the entity itself isn't available outside its tx. */
    private static AssessmentQuestion rebuildQuestion(Item item) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setType(item.type());
        q.setPrompt(item.prompt());
        q.setExpectedComplexity(item.expectedComplexity());
        q.setRubric(new ArrayList<>(item.rubric()));
        q.setKeyPoints(new ArrayList<>(item.keyPoints()));
        q.setModelAnswer(item.modelAnswer());
        return q;
    }

    // ── writing the scorecard ───────────────────────────────────────────

    private void writeScorecard(UUID scorecardId, List<Scored> results) {
        AssessmentScorecard card = scorecards.findById(scorecardId).orElse(null);
        if (card == null) return;

        BigDecimal total = BigDecimal.ZERO, max = BigDecimal.ZERO, review = BigDecimal.ZERO;
        boolean needsReview = false;
        card.getQuestionScores().clear();
        for (Scored r : results) {
            AssessmentQuestionScore qs = new AssessmentQuestionScore();
            qs.setScorecard(card);
            qs.setQuestion(questions.getReferenceById(r.questionId()));
            qs.setLevel(r.level());
            qs.setSeq(r.seq());
            qs.setType(r.type().name());
            qs.setMaxPoints(r.maxPoints());
            qs.setScore(r.score());
            qs.setAutoGraded(r.autoGraded());
            qs.setNeedsReview(r.needsReview());
            qs.setAnswered(r.answered());
            qs.setTestsPassed(r.testsPassed());
            qs.setTestsTotal(r.testsTotal());
            qs.setBreakdown(new ArrayList<>(r.breakdown()));
            qs.setDetail(r.detail());
            card.getQuestionScores().add(qs);

            total = total.add(r.score());
            max = max.add(BigDecimal.valueOf(r.maxPoints()));
            review = review.add(r.reviewPoints());
            needsReview |= r.needsReview();
        }
        card.setTotalScore(total);
        card.setMaxScore(max);
        card.setPercent(ScoringRules.percent(total, max));
        card.setPassThreshold(PASS_THRESHOLD);
        card.setPassed(card.getPercent().compareTo(BigDecimal.valueOf(PASS_THRESHOLD)) >= 0);
        card.setNeedsReview(needsReview);
        card.setReviewPoints(review);
        card.setStatus(ScorecardStatus.SCORED);
        card.setScoredAt(Instant.now());
        scorecards.save(card);
    }

    private void markFailed(UUID attemptId, String error) {
        try {
            tx.executeWithoutResult(s -> scorecards.findByAttemptId(attemptId).ifPresent(c -> {
                c.setStatus(ScorecardStatus.FAILED);
                c.setError(error);
            }));
        } catch (Exception e) {
            log.error("Could not mark scorecard for attempt {} failed", attemptId, e);
        }
    }

    private static String pct(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    private static boolean hasContent(Item item) {
        return switch (item.type()) {
            case MCQ, MSQ -> item.selected() != null && !item.selected().isEmpty();
            case CODE_WRITE, CODE_DEBUG -> item.code() != null && !item.code().isBlank();
            case CODE_OUTPUT, SCENARIO, LOGIC -> item.textAnswer() != null && !item.textAnswer().isBlank();
        };
    }
}

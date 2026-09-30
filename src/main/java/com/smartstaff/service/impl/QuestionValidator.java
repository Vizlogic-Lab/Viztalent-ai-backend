package com.smartstaff.service.impl;

import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.QuestionTestCase;
import com.smartstaff.entity.QuestionType;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.CodeRunnerService.Limits;
import com.smartstaff.service.CodeRunnerService.RunResult;
import com.smartstaff.service.CodeRunnerService.RunStatus;
import com.smartstaff.service.CodeRunnerService.TestInput;
import com.smartstaff.service.CodeRunnerService.TestResult;
import com.smartstaff.util.OutputComparator;
import org.springframework.stereotype.Component;

import java.util.*;

/** Proves a drafted question before it can be used. Coding questions are
 *  checked by running them in the sandbox:
 *  - CODE_WRITE: the reference passes 100% in every language, and the naive
 *    solution fails at least 2 hidden tests (so the tests discriminate);
 *  - CODE_DEBUG: the fixed code passes every test and the buggy code fails at
 *    least one hidden test (a visible-only failure would let an unchanged
 *    submission score full marks);
 *  - CODE_OUTPUT: the expected output IS the output of running the snippet.
 *  Every question must target a skill in the role profile. */
@Component
public class QuestionValidator {

    static final int MIN_VISIBLE_WRITE = 3, MIN_HIDDEN_WRITE = 8;
    static final int MIN_VISIBLE_DEBUG = 2, MIN_HIDDEN_DEBUG = 4;
    static final int MIN_NAIVE_HIDDEN_FAILS = 2;
    static final int MAX_OUTPUT_CHARS = 1024;

    private final CodeRunnerService runner;

    public QuestionValidator(CodeRunnerService runner) {
        this.runner = runner;
    }

    /** runnerUnavailable: the sandbox itself couldn't run the check, so
     *  regenerating the same kind of question won't help. */
    public record Result(boolean ok, String reason, String log, boolean runnerUnavailable) {
        static Result pass(String log) {
            return new Result(true, null, log, false);
        }

        static Result fail(String reason) {
            return new Result(false, reason, reason, false);
        }

        static Result runnerDown(String reason) {
            return new Result(false, reason, reason, true);
        }
    }

    public Result validate(QuestionDraft draft, Set<String> profileSkills, String targetSkill) {
        AssessmentQuestion q = draft.question();
        Result skill = checkSkill(q, profileSkills, targetSkill);
        if (skill != null) return skill;
        if (q.getPrompt() == null || q.getPrompt().isBlank()) return Result.fail("The question text is empty.");

        return switch (q.getType()) {
            case MCQ, MSQ -> choice(q);
            case SCENARIO -> scenario(q);
            case LOGIC -> logic(q);
            case CODE_WRITE -> codeWrite(q, draft.naiveSolution());
            case CODE_DEBUG -> codeDebug(q);
            case CODE_OUTPUT -> codeOutput(q);
        };
    }

    private static Result checkSkill(AssessmentQuestion q, Set<String> profileSkills, String targetSkill) {
        if (q.getSkill() == null || q.getSkill().isBlank()) {
            q.setSkill(targetSkill);
            return null;
        }
        String claimed = q.getSkill().trim().toLowerCase(Locale.ROOT);
        if (targetSkill != null && claimed.equals(targetSkill.toLowerCase(Locale.ROOT))) {
            q.setSkill(targetSkill);
            return null;
        }
        if (profileSkills.isEmpty() || profileSkills.contains(claimed)) {
            q.setSkill(claimed);
            return null;
        }
        return Result.fail("It targets '" + q.getSkill() + "', which isn't a skill in this role profile"
                + (targetSkill == null ? "." : "; target '" + targetSkill + "'."));
    }

    // ── theory and written ──────────────────────────────────────────────

    private static Result choice(AssessmentQuestion q) {
        List<String> options = q.getOptions();
        if (options.size() < 2 || options.size() > 6) return Result.fail("It needs 2 to 6 options.");
        Set<String> seen = new HashSet<>();
        for (String o : options) {
            String key = o == null ? "" : o.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) return Result.fail("An option is empty.");
            if (!seen.add(key)) return Result.fail("Two options are the same: '" + o + "'.");
        }
        List<Integer> correct = q.getCorrectIndices();
        if (correct.stream().anyMatch(i -> i == null || i < 0 || i >= options.size())) {
            return Result.fail("A correct index is outside the options.");
        }
        if (new HashSet<>(correct).size() != correct.size()) return Result.fail("A correct index is repeated.");
        if (q.getType() == QuestionType.MCQ && correct.size() != 1) {
            return Result.fail("A multiple-choice question needs exactly one correct option, found " + correct.size() + ".");
        }
        if (q.getType() == QuestionType.MSQ && correct.size() < 2) {
            return Result.fail("A multiple-select question needs at least two correct options.");
        }
        return Result.pass("Options and answer indices checked.");
    }

    private static Result scenario(AssessmentQuestion q) {
        if (q.getModelAnswer() == null || q.getModelAnswer().isBlank()) return Result.fail("The model answer is missing.");
        long points = q.getKeyPoints().stream().filter(p -> p != null && !p.isBlank()).count();
        if (points < 4 || points > 6 || points != q.getKeyPoints().size()) {
            return Result.fail("It needs 4 to 6 non-empty key points, found " + points + ".");
        }
        return Result.pass("Model answer and " + points + " key points present.");
    }

    private static Result logic(AssessmentQuestion q) {
        String answer = q.getModelAnswer();
        if (answer == null || answer.isBlank()) return Result.fail("The answer is missing.");
        if (answer.trim().contains("\n") || answer.trim().length() > 100) {
            return Result.fail("The answer must be a single short value.");
        }
        q.setModelAnswer(answer.trim());
        return Result.pass("Single-value answer present.");
    }

    // ── code ────────────────────────────────────────────────────────────

    private Result codeWrite(AssessmentQuestion q, Map<String, String> naive) {
        Result shape = testShape(q, MIN_VISIBLE_WRITE, MIN_HIDDEN_WRITE);
        if (shape != null) return shape;
        Result code = codeFor(q, q.getReferenceSolution(), "reference solution");
        if (code != null) return code;
        for (String lang : q.getLanguages()) {
            if (!q.getStarterCode().containsKey(lang)) return Result.fail("Starter code for " + lang + " is missing.");
        }

        StringBuilder log = new StringBuilder();
        for (String lang : q.getLanguages()) {
            RunResult r = runner.runTests(lang, q.getReferenceSolution().get(lang), inputs(q), Limits.DEFAULT);
            Result problem = mustPassAll(r, q, lang, "reference solution");
            if (problem != null) return problem;
            log.append(lang).append(" reference passed ").append(r.passedCount()).append('/').append(r.tests().size()).append(". ");
        }

        String lang = q.getLanguages().get(0);
        String naiveCode = naive == null ? null : naive.get(lang);
        if (naiveCode == null || naiveCode.isBlank()) return Result.fail("naive_solution for " + lang + " is missing.");
        RunResult n = runner.runTests(lang, naiveCode, inputs(q), Limits.DEFAULT);
        if (n.status() != RunStatus.OK) return Result.runnerDown("Code runner: " + n.message());
        long hiddenFails = hiddenFailures(q, n);
        if (hiddenFails < MIN_NAIVE_HIDDEN_FAILS) {
            return Result.fail("The naive solution fails only " + hiddenFails + " hidden test(s); the hidden tests must "
                    + "catch flawed solutions (at least " + MIN_NAIVE_HIDDEN_FAILS + ").");
        }
        log.append("Naive solution failed ").append(hiddenFails).append(" hidden test(s).");
        return Result.pass(log.toString().trim());
    }

    private Result codeDebug(AssessmentQuestion q) {
        Result shape = testShape(q, MIN_VISIBLE_DEBUG, MIN_HIDDEN_DEBUG);
        if (shape != null) return shape;
        Result fixed = codeFor(q, q.getReferenceSolution(), "fixed code");
        if (fixed != null) return fixed;
        Result buggy = codeFor(q, q.getBuggyCode(), "buggy code");
        if (buggy != null) return buggy;
        if (q.getBugDescriptions().isEmpty() || q.getBugDescriptions().size() > 3) {
            return Result.fail("Describe 1 to 3 planted bugs.");
        }

        StringBuilder log = new StringBuilder();
        for (String lang : q.getLanguages()) {
            RunResult f = runner.runTests(lang, q.getReferenceSolution().get(lang), inputs(q), Limits.DEFAULT);
            Result problem = mustPassAll(f, q, lang, "fixed code");
            if (problem != null) return problem;
            RunResult b = runner.runTests(lang, q.getBuggyCode().get(lang), inputs(q), Limits.DEFAULT);
            if (b.status() != RunStatus.OK) return Result.runnerDown("Code runner: " + b.message());
            long hiddenFails = hiddenFailures(q, b);
            if (hiddenFails < 1) return Result.fail("The buggy " + lang + " code passes every hidden test, so the bugs aren't caught.");
            log.append(lang).append(": fixed passed ").append(f.passedCount()).append('/').append(f.tests().size())
                    .append(", buggy failed ").append(hiddenFails).append(" hidden. ");
        }
        return Result.pass(log.toString().trim());
    }

    private Result codeOutput(AssessmentQuestion q) {
        if (q.getLanguages().isEmpty()) return Result.fail("No language for the snippet.");
        String lang = q.getLanguages().get(0);
        String code = q.getStarterCode().get(lang);
        if (code == null || code.isBlank()) return Result.fail("The " + lang + " snippet is missing.");

        RunResult r = runner.runTests(lang, code, List.of(new TestInput(0, "", "")), Limits.DEFAULT);
        if (r.status() != RunStatus.OK) return Result.runnerDown("Code runner: " + r.message());
        if (r.compileError() != null) return Result.fail("The snippet doesn't compile: " + shorten(r.compileError()));
        TestResult t = r.tests().get(0);
        if (t.status() != CodeRunnerService.TestStatus.PASSED && t.status() != CodeRunnerService.TestStatus.FAILED) {
            return Result.fail("The snippet doesn't run cleanly (" + t.status() + "): " + shorten(t.stderr()));
        }
        String output = OutputComparator.normalise(t.actualOutput());
        if (output.isBlank()) return Result.fail("The snippet prints nothing.");
        if (output.length() > MAX_OUTPUT_CHARS) return Result.fail("The snippet's output is longer than 1 KB.");
        q.setModelAnswer(output);
        return Result.pass("Expected output taken from running the " + lang + " snippet.");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Result testShape(AssessmentQuestion q, int minVisible, int minHidden) {
        if (q.getLanguages().isEmpty()) return Result.fail("No languages for this question.");
        long visible = q.getTestCases().stream().filter(QuestionTestCase::isVisible).count();
        long hidden = q.getTestCases().size() - visible;
        if (visible < minVisible || hidden < minHidden) {
            return Result.fail("It needs at least " + minVisible + " visible and " + minHidden + " hidden tests; found "
                    + visible + " visible and " + hidden + " hidden.");
        }
        return null;
    }

    private static Result codeFor(AssessmentQuestion q, Map<String, String> code, String what) {
        for (String lang : q.getLanguages()) {
            String c = code.get(lang);
            if (c == null || c.isBlank()) return Result.fail("The " + what + " for " + lang + " is missing.");
        }
        return null;
    }

    private static Result mustPassAll(RunResult r, AssessmentQuestion q, String lang, String what) {
        if (r.status() != RunStatus.OK) return Result.runnerDown("Code runner: " + r.message());
        if (r.compileError() != null) {
            return Result.fail("The " + lang + " " + what + " doesn't compile: " + shorten(r.compileError()));
        }
        for (TestResult t : r.tests()) {
            if (t.passed()) continue;
            QuestionTestCase tc = q.getTestCases().get(t.seq());
            return Result.fail("The " + lang + " " + what + " fails " + (r.tests().size() - r.passedCount()) + " of "
                    + r.tests().size() + " tests (test " + (t.seq() + 1) + ", " + t.status() + ": input '"
                    + shorten(tc.getInput()) + "', expected '" + shorten(tc.getExpectedOutput()) + "', got '"
                    + shorten(t.actualOutput()) + "'). Tests and solution must agree.");
        }
        return null;
    }

    private static long hiddenFailures(AssessmentQuestion q, RunResult r) {
        return r.tests().stream()
                .filter(t -> !t.passed() && !q.getTestCases().get(t.seq()).isVisible())
                .count();
    }

    private static List<TestInput> inputs(AssessmentQuestion q) {
        List<TestInput> out = new ArrayList<>();
        for (int i = 0; i < q.getTestCases().size(); i++) {
            QuestionTestCase t = q.getTestCases().get(i);
            out.add(new TestInput(i, t.getInput(), t.getExpectedOutput(), t.getFloatTolerance()));
        }
        return out;
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String one = s.replace("\n", "\\n");
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}

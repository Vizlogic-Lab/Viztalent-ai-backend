package com.smartstaff.service.impl;

import com.smartstaff.dto.request.AssessmentAnswerInput;
import com.smartstaff.dto.request.AssessmentRunRequest;
import com.smartstaff.dto.request.AssessmentSaveRequest;
import com.smartstaff.dto.response.AssessmentRunResponse;
import com.smartstaff.dto.response.AssessmentRunResponse.VisibleTestResult;
import com.smartstaff.dto.response.CandidateAnswerView;
import com.smartstaff.dto.response.CandidateAssessmentResponse;
import com.smartstaff.dto.response.CandidateQuestionView;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.AssessmentMapper;
import com.smartstaff.repository.*;
import com.smartstaff.service.CandidateAssessmentService;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.CodeRunnerService.RunResult;
import com.smartstaff.service.CodeRunnerService.RunStatus;
import com.smartstaff.service.CodeRunnerService.TestInput;
import com.smartstaff.service.CodeRunnerService.TestResult;
import com.smartstaff.util.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Implements the candidate flow. The invite is the only credential; every
 *  method resolves it to an attempt, enforces the per-attempt deadline
 *  server-side, and only ever exposes the candidate view of a question — never
 *  answers, solutions, hidden tests or rubric. */
@Service
public class CandidateAssessmentServiceImpl implements CandidateAssessmentService {

    /** Time budget for a question with no estimate of its own. */
    static final int DEFAULT_QUESTION_SECONDS = 120;

    private final InviteRepository inviteRepository;
    private final AssessmentAttemptRepository attemptRepository;
    private final AssessmentAnswerRepository answerRepository;
    private final AssessmentQuestionRepository questionRepository;
    private final AssessmentMapper assessmentMapper;
    private final CodeRunnerService codeRunner;

    public CandidateAssessmentServiceImpl(InviteRepository inviteRepository,
                                          AssessmentAttemptRepository attemptRepository,
                                          AssessmentAnswerRepository answerRepository,
                                          AssessmentQuestionRepository questionRepository,
                                          AssessmentMapper assessmentMapper,
                                          CodeRunnerService codeRunner) {
        this.inviteRepository = inviteRepository;
        this.attemptRepository = attemptRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.assessmentMapper = assessmentMapper;
        this.codeRunner = codeRunner;
    }

    // ── by_token ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CandidateAssessmentResponse byToken(String token) {
        Invite invite = resolveInvite(token);
        AssessmentAttempt attempt = attemptRepository.findByInviteId(invite.getId()).orElse(null);
        if (attempt == null) {
            if (invite.getExpiresAt().isBefore(Instant.now())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "This assessment link has expired.");
            }
            attempt = createAttempt(invite);
        }
        autoSubmitIfPastDeadline(attempt);
        return view(attempt);
    }

    // ── save ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CandidateAssessmentResponse save(String token, AssessmentSaveRequest req) {
        AssessmentAttempt attempt = existingAttempt(token);
        if (autoSubmitIfPastDeadline(attempt)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Time is up — your saved answers have been submitted.", "time_up");
        }
        if (attempt.getStatus().isFinal()) {
            throw new ApiException(HttpStatus.CONFLICT, "This assessment has already been submitted.", "already_submitted");
        }
        applyAnswers(attempt, req);
        return view(attempt);
    }

    // ── submit ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CandidateAssessmentResponse submit(String token, AssessmentSaveRequest req) {
        AssessmentAttempt attempt = existingAttempt(token);
        if (attempt.getStatus().isFinal()) {
            throw new ApiException(HttpStatus.CONFLICT, "This assessment has already been submitted.", "already_submitted");
        }
        Instant now = Instant.now();
        boolean pastDeadline = now.isAfter(attempt.getDeadline());
        if (!pastDeadline) applyAnswers(attempt, req);

        // Atomic single-use redemption; the loser of a concurrent submit sees 0.
        int consumed = inviteRepository.consume(attempt.getInvite().getId(), now);
        if (consumed == 0 && inviteRepository.findById(attempt.getInvite().getId())
                .map(i -> i.getUsedAt() != null).orElse(false)) {
            throw new ApiException(HttpStatus.CONFLICT, "This assessment has already been submitted.", "already_submitted");
        }
        finalise(attempt, pastDeadline, now);
        return view(attempt);
    }

    // ── run (visible tests only) ────────────────────────────────────────

    @Override
    @Transactional
    public AssessmentRunResponse run(String token, AssessmentRunRequest req) {
        AssessmentAttempt attempt = existingAttempt(token);
        if (autoSubmitIfPastDeadline(attempt) || attempt.getStatus().isFinal()) {
            throw new ApiException(HttpStatus.CONFLICT, "This assessment is closed — you can no longer run code.", "closed");
        }
        AssessmentQuestion q = questionOf(attempt, req.question_id());
        if (q.getType() != QuestionType.CODE_WRITE && q.getType() != QuestionType.CODE_DEBUG) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This question doesn't take runnable code.");
        }
        String language = req.language() == null ? "" : req.language().trim().toLowerCase(Locale.ROOT);
        if (!q.getLanguages().contains(language)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Choose one of: " + String.join(", ", q.getLanguages()) + ".");
        }
        String code = req.code() == null ? "" : req.code();

        // Keep the latest code as the saved answer, so a run doubles as autosave.
        storeCodeAnswer(attempt, q, language, code);

        List<QuestionTestCase> visible = q.getTestCases().stream().filter(QuestionTestCase::isVisible).toList();
        if (visible.isEmpty()) {
            return new AssessmentRunResponse(true, "OK", "This question has no sample tests to run against.",
                    null, 0, 0, List.of());
        }
        if (!codeRunner.availableLanguages().containsKey(language)) {
            return AssessmentRunResponse.unavailable("RUNNER_UNAVAILABLE",
                    "The code runner is unavailable right now — your code was saved; you can still submit.");
        }

        List<TestInput> inputs = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            QuestionTestCase t = visible.get(i);
            inputs.add(new TestInput(i, t.getInput(), t.getExpectedOutput(), t.getFloatTolerance()));
        }
        RunResult result = codeRunner.runTests(language, code, inputs, CodeRunnerService.Limits.DEFAULT);
        if (result.status() != RunStatus.OK) {
            return AssessmentRunResponse.unavailable(result.status().name(), runnerMessage(result.status()));
        }

        List<VisibleTestResult> tests = new ArrayList<>();
        int passed = 0;
        for (TestResult tr : result.tests()) {
            QuestionTestCase t = visible.get(tr.seq());
            if (tr.passed()) passed++;
            tests.add(new VisibleTestResult(t.getSeq(), t.getInput(), t.getExpectedOutput(),
                    tr.actualOutput(), tr.stderr(), tr.passed(), tr.status().name(), tr.timeMs()));
        }
        return new AssessmentRunResponse(true, "OK", null, result.compileError(), passed, tests.size(), tests);
    }

    // ── attempt lifecycle ───────────────────────────────────────────────

    private AssessmentAttempt createAttempt(Invite invite) {
        Assessment assessment = invite.getAssessment();
        List<AssessmentQuestion> questions = questionsFor(assessment, invite.getLevels());
        long budgetSeconds = questions.stream()
                .mapToLong(q -> q.getTimeEstimateSec() != null ? q.getTimeEstimateSec() : DEFAULT_QUESTION_SECONDS)
                .sum();
        Instant now = Instant.now();
        Instant deadline = now.plusSeconds(budgetSeconds);
        if (deadline.isAfter(invite.getExpiresAt())) deadline = invite.getExpiresAt();

        AssessmentAttempt attempt = new AssessmentAttempt();
        attempt.setInvite(invite);
        attempt.setAssessment(assessment);
        attempt.setJob(invite.getJob());
        attempt.setCandidateEmail(invite.getCandidateEmail());
        attempt.setCandidateName(invite.getCandidateName());
        attempt.setLevels(new ArrayList<>(invite.getLevels()));
        attempt.setCombined(invite.isCombined());
        attempt.setStartedAt(now);
        attempt.setDeadline(deadline);
        return attemptRepository.save(attempt);
    }

    /** Marks an IN_PROGRESS attempt whose deadline has passed as submitted
     *  (with whatever was saved). Returns true if it did so this call. */
    private boolean autoSubmitIfPastDeadline(AssessmentAttempt attempt) {
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS && Instant.now().isAfter(attempt.getDeadline())) {
            inviteRepository.consume(attempt.getInvite().getId(), Instant.now());
            finalise(attempt, true, attempt.getDeadline());
            return true;
        }
        return false;
    }

    private void finalise(AssessmentAttempt attempt, boolean auto, Instant now) {
        attempt.setStatus(AttemptStatus.SUBMITTED);
        attempt.setAutoSubmitted(auto);
        attempt.setSubmittedAt(auto ? attempt.getDeadline() : now);
        attemptRepository.save(attempt);
    }

    // ── answers ─────────────────────────────────────────────────────────

    private void applyAnswers(AssessmentAttempt attempt, AssessmentSaveRequest req) {
        if (req == null || req.answers() == null) return;
        Map<UUID, AssessmentQuestion> byId = questionIndex(attempt);
        for (AssessmentAnswerInput input : req.answers()) {
            AssessmentQuestion q = lookup(byId, input.question_id());
            AssessmentAnswer answer = answerRepository.findByAttemptIdAndQuestionId(attempt.getId(), q.getId())
                    .orElseGet(() -> {
                        AssessmentAnswer a = new AssessmentAnswer();
                        a.setAttempt(attempt);
                        a.setQuestion(q);
                        return a;
                    });
            fill(answer, q, input);
            answer.setUpdatedAt(Instant.now());
            answerRepository.save(answer);
        }
    }

    private void fill(AssessmentAnswer answer, AssessmentQuestion q, AssessmentAnswerInput input) {
        switch (q.getType()) {
            case MCQ, MSQ -> answer.setSelectedIndices(validateIndices(q, input.selected_indices()));
            case CODE_WRITE, CODE_DEBUG -> {
                String language = input.language() == null ? "" : input.language().trim().toLowerCase(Locale.ROOT);
                if (!language.isEmpty() && !q.getLanguages().contains(language)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "Question " + q.getSeq() + ": language must be one of " + String.join(", ", q.getLanguages()) + ".");
                }
                answer.setCodeLanguage(language.isEmpty() ? null : language);
                answer.setCode(input.code());
            }
            case CODE_OUTPUT, SCENARIO, LOGIC -> answer.setTextAnswer(input.text());
        }
    }

    private void storeCodeAnswer(AssessmentAttempt attempt, AssessmentQuestion q, String language, String code) {
        AssessmentAnswer answer = answerRepository.findByAttemptIdAndQuestionId(attempt.getId(), q.getId())
                .orElseGet(() -> {
                    AssessmentAnswer a = new AssessmentAnswer();
                    a.setAttempt(attempt);
                    a.setQuestion(q);
                    return a;
                });
        answer.setCodeLanguage(language);
        answer.setCode(code);
        answer.setUpdatedAt(Instant.now());
        answerRepository.save(answer);
    }

    private static List<Integer> validateIndices(AssessmentQuestion q, List<Integer> indices) {
        if (indices == null || indices.isEmpty()) return new ArrayList<>();
        LinkedHashSet<Integer> clean = new LinkedHashSet<>();
        for (Integer i : indices) {
            if (i == null || i < 0 || i >= q.getOptions().size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Question " + q.getSeq() + ": answer choice " + i + " is out of range.");
            }
            clean.add(i);
        }
        if (q.getType() == QuestionType.MCQ && clean.size() > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Question " + q.getSeq() + " takes a single answer.");
        }
        return new ArrayList<>(clean);
    }

    // ── views ───────────────────────────────────────────────────────────

    private CandidateAssessmentResponse view(AssessmentAttempt attempt) {
        List<AssessmentQuestion> questions = questionsFor(attempt.getAssessment(), attempt.getLevels());
        int totalPoints = questions.stream().mapToInt(AssessmentQuestion::getPoints).sum();
        List<CandidateAnswerView> saved = answerRepository.findByAttemptId(attempt.getId()).stream()
                .map(a -> toAnswerView(a)).toList();
        boolean done = attempt.getStatus().isFinal();
        long remaining = Math.max(0, Duration.between(Instant.now(), attempt.getDeadline()).getSeconds());

        List<CandidateQuestionView> questionViews = done ? null
                : questions.stream().map(assessmentMapper::toCandidateView).toList();
        String message = !done ? null
                : attempt.isAutoSubmitted()
                    ? "Time is up. Your saved answers were submitted automatically."
                    : "Your assessment has been submitted. Thank you.";

        return new CandidateAssessmentResponse(
                true,
                done ? "submitted" : "in_progress",
                attempt.getJob().getTitle(),
                attempt.getCandidateName(),
                attempt.getLevels(),
                attempt.isCombined(),
                questions.size(),
                totalPoints,
                attempt.getStartedAt(),
                attempt.getDeadline(),
                done ? 0 : remaining,
                attempt.getSubmittedAt(),
                attempt.isAutoSubmitted(),
                message,
                questionViews,
                saved);
    }

    private static CandidateAnswerView toAnswerView(AssessmentAnswer a) {
        QuestionType type = a.getQuestion().getType();
        return switch (type) {
            case MCQ, MSQ -> new CandidateAnswerView(a.getQuestion().getId().toString(),
                    a.getSelectedIndices(), null, null, null);
            case CODE_WRITE, CODE_DEBUG -> new CandidateAnswerView(a.getQuestion().getId().toString(),
                    null, a.getCodeLanguage(), a.getCode(), null);
            case CODE_OUTPUT, SCENARIO, LOGIC -> new CandidateAnswerView(a.getQuestion().getId().toString(),
                    null, null, null, a.getTextAnswer());
        };
    }

    // ── lookups ─────────────────────────────────────────────────────────

    private AssessmentAttempt existingAttempt(String token) {
        Invite invite = resolveInvite(token);
        return attemptRepository.findByInviteId(invite.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "This assessment hasn't been started yet — open the link first."));
    }

    private Invite resolveInvite(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This assessment link is invalid.");
        }
        String hash = FileStorageService.sha256Hex(token.getBytes(StandardCharsets.UTF_8));
        Invite invite = inviteRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "This assessment link is invalid."));
        if (!"ASSESSMENT".equals(invite.getKind()) || invite.getAssessment() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This assessment link is invalid.");
        }
        return invite;
    }

    /** The question named by the input, which must belong to this attempt's
     *  invited levels — otherwise a candidate could answer another level's or
     *  version's question. */
    private AssessmentQuestion questionOf(AssessmentAttempt attempt, String questionId) {
        return lookup(questionIndex(attempt), questionId);
    }

    private Map<UUID, AssessmentQuestion> questionIndex(AssessmentAttempt attempt) {
        Map<UUID, AssessmentQuestion> byId = new HashMap<>();
        for (AssessmentQuestion q : questionsFor(attempt.getAssessment(), attempt.getLevels())) byId.put(q.getId(), q);
        return byId;
    }

    private static AssessmentQuestion lookup(Map<UUID, AssessmentQuestion> byId, String questionId) {
        UUID id;
        try {
            id = UUID.fromString(questionId == null ? "" : questionId.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown question.");
        }
        AssessmentQuestion q = byId.get(id);
        if (q == null) throw new ApiException(HttpStatus.BAD_REQUEST, "That question isn't part of this assessment.");
        return q;
    }

    private List<AssessmentQuestion> questionsFor(Assessment assessment, List<String> levels) {
        Set<String> invited = new HashSet<>(levels);
        return questionRepository.findByAssessmentIdOrderByLevelAscSeqAsc(assessment.getId()).stream()
                .filter(q -> invited.contains(q.getLevel()))
                .toList();
    }

    private static String runnerMessage(RunStatus status) {
        return switch (status) {
            case RUNNER_BUSY -> "The code runner is busy — try again in a moment. Your code was saved.";
            case RUNNER_UNAVAILABLE -> "The code runner is unavailable right now. Your code was saved; you can still submit.";
            case UNSUPPORTED_LANGUAGE -> "That language isn't available in the code runner.";
            case OK -> "OK";
        };
    }
}

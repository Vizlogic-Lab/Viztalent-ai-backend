package com.smartstaff.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.dto.request.AssessmentAnswerInput;
import com.smartstaff.dto.request.AssessmentSaveRequest;
import com.smartstaff.dto.request.AttemptEventsRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.dto.response.AssessmentRunResponse.VisibleTestResult;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.AssessmentMapper;
import com.smartstaff.repository.*;
import com.smartstaff.service.AttemptSubmittedEvent;
import com.smartstaff.service.CandidateAssessmentService;
import com.smartstaff.service.CodeRunnerService;
import com.smartstaff.service.CodeRunnerService.RunResult;
import com.smartstaff.service.CodeRunnerService.RunStatus;
import com.smartstaff.service.CodeRunnerService.TestInput;
import com.smartstaff.service.CodeRunnerService.TestResult;
import com.smartstaff.util.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The candidate flow. The invite token is the only credential; it resolves
 *  to the invite, whose levels are served in order as one attempt each. Only
 *  CandidateQuestionView ever leaves the server — never answers, solutions,
 *  hidden tests or rubric — and "Run" executes the visible tests only.
 *
 *  Deadlines are enforced server-side. Once a level's deadline passes, saving
 *  and running are refused (time_up); the attempt itself is submitted by the
 *  next start/resume, by submit, or by the once-a-minute scheduler — never by
 *  a call that then fails, which would roll the auto-submit back. */
@Service
public class CandidateAssessmentServiceImpl implements CandidateAssessmentService {

    static final String INVALID = "This assessment link is invalid.";
    static final String EXPIRED = "This assessment link has expired.";
    static final String USED = "This assessment has already been submitted.";

    /** Time budget for a level whose questions carry no estimates. */
    static final long DEFAULT_LEVEL_SECONDS = 45L * 60;
    static final int EVENT_CAP = 500;

    private final InviteRepository inviteRepository;
    private final AssessmentAttemptRepository attemptRepository;
    private final AssessmentAnswerRepository answerRepository;
    private final AssessmentQuestionRepository questionRepository;
    private final CodeRunRepository codeRunRepository;
    private final AttemptEventRepository eventRepository;
    private final AssessmentMapper assessmentMapper;
    private final CodeRunnerService codeRunner;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final int runLimit;

    public CandidateAssessmentServiceImpl(InviteRepository inviteRepository,
                                          AssessmentAttemptRepository attemptRepository,
                                          AssessmentAnswerRepository answerRepository,
                                          AssessmentQuestionRepository questionRepository,
                                          CodeRunRepository codeRunRepository,
                                          AttemptEventRepository eventRepository,
                                          AssessmentMapper assessmentMapper,
                                          CodeRunnerService codeRunner,
                                          ApplicationEventPublisher events,
                                          ObjectMapper json,
                                          PlatformTransactionManager transactionManager,
                                          @Value("${app.assessment.run-limit-per-question:20}") int runLimit) {
        this.inviteRepository = inviteRepository;
        this.attemptRepository = attemptRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.codeRunRepository = codeRunRepository;
        this.eventRepository = eventRepository;
        this.assessmentMapper = assessmentMapper;
        this.codeRunner = codeRunner;
        this.events = events;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.runLimit = runLimit;
    }

    // ── start / resume ──────────────────────────────────────────────────

    @Override
    @Transactional
    public CandidateAssessmentResponse start(String token) {
        Invite invite = resolveInvite(token);
        List<AssessmentAttempt> attempts = attemptsOf(invite);

        // A level whose time ran out while the candidate was away is submitted
        // now, and that is what they see first (the next level isn't started
        // behind their back — they continue to it with another open).
        AssessmentAttempt justAuto = null;
        for (AssessmentAttempt a : attempts) {
            if (a.getStatus() == AttemptStatus.IN_PROGRESS && Instant.now().isAfter(a.getDeadlineAt())
                    && submitAttempt(invite, a, attempts, true)) {
                justAuto = a;
            }
        }
        if (justAuto != null) return submittedView(invite, justAuto, attempts);

        if (invite.getUsedAt() != null) throw used();
        String level = nextLevel(invite, attempts);
        if (level == null) throw used();

        AssessmentAttempt current = attemptFor(attempts, level);
        if (current == null) {
            if (invite.getExpiresAt().isBefore(Instant.now())) throw new ApiException(HttpStatus.BAD_REQUEST, EXPIRED, "link_expired");
            current = createAttempt(invite, level);
            attempts.add(current);
        }
        return inProgressView(invite, current, attempts);
    }

    // ── autosave ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AnswerSavedResponse saveAnswer(String token, UUID questionId, AssessmentAnswerInput input) {
        Invite invite = resolveInvite(token);
        AssessmentAttempt a = openAttempt(invite, attemptsOf(invite));
        AssessmentQuestion q = questionOf(a, questionId.toString());
        upsert(a, q, input);
        return new AnswerSavedResponse(true, q.getId().toString(), Instant.now());
    }

    @Override
    @Transactional
    public CandidateAssessmentResponse saveAll(String token, AssessmentSaveRequest req) {
        Invite invite = resolveInvite(token);
        List<AssessmentAttempt> attempts = attemptsOf(invite);
        AssessmentAttempt a = openAttempt(invite, attempts);
        if (req != null && req.answers() != null) {
            for (AssessmentAnswerInput input : req.answers()) upsert(a, questionOf(a, input.question_id()), input);
        }
        return inProgressView(invite, a, attempts);
    }

    // ── run (visible tests only) ────────────────────────────────────────

    @Override
    public AssessmentRunResponse run(String token, UUID questionId, String language, String code) {
        record Prep(UUID answerId, String language, String code, List<QuestionTestCase> visible, long used) {}
        String lang = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        String source = code == null ? "" : code;

        // 1. Checks + keep the latest code as the answer (a run doubles as autosave).
        Prep p = tx.execute(s -> {
            Invite invite = resolveInvite(token);
            AssessmentAttempt a = openAttempt(invite, attemptsOf(invite));
            AssessmentQuestion q = questionOf(a, questionId.toString());
            if (q.getType() != QuestionType.CODE_WRITE && q.getType() != QuestionType.CODE_DEBUG) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "This question doesn't take runnable code.");
            }
            if (!q.getLanguages().contains(lang)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Choose one of: " + String.join(", ", q.getLanguages()) + ".");
            }
            AssessmentAnswer answer = upsert(a, q, new AssessmentAnswerInput(q.getId().toString(), null, lang, source, null));
            long used = codeRunRepository.countByAnswerIdAndMode(answer.getId(), CodeRunMode.RUN);
            if (used >= runLimit) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "You've used all " + runLimit + " runs for this question. Your code is saved; you can still submit.",
                        "run_limit");
            }
            List<QuestionTestCase> visible = q.getTestCases().stream().filter(QuestionTestCase::isVisible).toList();
            return new Prep(answer.getId(), lang, source, new ArrayList<>(visible), used);
        });

        // Runs that never reached the sandbox don't count against the limit.
        int remaining = (int) (runLimit - p.used());
        if (p.visible().isEmpty()) {
            return new AssessmentRunResponse(true, "OK", "This question has no sample tests to run against.",
                    null, 0, 0, List.of(), remaining);
        }
        if (!codeRunner.availableLanguages().containsKey(p.language())) {
            return AssessmentRunResponse.unavailable("RUNNER_UNAVAILABLE",
                    "The code runner is unavailable right now. Your code was saved; you can still submit.", remaining);
        }

        // 2. Execute outside any transaction.
        List<TestInput> inputs = new ArrayList<>();
        for (int i = 0; i < p.visible().size(); i++) {
            QuestionTestCase t = p.visible().get(i);
            inputs.add(new TestInput(i, t.getInput(), t.getExpectedOutput(), t.getFloatTolerance()));
        }
        RunResult result = codeRunner.runTests(p.language(), p.code(), inputs, CodeRunnerService.Limits.DEFAULT);
        if (result.status() != RunStatus.OK) {
            return AssessmentRunResponse.unavailable(result.status().name(), runnerMessage(result.status()), remaining);
        }

        List<VisibleTestResult> tests = new ArrayList<>();
        int passed = 0;
        for (TestResult tr : result.tests()) {
            QuestionTestCase t = p.visible().get(tr.seq());
            if (tr.passed()) passed++;
            tests.add(new VisibleTestResult(t.getSeq(), t.getInput(), t.getExpectedOutput(),
                    tr.actualOutput(), tr.stderr(), tr.passed(), tr.status().name(), tr.timeMs()));
        }

        // 3. Record the run.
        tx.executeWithoutResult(s -> {
            CodeRun run = new CodeRun();
            run.setAnswer(answerRepository.getReferenceById(p.answerId()));
            run.setMode(CodeRunMode.RUN);
            run.setLanguage(p.language());
            run.setCompileError(result.compileError());
            run.setResults(json.convertValue(tests, new TypeReference<List<Map<String, Object>>>() {}));
            codeRunRepository.save(run);
        });
        return new AssessmentRunResponse(true, "OK", null, result.compileError(), passed, tests.size(), tests, remaining - 1);
    }

    // ── submit ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CandidateAssessmentResponse submit(String token, AssessmentSaveRequest req) {
        Invite invite = resolveInvite(token);
        List<AssessmentAttempt> attempts = attemptsOf(invite);
        if (invite.getUsedAt() != null) throw used();
        String level = nextLevel(invite, attempts);
        if (level == null) throw used();
        AssessmentAttempt a = attemptFor(attempts, level);
        if (a == null) throw notStarted();

        boolean late = Instant.now().isAfter(a.getDeadlineAt());
        // Mark first: a concurrent second submit blocks here, then loses.
        if (!submitAttempt(invite, a, attempts, late)) throw used();
        if (!late && req != null && req.answers() != null) {
            for (AssessmentAnswerInput input : req.answers()) upsert(a, questionOf(a, input.question_id()), input);
        }
        return submittedView(invite, a, attempts);
    }

    // ── integrity events ────────────────────────────────────────────────

    @Override
    @Transactional
    public AttemptEventsResponse recordEvents(String token, AttemptEventsRequest req) {
        Invite invite = resolveInvite(token);
        List<AssessmentAttempt> attempts = attemptsOf(invite);
        // Batches arrive every few seconds, so one can land just after submit:
        // attach to the open attempt, else the most recently started one.
        AssessmentAttempt a = attempts.stream().filter(x -> x.getStatus() == AttemptStatus.IN_PROGRESS).findFirst()
                .orElseGet(() -> attempts.isEmpty() ? null : attempts.get(attempts.size() - 1));
        if (a == null) throw notStarted();
        if (req == null || req.events() == null) return new AttemptEventsResponse(true, 0, 0);

        long existing = eventRepository.countByAttemptId(a.getId());
        int stored = 0, dropped = 0;
        Instant now = Instant.now();
        for (AttemptEventsRequest.Event e : req.events()) {
            AttemptEventType type;
            try {
                type = AttemptEventType.valueOf(e.type() == null ? "" : e.type().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                dropped++;
                continue;
            }
            if (existing + stored >= EVENT_CAP) {
                dropped++;
                continue;
            }
            AttemptEvent ev = new AttemptEvent();
            ev.setAttempt(a);
            ev.setType(type);
            ev.setPayload(safePayload(e.payload()));
            ev.setAt(e.at() != null && !e.at().isAfter(now.plusSeconds(60)) ? e.at() : now);
            eventRepository.save(ev);
            stored++;
        }
        return new AttemptEventsResponse(true, stored, dropped);
    }

    /** Facts only: numbers, booleans and short strings, at most 10 keys —
     *  so pasted content can never be stored even if a client sent it. */
    private static Map<String, Object> safePayload(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> en : raw.entrySet()) {
            if (out.size() >= 10) break;
            Object v = en.getValue();
            if (v instanceof Number || v instanceof Boolean || (v instanceof String str && str.length() <= 64)) {
                out.put(en.getKey(), v);
            }
        }
        return out;
    }

    // ── scheduler ───────────────────────────────────────────────────────

    @Override
    public int autoSubmitOverdue() {
        int n = 0;
        for (UUID id : attemptRepository.findOverdueIds(Instant.now())) {
            Boolean won = tx.execute(s -> attemptRepository.findById(id)
                    .filter(a -> a.getStatus() == AttemptStatus.IN_PROGRESS)
                    .map(a -> submitAttempt(a.getInvite(), a, attemptsOf(a.getInvite()), true))
                    .orElse(false));
            if (Boolean.TRUE.equals(won)) n++;
        }
        return n;
    }

    // ── attempt lifecycle ───────────────────────────────────────────────

    private AssessmentAttempt createAttempt(Invite invite, String level) {
        Assessment assessment = invite.getAssessment();
        long budget = questionsFor(assessment, level).stream()
                .filter(q -> q.getTimeEstimateSec() != null)
                .mapToLong(AssessmentQuestion::getTimeEstimateSec)
                .sum();
        if (budget <= 0) budget = DEFAULT_LEVEL_SECONDS;
        Instant now = Instant.now();
        Instant deadline = now.plusSeconds(budget);
        if (deadline.isAfter(invite.getExpiresAt())) deadline = invite.getExpiresAt();

        AssessmentAttempt a = new AssessmentAttempt();
        a.setInvite(invite);
        a.setAssessment(assessment);
        a.setVersion(assessment.getVersion());
        a.setLevel(level);
        a.setJob(invite.getJob());
        a.setResumeId(invite.getResumeId());
        a.setCandidateEmail(invite.getCandidateEmail());
        a.setCandidateName(invite.getCandidateName());
        a.setStartedAt(now);
        a.setDeadlineAt(deadline);
        return attemptRepository.save(a);
    }

    /** Atomic IN_PROGRESS → SUBMITTED; on success consume the invite if this
     *  was its last level and queue evaluation (after commit). */
    private boolean submitAttempt(Invite invite, AssessmentAttempt a, List<AssessmentAttempt> attempts, boolean auto) {
        Instant at = auto ? a.getDeadlineAt() : Instant.now();
        if (attemptRepository.markSubmitted(a.getId(), at, auto) == 0) return false;
        // The bulk update bypassed the persistence context; mirror it in memory.
        a.setStatus(AttemptStatus.SUBMITTED);
        a.setSubmittedAt(at);
        a.setAutoSubmitted(auto);
        if (nextLevel(invite, attempts) == null && inviteRepository.consume(invite.getId(), Instant.now()) == 1) {
            invite.setUsedAt(Instant.now());
        }
        events.publishEvent(new AttemptSubmittedEvent(a.getId()));
        return true;
    }

    /** The open attempt of the current level, for save/run. Past its deadline → time_up. */
    private AssessmentAttempt openAttempt(Invite invite, List<AssessmentAttempt> attempts) {
        if (invite.getUsedAt() != null) throw used();
        String level = nextLevel(invite, attempts);
        if (level == null) throw used();
        AssessmentAttempt a = attemptFor(attempts, level);
        if (a == null) throw notStarted();
        if (Instant.now().isAfter(a.getDeadlineAt())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Time is up for this level. Your saved answers will be submitted.", "time_up");
        }
        return a;
    }

    private List<AssessmentAttempt> attemptsOf(Invite invite) {
        return new ArrayList<>(attemptRepository.findByInviteIdOrderByStartedAtAsc(invite.getId()));
    }

    private static AssessmentAttempt attemptFor(List<AssessmentAttempt> attempts, String level) {
        return attempts.stream().filter(a -> a.getLevel().equals(level)).findFirst().orElse(null);
    }

    /** First invite level (in minted order) without a finished attempt. */
    private static String nextLevel(Invite invite, List<AssessmentAttempt> attempts) {
        for (String level : invite.getLevels()) {
            AssessmentAttempt a = attemptFor(attempts, level);
            if (a == null || !a.getStatus().isFinal()) return level;
        }
        return null;
    }

    // ── answers ─────────────────────────────────────────────────────────

    private AssessmentAnswer upsert(AssessmentAttempt attempt, AssessmentQuestion q, AssessmentAnswerInput input) {
        AssessmentAnswer answer = answerRepository.findByAttemptIdAndQuestionId(attempt.getId(), q.getId())
                .orElseGet(() -> {
                    AssessmentAnswer a = new AssessmentAnswer();
                    a.setAttempt(attempt);
                    a.setQuestion(q);
                    a.setQuestionSnapshot(json.convertValue(assessmentMapper.toCandidateView(q),
                            new TypeReference<Map<String, Object>>() {}));
                    return a;
                });
        switch (q.getType()) {
            case MCQ, MSQ -> answer.setSelectedIndices(validateIndices(q, input.selected_indices()));
            case CODE_WRITE, CODE_DEBUG -> {
                String lang = input.language() == null ? "" : input.language().trim().toLowerCase(Locale.ROOT);
                if (!lang.isEmpty() && !q.getLanguages().contains(lang)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "Question " + (q.getSeq() + 1) + ": language must be one of " + String.join(", ", q.getLanguages()) + ".");
                }
                answer.setLanguage(lang.isEmpty() ? null : lang);
                answer.setCode(input.code());
            }
            case CODE_OUTPUT, SCENARIO, LOGIC -> answer.setTextAnswer(input.text());
        }
        answer.setUpdatedAt(Instant.now());
        return answerRepository.save(answer);
    }

    private static List<Integer> validateIndices(AssessmentQuestion q, List<Integer> indices) {
        if (indices == null || indices.isEmpty()) return new ArrayList<>();
        LinkedHashSet<Integer> clean = new LinkedHashSet<>();
        for (Integer i : indices) {
            if (i == null || i < 0 || i >= q.getOptions().size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Question " + (q.getSeq() + 1) + ": answer choice " + i + " is out of range.");
            }
            clean.add(i);
        }
        if (q.getType() == QuestionType.MCQ && clean.size() > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Question " + (q.getSeq() + 1) + " takes a single answer.");
        }
        return new ArrayList<>(clean);
    }

    // ── views ───────────────────────────────────────────────────────────

    private CandidateAssessmentResponse inProgressView(Invite invite, AssessmentAttempt a, List<AssessmentAttempt> attempts) {
        List<AssessmentQuestion> questions = questionsFor(a.getAssessment(), a.getLevel());
        Instant now = Instant.now();
        return new CandidateAssessmentResponse(true, "in_progress",
                invite.getJob().getTitle(), invite.getCandidateName(), invite.getLevels(), invite.isCombined(),
                a.getLevel(), null, levelStatuses(invite, attempts), a.getId().toString(),
                questions.size(), questions.stream().mapToInt(AssessmentQuestion::getPoints).sum(),
                a.getStartedAt(), a.getDeadlineAt(), a.getDeadlineAt(), now,
                Math.max(0, Duration.between(now, a.getDeadlineAt()).getSeconds()),
                null, false, runLimit, null,
                questions.stream().map(assessmentMapper::toCandidateView).toList(),
                savedAnswers(a));
    }

    private CandidateAssessmentResponse submittedView(Invite invite, AssessmentAttempt a, List<AssessmentAttempt> attempts) {
        String next = nextLevel(invite, attempts);
        String message;
        if (a.isAutoSubmitted()) {
            message = next == null ? "Time is up. Your saved answers were submitted automatically."
                    : "Time is up for level " + a.getLevel() + ". Your saved answers were submitted. You can continue to level " + next + ".";
        } else {
            message = next == null ? "Your assessment has been submitted. Thank you."
                    : "Level " + a.getLevel() + " submitted. Continue to level " + next + " when you're ready.";
        }
        List<AssessmentQuestion> questions = questionsFor(a.getAssessment(), a.getLevel());
        return new CandidateAssessmentResponse(true, "submitted",
                invite.getJob().getTitle(), invite.getCandidateName(), invite.getLevels(), invite.isCombined(),
                a.getLevel(), next, levelStatuses(invite, attempts), a.getId().toString(),
                questions.size(), questions.stream().mapToInt(AssessmentQuestion::getPoints).sum(),
                a.getStartedAt(), a.getDeadlineAt(), a.getDeadlineAt(), Instant.now(), 0,
                a.getSubmittedAt(), a.isAutoSubmitted(), null, message, null, savedAnswers(a));
    }

    private static List<CandidateAssessmentResponse.LevelStatus> levelStatuses(Invite invite, List<AssessmentAttempt> attempts) {
        List<CandidateAssessmentResponse.LevelStatus> out = new ArrayList<>();
        for (String level : invite.getLevels()) {
            AssessmentAttempt a = attemptFor(attempts, level);
            out.add(a == null ? new CandidateAssessmentResponse.LevelStatus(level, "NOT_STARTED", null, null)
                    : new CandidateAssessmentResponse.LevelStatus(level, a.getStatus().name(), a.isAutoSubmitted(), a.getSubmittedAt()));
        }
        return out;
    }

    private List<CandidateAnswerView> savedAnswers(AssessmentAttempt a) {
        Map<UUID, Long> runs = new HashMap<>();
        for (Object[] row : codeRunRepository.countRunsByQuestion(a.getId())) runs.put((UUID) row[0], (Long) row[1]);
        return answerRepository.findByAttemptId(a.getId()).stream().map(ans -> {
            String qid = ans.getQuestion().getId().toString();
            return switch (ans.getQuestion().getType()) {
                case MCQ, MSQ -> new CandidateAnswerView(qid, ans.getSelectedIndices(), null, null, null, null);
                case CODE_WRITE, CODE_DEBUG -> new CandidateAnswerView(qid, null, ans.getLanguage(), ans.getCode(), null,
                        runs.getOrDefault(ans.getQuestion().getId(), 0L).intValue());
                case CODE_OUTPUT, SCENARIO, LOGIC -> new CandidateAnswerView(qid, null, null, null, ans.getTextAnswer(), null);
            };
        }).toList();
    }

    // ── lookups ─────────────────────────────────────────────────────────

    private Invite resolveInvite(String token) {
        if (token == null || token.isBlank()) throw invalid();
        String hash = FileStorageService.sha256Hex(token.getBytes(StandardCharsets.UTF_8));
        Invite invite = inviteRepository.findByTokenHash(hash).orElseThrow(CandidateAssessmentServiceImpl::invalid);
        if (!"ASSESSMENT".equals(invite.getKind()) || invite.getAssessment() == null) throw invalid();
        return invite;
    }

    /** The question must belong to this attempt's level of its version — a
     *  candidate can't answer another level's or version's question. */
    private AssessmentQuestion questionOf(AssessmentAttempt a, String questionId) {
        UUID id;
        try {
            id = UUID.fromString(questionId == null ? "" : questionId.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown question.");
        }
        return questionsFor(a.getAssessment(), a.getLevel()).stream().filter(q -> q.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "That question isn't part of this assessment."));
    }

    private List<AssessmentQuestion> questionsFor(Assessment assessment, String level) {
        return questionRepository.findByAssessmentIdOrderByLevelAscSeqAsc(assessment.getId()).stream()
                .filter(q -> level.equals(q.getLevel()))
                .toList();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID, "link_invalid");
    }

    private static ApiException used() {
        return new ApiException(HttpStatus.BAD_REQUEST, USED, "already_submitted");
    }

    private static ApiException notStarted() {
        return new ApiException(HttpStatus.BAD_REQUEST, "This assessment hasn't been started yet. Open the link first.", "not_started");
    }

    private static String runnerMessage(RunStatus status) {
        return switch (status) {
            case RUNNER_BUSY -> "The code runner is busy. Try again in a moment; your code was saved.";
            case RUNNER_UNAVAILABLE -> "The code runner is unavailable right now. Your code was saved; you can still submit.";
            case UNSUPPORTED_LANGUAGE -> "That language isn't available in the code runner.";
            case OK -> "OK";
        };
    }
}

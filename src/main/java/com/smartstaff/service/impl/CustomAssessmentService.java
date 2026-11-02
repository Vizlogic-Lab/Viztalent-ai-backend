package com.smartstaff.service.impl;

import com.smartstaff.dto.request.CustomAssessmentCreateRequest;
import com.smartstaff.dto.request.CustomQuestionRequest;
import com.smartstaff.dto.request.MarkingSchemeRequest;
import com.smartstaff.dto.request.SectionGenerateRequest;
import com.smartstaff.dto.response.CustomAssessmentResponse;
import com.smartstaff.dto.response.CustomAssessmentSummary;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AssessmentAttemptRepository;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.repository.AssessmentRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.service.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/** Hand-authored assessments: a fixed 50-question paper (25 MCQ, 15 ONE_WORD,
 *  5 SCENARIO, 5 PROJECT) whose sections the author fills by hand or with
 *  "Generate by AI", marked by a {@link MarkingScheme}.
 *
 *  Lifecycle DRAFT -> PUBLISHED -> ARCHIVED. Publishing needs every section full
 *  and the question marks adding up to the scheme's target; it then makes this
 *  version the job's current one, so the existing invite links (token + TTL,
 *  InviteService) send candidates to it, at level L1. Questions are locked as
 *  soon as any candidate has started an attempt on the version. Edits to a
 *  PUBLISHED, unlocked version must leave it publishable.
 *
 *  Stored as an ordinary {@link Assessment} (source CUSTOM, status READY) so the
 *  candidate page, grading, results and reports work unchanged. */
@Service
public class CustomAssessmentService {

    public static final String SOURCE = "CUSTOM";
    public static final String LEVEL = "L1";
    /** Section order on the paper. */
    static final List<QuestionType> SECTIONS =
            List.of(QuestionType.MCQ, QuestionType.ONE_WORD, QuestionType.SCENARIO, QuestionType.PROJECT);

    private final AssessmentRepository assessments;
    private final AssessmentQuestionRepository questions;
    private final AssessmentAttemptRepository attempts;
    private final JobRepository jobs;
    private final SettingsService settings;
    private final SectionDrafter drafter;
    private final ReviewRouter reviewRouter;
    private final ActivityRecorder activity;
    private final TransactionTemplate tx;

    public CustomAssessmentService(AssessmentRepository assessments, AssessmentQuestionRepository questions,
                                   AssessmentAttemptRepository attempts, JobRepository jobs, SettingsService settings,
                                   SectionDrafter drafter, ReviewRouter reviewRouter, ActivityRecorder activity,
                                   PlatformTransactionManager txManager) {
        this.assessments = assessments;
        this.questions = questions;
        this.attempts = attempts;
        this.jobs = jobs;
        this.settings = settings;
        this.drafter = drafter;
        this.reviewRouter = reviewRouter;
        this.activity = activity;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── reads ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CustomAssessmentSummary> list(UUID jobId) {
        return assessments.findByJobIdAndSourceOrderByVersionDesc(jobId, SOURCE).stream()
                .map(a -> {
                    List<AssessmentQuestion> qs = questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId());
                    return new CustomAssessmentSummary(a.getId(), a.getTitle(), a.getLifecycle().name(), a.getVersion(),
                            a.isCurrent(), qs.size(), totalMarks(qs), attempts.existsByAssessmentId(a.getId()),
                            a.getUpdatedAt() == null ? a.getCreatedAt() : a.getUpdatedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomAssessmentResponse get(UUID jobId, UUID id) {
        return view(load(jobId, id));
    }

    // ── draft and marking scheme ────────────────────────────────────────

    @Transactional
    public CustomAssessmentResponse create(UUID jobId, CustomAssessmentCreateRequest req, User admin) {
        Job job = jobs.lockById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found.", "job_not_found"));
        Assessment a = new Assessment();
        a.setJob(job);
        a.setVersion(assessments.maxVersion(jobId) + 1);
        a.setSource(SOURCE);
        a.setStatus(AssessmentStatus.READY);   // nothing to generate in the background
        a.setLifecycle(AssessmentLifecycle.DRAFT);
        a.setCurrent(false);
        a.setTitle(req.title().strip());
        a.setMarkingScheme(req.marking_scheme() == null ? MarkingScheme.DEFAULTS : scheme(req.marking_scheme()));
        a.setProgress(GenerationProgress.start(0));
        a.setUpdatedAt(Instant.now());
        a.setBlueprint(new LinkedHashMap<>(Map.of(LEVEL, blueprint(a, List.of()))));
        assessments.save(a);
        activity.record(jobId, ActivityRecorder.ASSESSMENT,
                "Custom assessment \"" + a.getTitle() + "\" (v" + a.getVersion() + ") drafted by " + who(admin), a.getId());
        return view(a);
    }

    /** New default marks per section; every question takes its section's new default. */
    @Transactional
    public CustomAssessmentResponse updateScheme(UUID jobId, UUID id, MarkingSchemeRequest req) {
        Assessment a = loadEditable(jobId, id);
        MarkingScheme scheme = scheme(req);
        a.setMarkingScheme(scheme);
        List<AssessmentQuestion> qs = questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId());
        qs.forEach(q -> q.setPoints(scheme.marksFor(q.getType())));
        return saveChanges(a, qs);
    }

    // ── manual questions ────────────────────────────────────────────────

    @Transactional
    public CustomAssessmentResponse addQuestion(UUID jobId, UUID id, CustomQuestionRequest req) {
        Assessment a = loadEditable(jobId, id);
        List<AssessmentQuestion> qs = new ArrayList<>(questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId()));
        requireRoom(a, qs, req.type(), 1);
        AssessmentQuestion q = newQuestion(a, req.type());
        q.setOrigin("MANUAL");
        apply(q, req, a.getMarkingScheme());
        qs.add(q);
        return saveChanges(a, qs);
    }

    @Transactional
    public CustomAssessmentResponse updateQuestion(UUID jobId, UUID id, UUID questionId, CustomQuestionRequest req) {
        Assessment a = loadEditable(jobId, id);
        List<AssessmentQuestion> qs = questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId());
        AssessmentQuestion q = find(qs, questionId);
        if (q.getType() != req.type()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A question's type can't change; delete it and add a " + req.type() + " question instead.", "type_change");
        }
        clearAnswerKey(q);
        apply(q, req, a.getMarkingScheme());
        return saveChanges(a, qs);
    }

    @Transactional
    public CustomAssessmentResponse deleteQuestion(UUID jobId, UUID id, UUID questionId) {
        Assessment a = loadEditable(jobId, id);
        List<AssessmentQuestion> qs = new ArrayList<>(questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId()));
        AssessmentQuestion q = find(qs, questionId);
        qs.remove(q);
        questions.delete(q);
        return saveChanges(a, qs);
    }

    // ── AI sections ─────────────────────────────────────────────────────

    private record Snapshot(Job job, int need, int marks, List<String> existing) {}

    /** Fills a section with AI questions, in batches of {@link SectionDrafter#batchSize}.
     *  Gemini runs outside any transaction; the lock and section room are checked again
     *  before saving, since a candidate may have started meanwhile. */
    public CustomAssessmentResponse generateSection(UUID jobId, UUID id, SectionGenerateRequest req) {
        QuestionType type = req.type();
        if (!SECTIONS.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Sections are MCQ, ONE_WORD, SCENARIO and PROJECT.", "unknown_section");
        }
        String key = settings.getGeminiApiKeyOrNull();
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "No Gemini API key is set. Add one in Settings → AI Provider.", "no_ai_key");
        }
        Snapshot snap = tx.execute(s -> {
            Assessment a = loadEditable(jobId, id);
            List<AssessmentQuestion> qs = questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId());
            int room = a.getMarkingScheme().required(type) - count(qs, type);
            if (room <= 0) throw sectionFull(type);
            Job job = jobs.findById(jobId).orElseThrow();
            Job copy = new Job();
            copy.setId(job.getId());
            copy.setTitle(job.getTitle());
            copy.setJdText(job.getJdText());
            return new Snapshot(copy, req.count() == null ? room : Math.min(req.count(), room),
                    a.getMarkingScheme().marksFor(type),
                    qs.stream().filter(q -> q.getType() == type).map(AssessmentQuestion::getPrompt).toList());
        });

        Difficulty difficulty = req.difficulty() == null ? Difficulty.MEDIUM
                : Difficulty.valueOf(req.difficulty().trim().toUpperCase(Locale.ROOT));
        List<AssessmentQuestion> drafted = new ArrayList<>();
        List<String> seen = new ArrayList<>(snap.existing());
        while (drafted.size() < snap.need()) {
            int ask = Math.min(SectionDrafter.batchSize(type), snap.need() - drafted.size());
            List<AssessmentQuestion> batch = drafter.draft(key, new SectionDrafter.Request(snap.job(), type, ask,
                    snap.marks(), difficulty, req.topic_hint(), List.copyOf(seen)));
            drafted.addAll(batch);
            batch.forEach(q -> seen.add(q.getPrompt()));
            if (batch.size() < ask) break;   // the AI ran short; keep what it gave rather than loop
        }

        return tx.execute(s -> {
            Assessment a = loadEditable(jobId, id);
            List<AssessmentQuestion> qs = new ArrayList<>(questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId()));
            int room = a.getMarkingScheme().required(type) - count(qs, type);
            if (room <= 0) throw sectionFull(type);
            for (AssessmentQuestion d : drafted.subList(0, Math.min(room, drafted.size()))) {
                d.setAssessment(a);
                d.setLevel(LEVEL);
                d.setPoints(a.getMarkingScheme().marksFor(type));
                qs.add(d);
            }
            activity.record(jobId, ActivityRecorder.ASSESSMENT, "AI wrote " + Math.min(room, drafted.size()) + " "
                    + type + " question(s) for custom assessment \"" + a.getTitle() + "\"", a.getId());
            return saveChanges(a, qs);
        });
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    /** DRAFT -> PUBLISHED: becomes the job's current assessment, so invite links use it. */
    @Transactional
    public CustomAssessmentResponse publish(UUID jobId, UUID id, User admin) {
        Assessment a = load(jobId, id);
        if (a.getLifecycle() != AssessmentLifecycle.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a draft can be published; this one is "
                    + a.getLifecycle().name().toLowerCase(Locale.ROOT) + ".", "invalid_state");
        }
        List<AssessmentQuestion> qs = questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId());
        List<String> problems = problems(a, qs);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not ready to publish: " + String.join(" ", problems), "not_publishable");
        }
        jobs.lockById(jobId);
        assessments.clearCurrent(jobId);   // clears the persistence context
        Assessment fresh = assessments.findById(id).orElseThrow();
        fresh.setLifecycle(AssessmentLifecycle.PUBLISHED);
        fresh.setCurrent(true);
        fresh.setGeneratedAt(Instant.now());
        fresh.setUpdatedAt(Instant.now());
        assessments.save(fresh);
        reviewRouter.questionSetReady(fresh);   // SME approval first, if the job requires it
        activity.record(jobId, ActivityRecorder.ASSESSMENT,
                "Custom assessment \"" + fresh.getTitle() + "\" published by " + who(admin), fresh.getId());
        return view(fresh);
    }

    /** PUBLISHED (or DRAFT) -> ARCHIVED: no new invites; existing links and results keep working. */
    @Transactional
    public CustomAssessmentResponse archive(UUID jobId, UUID id, User admin) {
        Assessment a = load(jobId, id);
        if (a.getLifecycle() == AssessmentLifecycle.ARCHIVED) {
            throw new ApiException(HttpStatus.CONFLICT, "This assessment is already archived.", "invalid_state");
        }
        a.setLifecycle(AssessmentLifecycle.ARCHIVED);
        a.setCurrent(false);
        a.setUpdatedAt(Instant.now());
        assessments.save(a);
        activity.record(jobId, ActivityRecorder.ASSESSMENT,
                "Custom assessment \"" + a.getTitle() + "\" archived by " + who(admin), a.getId());
        return view(a);
    }

    // ── rules ───────────────────────────────────────────────────────────

    /** Everything that stops publishing; empty when publishable. */
    static List<String> problems(Assessment a, List<AssessmentQuestion> qs) {
        MarkingScheme scheme = a.getMarkingScheme();
        List<String> out = new ArrayList<>();
        for (QuestionType type : SECTIONS) {
            int have = count(qs, type), need = scheme.required(type);
            if (have != need) out.add(type + " has " + have + " of " + need + " questions.");
        }
        for (AssessmentQuestion q : qs) {
            QuestionValidator.Result r = QuestionValidator.checkWritten(q);
            if (!r.ok()) out.add("Question " + (q.getSeq() + 1) + " (" + q.getType() + "): " + r.reason());
        }
        int total = totalMarks(qs);
        if (total != scheme.target_total()) {
            out.add("Marks add up to " + total + ", not the target " + scheme.target_total() + ".");
        }
        return out;
    }

    static MarkingScheme scheme(MarkingSchemeRequest r) {
        return new MarkingScheme(MarkingScheme.sections(r.mcq_marks(), r.one_word_marks(), r.scenario_marks(),
                r.project_marks()), r.target_total());
    }

    static int timeEstimate(QuestionType type) {
        return switch (type) {
            case MCQ -> 60;
            case ONE_WORD -> 45;
            case SCENARIO -> 300;
            case PROJECT -> 900;
            default -> 120;
        };
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Assessment load(UUID jobId, UUID id) {
        Assessment a = assessments.findById(id)
                .filter(x -> SOURCE.equals(x.getSource()) && x.getJob().getId().equals(jobId) && x.getCandidateId() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Custom assessment not found.", "assessment_not_found"));
        if (a.getMarkingScheme() == null) a.setMarkingScheme(MarkingScheme.DEFAULTS);
        return a;
    }

    /** Archived versions and versions a candidate has started can't change. */
    private Assessment loadEditable(UUID jobId, UUID id) {
        Assessment a = load(jobId, id);
        if (a.getLifecycle() == AssessmentLifecycle.ARCHIVED) {
            throw new ApiException(HttpStatus.CONFLICT, "This assessment is archived and can't be edited.", "invalid_state");
        }
        if (attempts.existsByAssessmentId(a.getId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A candidate has already started this assessment, so its questions are locked. "
                            + "Create a new version to make changes.", "assessment_locked");
        }
        return a;
    }

    /** Renumbers by section, rebuilds the blueprint and saves. A published version must stay publishable. */
    private CustomAssessmentResponse saveChanges(Assessment a, List<AssessmentQuestion> qs) {
        List<AssessmentQuestion> ordered = new ArrayList<>(qs);
        ordered.sort(Comparator.comparingInt((AssessmentQuestion q) -> SECTIONS.indexOf(q.getType()))
                .thenComparingInt(AssessmentQuestion::getSeq));
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).setSeq(i);
        if (a.getLifecycle() == AssessmentLifecycle.PUBLISHED) {
            List<String> problems = problems(a, ordered);
            if (!problems.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "This assessment is published, and that change would make it "
                        + "invalid: " + String.join(" ", problems), "would_unpublish");
            }
        }
        a.setBlueprint(new LinkedHashMap<>(Map.of(LEVEL, blueprint(a, ordered))));
        a.setUpdatedAt(Instant.now());
        assessments.save(a);
        questions.saveAll(ordered);
        return view(a, ordered);
    }

    private static void requireRoom(Assessment a, List<AssessmentQuestion> qs, QuestionType type, int adding) {
        if (!SECTIONS.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Sections are MCQ, ONE_WORD, SCENARIO and PROJECT.", "unknown_section");
        }
        if (count(qs, type) + adding > a.getMarkingScheme().required(type)) throw sectionFull(type);
    }

    private static ApiException sectionFull(QuestionType type) {
        return new ApiException(HttpStatus.CONFLICT, "The " + type + " section is already full.", "section_full");
    }

    private static AssessmentQuestion newQuestion(Assessment a, QuestionType type) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setAssessment(a);
        q.setLevel(LEVEL);
        q.setSeq(Integer.MAX_VALUE);   // goes to the end of its section on renumbering
        q.setType(type);
        q.setTimeEstimateSec(timeEstimate(type));
        q.setDifficulty(Difficulty.MEDIUM.label());
        return q;
    }

    private static void clearAnswerKey(AssessmentQuestion q) {
        q.setOptions(new ArrayList<>());
        q.setCorrectIndices(new ArrayList<>());
        q.setModelAnswer(null);
        q.setAcceptedAnswers(new ArrayList<>());
        q.setKeyPoints(new ArrayList<>());
        q.setRubric(new ArrayList<>());
    }

    /** Copies the request onto the question and runs the same content checks as AI questions. */
    private static void apply(AssessmentQuestion q, CustomQuestionRequest r, MarkingScheme scheme) {
        q.setPrompt(r.prompt().strip());
        q.setPoints(r.marks() == null ? scheme.marksFor(r.type()) : r.marks());
        q.setSkill(r.skill() == null || r.skill().isBlank() ? null : r.skill().strip());
        if (r.difficulty() != null) q.setDifficulty(r.difficulty().toLowerCase(Locale.ROOT));
        switch (r.type()) {
            case MCQ -> {
                q.setOptions(r.options().stream().map(String::strip).toList());
                q.setCorrectIndices(List.of(r.correct_index()));
            }
            case ONE_WORD -> {
                q.setModelAnswer(r.answer().strip());
                q.setAcceptedAnswers(r.accepted_answers() == null ? new ArrayList<>()
                        : new ArrayList<>(r.accepted_answers().stream().map(String::strip).toList()));
            }
            case SCENARIO -> {
                q.setModelAnswer(r.model_answer().strip());
                q.setKeyPoints(r.key_points().stream().map(String::strip).toList());
            }
            case PROJECT -> {
                q.setModelAnswer(r.model_answer() == null ? null : r.model_answer().strip());
                q.setRubric(r.rubric().stream()
                        .map(c -> new RubricCriterion(c.criterion().strip(), c.weight(), c.description().strip())).toList());
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported question type.", "unknown_section");
        }
        QuestionValidator.Result check = QuestionValidator.checkWritten(q);
        if (!check.ok()) throw new ApiException(HttpStatus.BAD_REQUEST, check.reason(), "invalid_question");
        q.setValidated(true);
        q.setValidationLog(check.log());
    }

    private static AssessmentQuestion find(List<AssessmentQuestion> qs, UUID questionId) {
        return qs.stream().filter(q -> q.getId().equals(questionId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Question not found.", "question_not_found"));
    }

    private static int count(List<AssessmentQuestion> qs, QuestionType type) {
        return (int) qs.stream().filter(q -> q.getType() == type).count();
    }

    private static int totalMarks(List<AssessmentQuestion> qs) {
        return qs.stream().mapToInt(AssessmentQuestion::getPoints).sum();
    }

    private static Blueprint blueprint(Assessment a, List<AssessmentQuestion> qs) {
        List<Blueprint.Slot> slots = qs.stream().map(q -> new Blueprint.Slot(q.getSeq(), q.getType(), q.getCompetency(),
                q.getDimension(), q.getPoints(), Difficulty.MEDIUM, q.getSkill(),
                q.getTimeEstimateSec() == null ? timeEstimate(q.getType()) : q.getTimeEstimateSec(), false)).toList();
        int seconds = slots.stream().mapToInt(Blueprint.Slot::timeEstimateSec).sum();
        return new Blueprint(LEVEL, null, false, List.of(), totalMarks(qs), (seconds + 59) / 60, slots);
    }

    private CustomAssessmentResponse view(Assessment a) {
        return view(a, questions.findByAssessmentIdOrderByLevelAscSeqAsc(a.getId()));
    }

    private CustomAssessmentResponse view(Assessment a, List<AssessmentQuestion> qs) {
        MarkingScheme scheme = a.getMarkingScheme();
        List<CustomAssessmentResponse.Section> sections = SECTIONS.stream()
                .map(t -> new CustomAssessmentResponse.Section(t.name(), scheme.required(t), count(qs, t), scheme.marksFor(t),
                        qs.stream().filter(q -> q.getType() == t).mapToInt(AssessmentQuestion::getPoints).sum()))
                .toList();
        List<String> problems = problems(a, qs);
        boolean locked = a.getId() != null && attempts.existsByAssessmentId(a.getId());
        List<CustomAssessmentResponse.Question> views = qs.stream().map(q -> new CustomAssessmentResponse.Question(
                q.getId(), q.getSeq(), q.getType().name(), q.getPrompt(), q.getOptions(),
                q.getCorrectIndices().isEmpty() ? null : q.getCorrectIndices().get(0),
                q.getType() == QuestionType.ONE_WORD ? q.getModelAnswer() : null, q.getAcceptedAnswers(),
                q.getType() == QuestionType.ONE_WORD ? null : q.getModelAnswer(), q.getKeyPoints(), q.getRubric(),
                q.getPoints(), q.getOrigin(), q.getSkill(), q.getDifficulty())).toList();
        return new CustomAssessmentResponse(a.getId(), a.getJob().getId(), a.getTitle(), a.getLifecycle().name(),
                a.getVersion(), a.isCurrent(), totalMarks(qs), scheme.target_total(), sections, locked,
                a.getLifecycle() == AssessmentLifecycle.DRAFT && problems.isEmpty(), problems, views,
                a.getCreatedAt(), a.getUpdatedAt());
    }

    private static String who(User admin) {
        return admin == null ? "an admin" : admin.getEmail();
    }
}

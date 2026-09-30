package com.smartstaff.service.impl;

import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.AssessmentMapper;
import com.smartstaff.repository.*;
import com.smartstaff.service.AssessmentQueuedEvent;
import com.smartstaff.service.AssessmentService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.BlueprintFactory;
import com.smartstaff.util.RoleProfileRules;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Assessment generation requests, status and answer keys.
 *
 *  generate() only validates and queues a new version; AssessmentGenerationJob
 *  builds it in the background after commit. The version becomes current
 *  when it is READY. */
@Service
public class AssessmentServiceImpl implements AssessmentService {

    private static final List<String> LEVELS = BlueprintFactory.LEVELS;
    private static final Set<String> VALID_SOURCES = Set.of("AI", "MIX", "CUSTOM");
    /** An in-progress generation older than this is treated as dead. */
    private static final Duration STALE_GENERATION = Duration.ofMinutes(30);

    private final JobRepository jobRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionBankItemRepository questionBankItemRepository;
    private final JobRoleProfileRepository roleProfileRepository;
    private final SettingsService settingsService;
    private final BlueprintFactory blueprintFactory;
    private final AssessmentMapper assessmentMapper;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public AssessmentServiceImpl(JobRepository jobRepository,
                                  AssessmentRepository assessmentRepository,
                                  AssessmentQuestionRepository assessmentQuestionRepository,
                                  QuestionBankItemRepository questionBankItemRepository,
                                  JobRoleProfileRepository roleProfileRepository,
                                  SettingsService settingsService,
                                  BlueprintFactory blueprintFactory,
                                  AssessmentMapper assessmentMapper,
                                  ApplicationEventPublisher events,
                                  PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.assessmentRepository = assessmentRepository;
        this.assessmentQuestionRepository = assessmentQuestionRepository;
        this.questionBankItemRepository = questionBankItemRepository;
        this.roleProfileRepository = roleProfileRepository;
        this.settingsService = settingsService;
        this.blueprintFactory = blueprintFactory;
        this.assessmentMapper = assessmentMapper;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public AssessmentSubmissionsResponse submissions(UUID jobId) {
        return AssessmentSubmissionsResponse.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public AssessmentStatusResponse status(UUID jobId) {
        Optional<Job> job = jobRepository.findById(jobId);
        if (job.isEmpty()) {
            return new AssessmentStatusResponse(true, false, null, 0, 0, null, null, false, false, null,
                    null, null, null, null, null, null, null, null, null);
        }
        Optional<Assessment> latest = assessmentRepository.findTopByJobIdOrderByVersionDesc(jobId);
        Optional<Assessment> current = assessmentRepository.findByJobIdAndCurrentTrue(jobId);
        if (latest.isEmpty()) {
            return new AssessmentStatusResponse(true, true, job.get().getTitle(), 0, 0, null, null, false, false, null,
                    null, null, null, null, null, null, null, null, null);
        }

        Assessment a = latest.get();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String level : a.getBlueprint().keySet()) {
            int n = (int) assessmentQuestionRepository.countByAssessmentIdAndLevel(a.getId(), level);
            counts.put(level, n);
            total += n;
        }
        GenerationProgress p = a.getProgress();
        return new AssessmentStatusResponse(
                true,
                true,
                job.get().getTitle(),
                total,
                0,
                null,
                current.map(c -> assessmentUrl(job.get(), "L1")).orElse(null),
                a.getStatus() == AssessmentStatus.READY,
                a.getStatus().inProgress(),
                a.getError(),
                a.getId().toString(),
                a.getVersion(),
                a.getStatus().name(),
                current.map(Assessment::getVersion).orElse(null),
                counts,
                p.slotsTotal(),
                p.slotsDone(),
                p.unfilled() == null ? List.of() : p.unfilled(),
                p.errors() == null ? List.of() : p.errors());
    }

    @Override
    public AssessmentGenerateResponse generate(AssessmentGenerateRequest req, User admin) {
        UUID jobId = parseSessionId(req.session_id());
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));

        String source = req.question_source() == null ? "" : req.question_source().trim().toUpperCase(Locale.ROOT);
        if (!VALID_SOURCES.contains(source)) {
            return AssessmentGenerateResponse.error(
                    "Unknown question source: " + req.question_source() + " (expected ai, mix, or custom).");
        }
        List<String> levels = req.levels() == null || req.levels().isEmpty()
                ? LEVELS
                : req.levels().stream().map(l -> l.trim().toUpperCase(Locale.ROOT)).distinct().sorted().toList();
        if (!LEVELS.containsAll(levels)) {
            return AssessmentGenerateResponse.error("Levels must be L1, L2 and/or L3.");
        }

        boolean geminiConfigured = settingsService.getGeminiApiKeyOrNull() != null;
        if (source.equals("AI") && !geminiConfigured) {
            return AssessmentGenerateResponse.error(
                    "No Gemini API key configured. Add one in Settings, or choose a different question source.");
        }
        if (!geminiConfigured && questionBankItemRepository.count() == 0) {
            return AssessmentGenerateResponse.error(
                    "No questions available — upload a question bank or configure a Gemini key.");
        }

        JobRoleProfile profile = roleProfileRepository.findById(jobId).orElseGet(() -> RoleProfileRules.fallback(job));
        Map<String, Blueprint> blueprints = new LinkedHashMap<>();
        for (String level : levels) blueprints.put(level, blueprintFactory.create(level, profile));
        int slots = blueprints.values().stream().mapToInt(b -> b.slots().size()).sum();

        Assessment queued = transaction.execute(status -> {
            Job locked = jobRepository.lockById(jobId).orElseThrow();
            assessmentRepository.findTopByJobIdOrderByVersionDesc(jobId)
                    .filter(a -> a.getStatus().inProgress())
                    .filter(a -> a.getCreatedAt().isAfter(Instant.now().minus(STALE_GENERATION)))
                    .ifPresent(a -> {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "A question set is already being generated for this job.", "generation_in_progress");
                    });
            Assessment a = new Assessment();
            a.setJob(locked);
            a.setVersion(assessmentRepository.maxVersion(jobId) + 1);
            a.setSource(source);
            a.setBlueprint(blueprints);
            a.setStatus(AssessmentStatus.QUEUED);
            a.setProgress(GenerationProgress.start(slots));
            a.setCurrent(false);
            assessmentRepository.save(a);
            events.publishEvent(new AssessmentQueuedEvent(a.getId()));
            return a;
        });

        Map<String, String> urls = new LinkedHashMap<>();
        for (String level : levels) urls.put(level, assessmentUrl(job, level));
        return new AssessmentGenerateResponse("queued",
                "Generating " + slots + " questions — this can take a few minutes.",
                urls.get(levels.get(0)), urls, null, queued.getId().toString(), queued.getVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public AnswerKeyResponse answerKey(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
        Assessment assessment = assessmentRepository.findByJobIdAndCurrentTrue(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No assessment has been generated for this job yet."));

        List<AssessmentQuestion> all = assessmentQuestionRepository.findByAssessmentIdOrderByLevelAscSeqAsc(assessment.getId());
        Map<String, List<AssessmentQuestion>> grouped = new LinkedHashMap<>();
        for (String level : LEVELS) grouped.put(level, new ArrayList<>());
        for (AssessmentQuestion q : all) {
            grouped.computeIfAbsent(q.getLevel(), k -> new ArrayList<>()).add(q);
        }

        List<AnswerKeyLevelResponse> levels = new ArrayList<>();
        for (String level : LEVELS) {
            List<AssessmentQuestionResponse> questions = grouped.getOrDefault(level, List.of()).stream()
                    .map(assessmentMapper::toQuestionResponse).toList();
            levels.add(new AnswerKeyLevelResponse(level, questions.size(), questions));
        }

        return new AnswerKeyResponse(true, job.getTitle(), levels);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String assessmentUrl(Job job, String level) {
        String jd = job.jdNumberDisplay();
        if (jd == null) return null;
        return settingsService.getPublicBaseUrl() + "/assessment/" + jd + "?level=" + level;
    }

    private static UUID parseSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No job description found for this session — upload a JD first.");
        }
    }
}

package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.mapper.AssessmentMapper;
import com.smartstaff.repository.*;
import com.smartstaff.service.AssessmentService;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.BlueprintFactory;
import com.smartstaff.util.RoleProfileRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/** Assessment generation (AI / Mix / Custom bank), status, and answer keys.
 *
 *  Each generation writes a new assessment version and moves is_current to
 *  it; older versions stay for grading. Questions are built (including any
 *  Gemini calls) before the database transaction opens. Generation is still
 *  synchronous and MCQ/MSQ-only here; the async practical pipeline replaces
 *  it in F4. */
@Service
public class AssessmentServiceImpl implements AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentServiceImpl.class);

    private static final List<String> LEVELS = BlueprintFactory.LEVELS;
    private static final Map<String, String> DIFFICULTY_BY_LEVEL = Map.of(
            "L1", "easy", "L2", "medium", "L3", "hard");
    private static final Set<String> VALID_SOURCES = Set.of("AI", "MIX", "CUSTOM");
    private static final int QUESTIONS_PER_LEVEL = 6;
    private static final int LEGACY_POINTS = 5;

    private final JobRepository jobRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionBankItemRepository questionBankItemRepository;
    private final JobRoleProfileRepository roleProfileRepository;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final GeminiClient geminiClient;
    private final BlueprintFactory blueprintFactory;
    private final AssessmentMapper assessmentMapper;
    private final TransactionTemplate transaction;

    public AssessmentServiceImpl(JobRepository jobRepository,
                                  AssessmentRepository assessmentRepository,
                                  AssessmentQuestionRepository assessmentQuestionRepository,
                                  QuestionBankItemRepository questionBankItemRepository,
                                  JobRoleProfileRepository roleProfileRepository,
                                  SettingsService settingsService,
                                  ObjectMapper objectMapper,
                                  GeminiClient geminiClient,
                                  BlueprintFactory blueprintFactory,
                                  AssessmentMapper assessmentMapper,
                                  PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.assessmentRepository = assessmentRepository;
        this.assessmentQuestionRepository = assessmentQuestionRepository;
        this.questionBankItemRepository = questionBankItemRepository;
        this.roleProfileRepository = roleProfileRepository;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.geminiClient = geminiClient;
        this.blueprintFactory = blueprintFactory;
        this.assessmentMapper = assessmentMapper;
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
        Optional<Assessment> assessment = job.isPresent()
                ? assessmentRepository.findByJobIdAndCurrentTrue(jobId)
                : Optional.empty();

        String assessmentUrl = assessment.map(a -> assessmentUrl(job.get(), "L1")).orElse(null);
        int numQuestions = assessment.map(a -> (int) assessmentQuestionRepository.countByAssessmentId(a.getId())).orElse(0);

        return new AssessmentStatusResponse(
                true,
                job.isPresent(),
                job.map(Job::getTitle).orElse(null),
                numQuestions,
                0,
                null,
                assessmentUrl,
                assessment.map(a -> a.getStatus() == AssessmentStatus.READY).orElse(false),
                assessment.map(a -> a.getStatus().inProgress()).orElse(false),
                assessment.map(Assessment::getError).orElse(null)
        );
    }

    @Override
    public AssessmentGenerateResponse generate(AssessmentGenerateRequest req, User admin) {
        UUID jobId = parseSessionId(req.session_id());
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));

        String source = req.question_source() == null ? "" : req.question_source().trim().toUpperCase(Locale.ROOT);
        if (!VALID_SOURCES.contains(source)) {
            return new AssessmentGenerateResponse("error",
                    "Unknown question source: " + req.question_source() + " (expected ai, mix, or custom).",
                    null, null, null);
        }

        boolean geminiConfigured = settingsService.getGeminiApiKeyOrNull() != null;
        if (source.equals("AI") && !geminiConfigured) {
            return new AssessmentGenerateResponse("error",
                    "No Gemini API key configured. Add one in Settings, or choose a different question source.",
                    null, null, null);
        }

        Map<String, List<AssessmentQuestion>> byLevel = new LinkedHashMap<>();
        for (String level : LEVELS) {
            byLevel.put(level, buildQuestionsForLevel(job, level, source));
        }

        int total = byLevel.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return new AssessmentGenerateResponse("error",
                    "No questions available — upload a question bank or configure a Gemini key.",
                    null, null, null);
        }

        JobRoleProfile profile = roleProfileRepository.findById(jobId).orElseGet(() -> RoleProfileRules.fallback(job));
        Map<String, Blueprint> blueprints = blueprintFactory.createAll(profile);

        transaction.executeWithoutResult(status -> saveNewVersion(jobId, source, blueprints, byLevel));

        Map<String, String> urls = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String level : LEVELS) {
            urls.put(level, assessmentUrl(job, level));
            counts.put(level, byLevel.get(level).size());
        }

        return new AssessmentGenerateResponse("success", null, urls.get("L1"), urls, counts);
    }

    /** New version = max + 1, made current. The job row lock serialises two
     *  concurrent generations for the same job. */
    private void saveNewVersion(UUID jobId, String source, Map<String, Blueprint> blueprints,
                                Map<String, List<AssessmentQuestion>> byLevel) {
        Job job = jobRepository.lockById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No job description found for this session — upload a JD first."));
        int version = assessmentRepository.maxVersion(jobId) + 1;
        assessmentRepository.clearCurrent(jobId);

        Assessment assessment = new Assessment();
        assessment.setJob(job);
        assessment.setVersion(version);
        assessment.setSource(source);
        assessment.setBlueprint(blueprints);
        assessment.setStatus(AssessmentStatus.READY);
        assessment.setGeneratedAt(Instant.now());
        assessment.setCurrent(true);
        assessmentRepository.save(assessment);

        for (String level : LEVELS) {
            int seq = 0;
            for (AssessmentQuestion q : byLevel.get(level)) {
                q.setAssessment(assessment);
                q.setSeq(seq++);
                assessmentQuestionRepository.save(q);
            }
        }
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

    // ── question sourcing ───────────────────────────────────────────────

    private List<AssessmentQuestion> buildQuestionsForLevel(Job job, String level, String source) {
        List<AssessmentQuestion> questions = new ArrayList<>();

        if (!source.equals("AI")) {
            int fromBank = source.equals("MIX") ? QUESTIONS_PER_LEVEL / 2 : QUESTIONS_PER_LEVEL;
            questions.addAll(fromBank(level, fromBank));
        }

        int need = QUESTIONS_PER_LEVEL - questions.size();
        // AI generates its full share; MIX/CUSTOM only call out to Gemini to
        // pad what the bank couldn't cover.
        if (need > 0 && settingsService.getGeminiApiKeyOrNull() != null) {
            try {
                questions.addAll(generateWithGemini(job, level, need));
            } catch (Exception e) {
                log.warn("Gemini question generation failed for job {} level {}: {}", job.getId(), level, e.toString());
            }
        }

        return questions;
    }

    private List<AssessmentQuestion> fromBank(String level, int limit) {
        if (limit <= 0) return List.of();
        List<QuestionBankItem> items = new ArrayList<>(questionBankItemRepository.findByLevelOrLevelIsNull(level));
        Collections.shuffle(items);
        List<AssessmentQuestion> out = new ArrayList<>();
        for (QuestionBankItem item : items) {
            if (out.size() >= limit) break;
            QuestionType type = QuestionType.fromBankType(item.getType());
            AssessmentQuestion q = newQuestion(level, type);
            q.setPrompt(item.getPrompt());
            q.setOptions(new ArrayList<>(item.getOptions()));
            q.setCorrectIndices(new ArrayList<>(item.getCorrectIndices()));
            q.setSkill(item.getSkill());
            q.setDifficulty(item.getDifficulty() != null ? item.getDifficulty() : DIFFICULTY_BY_LEVEL.get(level));
            // Bank coding/scenario items carry no solution, tests or model answer yet (F5).
            q.setValidated(!type.isCode() && type != QuestionType.SCENARIO);
            if (!q.isValidated()) q.setValidationLog("Imported from the question bank without a solution or tests.");
            out.add(q);
        }
        return out;
    }

    private List<AssessmentQuestion> generateWithGemini(Job job, String level, int count) {
        String key = settingsService.getGeminiApiKeyOrNull();
        if (key == null || key.isBlank()) return List.of();

        String difficulty = DIFFICULTY_BY_LEVEL.getOrDefault(level, "medium");
        List<String> skills = job.allSkills();
        String skillsCsv = skills.isEmpty() ? "general software engineering" : String.join(", ", skills);
        String jdExcerpt = truncate(job.getJdText(), 2000);

        String prompt = """
                You are writing a technical screening assessment for the role "%s".
                Relevant skills: %s.
                Job description excerpt:
                %s

                Write exactly %d questions at %s difficulty (assessment level %s). Mix multiple-choice \
                (type "MCQ", exactly one correct answer) and multi-select (type "MSQ", two or more correct \
                answers) questions, each with exactly 4 answer options. Base the questions on the skills and \
                job description above rather than generic trivia.

                Respond with ONLY a JSON array, no markdown fences, no commentary, shaped exactly like this \
                example (values are illustrative, not real answers):
                [{"type":"MCQ","question":"...","options":["...","...","...","..."],"correct_indices":[0],"skill":"...","difficulty":"%s"}]
                """.formatted(job.getTitle(), skillsCsv, jdExcerpt, count, difficulty, level, difficulty);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.7)
        );

        String raw = geminiClient.generateContent(key, body);

        JsonNode arr;
        try {
            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            arr = objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini returned unparseable output", e);
        }

        List<AssessmentQuestion> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode qNode : arr) {
                if (out.size() >= count) break;
                AssessmentQuestion q = questionFromGeminiJson(qNode, level, difficulty);
                if (q != null) out.add(q);
            }
        }
        return out;
    }

    private AssessmentQuestion questionFromGeminiJson(JsonNode node, String level, String fallbackDifficulty) {
        String question = node.path("question").asText(null);
        if (question == null || question.isBlank()) return null;

        List<String> options = new ArrayList<>();
        for (JsonNode o : node.path("options")) options.add(o.asText(""));
        if (options.size() < 2 || new HashSet<>(options).size() != options.size()) return null;

        List<Integer> correct = new ArrayList<>();
        for (JsonNode i : node.path("correct_indices")) correct.add(i.asInt());
        if (correct.isEmpty() || correct.stream().anyMatch(i -> i < 0 || i >= options.size())) return null;

        AssessmentQuestion q = newQuestion(level, correct.size() > 1 ? QuestionType.MSQ : QuestionType.MCQ);
        q.setPrompt(question);
        q.setOptions(options);
        q.setCorrectIndices(correct);
        q.setSkill(node.path("skill").asText(null));
        q.setDifficulty(node.path("difficulty").asText(fallbackDifficulty));
        q.setValidated(true);
        return q;
    }

    private static AssessmentQuestion newQuestion(String level, QuestionType type) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setLevel(level);
        q.setType(type);
        q.setPoints(LEGACY_POINTS);
        q.setTimeEstimateSec(switch (type) {
            case MCQ -> 60;
            case MSQ -> 90;
            case CODE_WRITE -> 600;
            case CODE_DEBUG -> 480;
            case CODE_OUTPUT -> 180;
            case SCENARIO -> 300;
            case LOGIC -> 240;
        });
        return q;
    }

    // ── shared helpers ──────────────────────────────────────────────────

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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}

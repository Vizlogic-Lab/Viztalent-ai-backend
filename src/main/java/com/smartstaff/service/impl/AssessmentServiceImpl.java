package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.Assessment;
import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.QuestionBankItem;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.repository.AssessmentRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.repository.QuestionBankItemRepository;
import com.smartstaff.service.AssessmentService;
import com.smartstaff.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Assessment generation (AI / Mix / Custom bank), status, and answer keys.
 *
 *  Generation runs synchronously inside the POST /api/assessment/generate
 *  call rather than on a background thread — see AssessmentStatusResponse's
 *  javadoc for why that's a safe, documented deviation from the "async, poll
 *  status" flow Candidates.jsx is written to tolerate. */
@Service
public class AssessmentServiceImpl implements AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentServiceImpl.class);

    private static final List<String> LEVELS = List.of("L1", "L2", "L3");
    private static final Map<String, String> DIFFICULTY_BY_LEVEL = Map.of(
            "L1", "easy", "L2", "medium", "L3", "hard");
    private static final Set<String> VALID_SOURCES = Set.of("AI", "MIX", "CUSTOM");
    private static final int QUESTIONS_PER_LEVEL = 6;

    private final JobRepository jobRepository;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentQuestionRepository assessmentQuestionRepository;
    private final QuestionBankItemRepository questionBankItemRepository;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final GeminiClient geminiClient;

    public AssessmentServiceImpl(JobRepository jobRepository,
                                  AssessmentRepository assessmentRepository,
                                  AssessmentQuestionRepository assessmentQuestionRepository,
                                  QuestionBankItemRepository questionBankItemRepository,
                                  SettingsService settingsService,
                                  ObjectMapper objectMapper,
                                  GeminiClient geminiClient) {
        this.jobRepository = jobRepository;
        this.assessmentRepository = assessmentRepository;
        this.assessmentQuestionRepository = assessmentQuestionRepository;
        this.questionBankItemRepository = questionBankItemRepository;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.geminiClient = geminiClient;
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
                ? assessmentRepository.findByJobId(jobId)
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
                assessment.map(Assessment::isReady).orElse(false),
                false,
                assessment.map(Assessment::getError).orElse(null)
        );
    }

    @Override
    @Transactional
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

        // A job has at most one assessment (job_id is UNIQUE) — replace any
        // prior one wholesale rather than trying to diff/merge question sets.
        assessmentRepository.findByJobId(jobId).ifPresent(existing -> {
            assessmentRepository.delete(existing);
            assessmentRepository.flush();
        });

        Assessment assessment = new Assessment();
        assessment.setJob(job);
        assessment.setSource(source);
        assessment.setReady(true);
        assessmentRepository.save(assessment);

        for (String level : LEVELS) {
            for (AssessmentQuestion q : byLevel.get(level)) {
                q.setAssessment(assessment);
                assessmentQuestionRepository.save(q);
            }
        }

        Map<String, String> urls = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String level : LEVELS) {
            urls.put(level, assessmentUrl(job, level));
            counts.put(level, byLevel.get(level).size());
        }

        return new AssessmentGenerateResponse("success", null, urls.get("L1"), urls, counts);
    }

    @Override
    @Transactional(readOnly = true)
    public AnswerKeyResponse answerKey(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
        Assessment assessment = assessmentRepository.findByJobId(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No assessment has been generated for this job yet."));

        List<AssessmentQuestion> all = assessmentQuestionRepository.findByAssessmentIdOrderByLevelAsc(assessment.getId());
        Map<String, List<AssessmentQuestion>> grouped = new LinkedHashMap<>();
        for (String level : LEVELS) grouped.put(level, new ArrayList<>());
        for (AssessmentQuestion q : all) {
            grouped.computeIfAbsent(q.getLevel(), k -> new ArrayList<>()).add(q);
        }

        List<AnswerKeyLevelResponse> levels = new ArrayList<>();
        for (String level : LEVELS) {
            List<AssessmentQuestion> qs = grouped.getOrDefault(level, List.of());
            List<AssessmentQuestionResponse> questions = qs.stream().map(this::toQuestionResponse).toList();
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
        // pad what the bank couldn't cover (CUSTOM's "AI only pads if short"
        // promise in Candidates.jsx).
        if (need > 0 && settingsService.getGeminiApiKeyOrNull() != null) {
            try {
                questions.addAll(generateWithGemini(job, level, need));
            } catch (Exception e) {
                log.warn("Gemini question generation failed for job {} level {}: {}", job.getId(), level, e.toString());
                // Non-fatal — fall through with whatever the bank provided.
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
            AssessmentQuestion q = new AssessmentQuestion();
            q.setLevel(level);
            q.setType(item.getType());
            q.setPrompt(item.getPrompt());
            q.setOptions(new ArrayList<>(item.getOptions()));
            q.setCorrectIndices(new ArrayList<>(item.getCorrectIndices()));
            q.setSkill(item.getSkill());
            q.setDifficulty(item.getDifficulty() != null ? item.getDifficulty() : DIFFICULTY_BY_LEVEL.get(level));
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
        if (options.size() < 2) return null;

        List<Integer> correct = new ArrayList<>();
        for (JsonNode i : node.path("correct_indices")) correct.add(i.asInt());
        if (correct.isEmpty()) return null;

        String type = correct.size() > 1 ? "MSQ" : node.path("type").asText("MCQ").toUpperCase(Locale.ROOT);
        if (!type.equals("MCQ") && !type.equals("MSQ")) type = correct.size() > 1 ? "MSQ" : "MCQ";

        AssessmentQuestion q = new AssessmentQuestion();
        q.setLevel(level);
        q.setType(type);
        q.setPrompt(question);
        q.setOptions(options);
        q.setCorrectIndices(correct);
        q.setSkill(node.path("skill").asText(null));
        q.setDifficulty(node.path("difficulty").asText(fallbackDifficulty));
        return q;
    }

    // ── shared helpers ──────────────────────────────────────────────────

    private AssessmentQuestionResponse toQuestionResponse(AssessmentQuestion q) {
        List<Integer> indices = q.getCorrectIndices();
        Integer correctIndex = "MCQ".equals(q.getType()) && !indices.isEmpty() ? indices.get(0) : null;
        List<Integer> correctIndices = "MSQ".equals(q.getType()) ? indices : null;
        return new AssessmentQuestionResponse(q.getType(), q.getPrompt(), q.getOptions(), correctIndex, correctIndices, q.getSkill(), q.getDifficulty());
    }

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

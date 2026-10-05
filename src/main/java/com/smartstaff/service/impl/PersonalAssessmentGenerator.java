package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.ai.AiFeature;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.repository.AssessmentRepository;
import com.smartstaff.repository.CandidateRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Writes one candidate's personal assessment from the JD + their resume with the
 *  admin-editable prompt ({@link AssessmentPromptService}), in one Gemini call.
 *
 *  The prompt's five sections map onto existing question types, so the candidate
 *  page, grading and review work unchanged:
 *    A  5 MCQs              → MCQ (auto-graded)
 *    B  4 experience        → SCENARIO, graded on its key points
 *    C  2–3 bug fixing      → SCENARIO (competency DEBUGGING), snippet shown as code
 *    D  1–2 DevOps          → SCENARIO
 *    E  1 Agile             → SCENARIO
 *  Everything lives under level L1. Personal assessments are never the job's
 *  current version; invites find them by candidate. */
@Service
public class PersonalAssessmentGenerator {

    private static final Logger log = LoggerFactory.getLogger(PersonalAssessmentGenerator.class);

    public static final String LEVEL = "L1";
    static final int MAX_JD_CHARS = 12_000;
    static final int MAX_RESUME_CHARS = 12_000;

    /** Section → [min, max] question count, per the prompt. */
    static final Map<String, int[]> COUNTS = new LinkedHashMap<>();
    static {
        COUNTS.put("mcqs", new int[]{5, 5});
        COUNTS.put("experience", new int[]{4, 4});
        COUNTS.put("bug_fixing", new int[]{2, 3});
        COUNTS.put("devops", new int[]{1, 2});
        COUNTS.put("agile", new int[]{1, 1});
    }

    private static final String OUTPUT_RULES = """


            OUTPUT FORMAT (system requirement — follow exactly)
            Return the assessment as JSON matching the response schema, one array per section:
            mcqs (Section A), experience (Section B), bug_fixing (Section C), devops (Section D), agile (Section E).
            - MCQs: exactly four options as plain text (no "A." prefixes); correct_option is the letter A, B, C or D.
            - bug_fixing: put the question in "question" and the code (or log/config) in "code_snippet", at most 25 lines.
            - Every non-MCQ question needs a model_answer and 3 to 5 key_points: the specific facts a strong answer
              must state. They are used to grade the candidate's written answer, so make each one checkable.
            - skill: the one JD skill or technology the question tests.
            """;

    private final GeminiClient gemini;
    private final SettingsService settings;
    private final AssessmentPromptService prompts;
    private final ObjectMapper objectMapper;
    private final JobRepository jobs;
    private final AssessmentRepository assessments;
    private final AssessmentQuestionRepository questions;
    private final ReviewRouter reviewRouter;
    private final ActivityRecorder activity;
    private final CandidateRepository candidates;

    public PersonalAssessmentGenerator(GeminiClient gemini, SettingsService settings, AssessmentPromptService prompts,
                                       ObjectMapper objectMapper, JobRepository jobs, AssessmentRepository assessments,
                                       AssessmentQuestionRepository questions, ReviewRouter reviewRouter,
                                       ActivityRecorder activity, CandidateRepository candidates) {
        this.candidates = candidates;
        this.gemini = gemini;
        this.settings = settings;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
        this.jobs = jobs;
        this.assessments = assessments;
        this.questions = questions;
        this.reviewRouter = reviewRouter;
        this.activity = activity;
    }

    /** The id of the candidate's newest READY personal assessment, generating one if
     *  there is none. Runs in its own transaction so a generated assessment is kept
     *  even when the caller then fails (e.g. the question set awaits SME approval). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID readyFor(UUID candidateId) {
        Candidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Candidate not found."));
        return assessments.findFirstByCandidateIdAndStatusOrderByCreatedAtDesc(candidateId, AssessmentStatus.READY)
                .orElseGet(() -> generate(candidate)).getId();
    }

    /** Always writes a new version (HR "regenerate"). Own transaction, as above. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID regenerate(UUID candidateId) {
        Candidate candidate = candidates.findById(candidateId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Candidate not found."));
        return generate(candidate).getId();
    }

    private Assessment generate(Candidate candidate) {
        Job job = candidate.getJob();
        String resume = candidate.getResume() == null ? null : candidate.getResume().getExtractedText();
        String jd = jdFor(job.getTitle(), job.getJdText(), job.getMustHaveSkills(), job.getNiceToHaveSkills(),
                job.getExperienceMinYears(), job.getExperienceMaxYears());
        if (jd == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This job has no description or skills to build an assessment from.", "no_jd_text");
        }
        if (blank(resume)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "We couldn't read " + name(candidate) + "'s resume, so a personal assessment can't be written.", "no_resume_text");
        }
        String key = settings.getGeminiApiKeyOrNull();
        if (blank(key)) {
            throw new ApiException(HttpStatus.CONFLICT, "No Gemini API key is set. Add one in Settings → AI Provider.", "no_ai_key");
        }

        String prompt = AssessmentPromptService.render(prompts.current(),
                cut(jd, MAX_JD_CHARS), cut(resume, MAX_RESUME_CHARS)) + OUTPUT_RULES;

        Parsed parsed = null;
        String problem = null;
        for (int attempt = 1; attempt <= 2 && parsed == null; attempt++) {
            String text = call(key, attempt == 1 ? prompt
                    : prompt + "\nYour previous answer was rejected: " + problem + " Fix that and answer again.");
            try {
                parsed = parse(objectMapper.readTree(text));
            } catch (InvalidOutput e) {
                problem = e.getMessage();
                log.warn("Personal assessment for candidate {} rejected (attempt {}): {}", candidate.getId(), attempt, problem);
            } catch (Exception e) {
                problem = "the reply wasn't valid JSON.";
                log.warn("Personal assessment for candidate {}: unreadable reply (attempt {})", candidate.getId(), attempt);
            }
        }
        if (parsed == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The AI didn't return a complete assessment (" + problem + "). Please try again.", "ai_bad_output");
        }
        return persist(job, candidate, parsed);
    }

    private String call(String key, String prompt) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema(),
                        "temperature", 0.6));
        String raw = gemini.generateContent(key, body, AiFeature.PERSONAL_ASSESSMENT);   // GeminiException carries a clear message
        try {
            return objectMapper.readTree(raw).path("candidates").path(0).path("content").path("parts").path(0)
                    .path("text").asText("");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI service returned an unreadable reply. Please try again.", "ai_bad_output");
        }
    }

    /** Below this, the JD text is too thin to stand alone (e.g. a pasted skill list). */
    static final int MIN_JD_CHARS = 200;

    /** The JD as the prompt sees it: the uploaded text, plus a structured summary
     *  (title, skills, experience) when the text is missing or thin — many jobs are
     *  created from a pasted skill list. Null when there is nothing to go on. */
    static String jdFor(String title, String jdText, List<String> mustHave, List<String> niceToHave,
                        Integer minYears, Integer maxYears) {
        String text = blank(jdText) ? "" : jdText.strip();
        boolean hasSkills = mustHave != null && !mustHave.isEmpty();
        if (text.length() >= MIN_JD_CHARS) return text;
        if (text.isEmpty() && !hasSkills) return null;
        StringBuilder sb = new StringBuilder();
        if (!blank(title)) sb.append("Job title: ").append(title.strip()).append('\n');
        if (hasSkills) sb.append("Required skills: ").append(String.join(", ", mustHave)).append('\n');
        if (niceToHave != null && !niceToHave.isEmpty()) {
            sb.append("Nice-to-have skills: ").append(String.join(", ", niceToHave)).append('\n');
        }
        if (minYears != null || maxYears != null) {
            sb.append("Experience: ")
                    .append(minYears == null ? "up to " + maxYears : maxYears == null ? minYears + "+" : minYears + "–" + maxYears)
                    .append(" years\n");
        }
        if (!text.isEmpty()) sb.append('\n').append(text);
        return sb.toString().strip();
    }

    // ── parsing and checks ───────────────────────────────────────────────

    record Mcq(String question, List<String> options, int correct, String explanation, String skill) {}
    record Open(String section, String question, String snippet, String modelAnswer, List<String> keyPoints, String skill) {}
    record Parsed(List<Mcq> mcqs, List<Open> open) {}

    static final class InvalidOutput extends RuntimeException {
        InvalidOutput(String message) { super(message); }
    }

    /** Validates counts and fields; trims surplus questions, rejects shortfalls. */
    static Parsed parse(JsonNode root) {
        Map<String, List<JsonNode>> sections = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : COUNTS.entrySet()) {
            List<JsonNode> items = new ArrayList<>();
            root.path(e.getKey()).forEach(items::add);
            int min = e.getValue()[0], max = e.getValue()[1];
            if (items.size() < min) {
                throw new InvalidOutput("section " + e.getKey() + " has " + items.size() + " questions, needs at least " + min + ".");
            }
            sections.put(e.getKey(), items.subList(0, Math.min(items.size(), max)));
        }

        Set<String> seen = new HashSet<>();
        List<Mcq> mcqs = new ArrayList<>();
        for (JsonNode n : sections.get("mcqs")) {
            String q = req(n, "question", "an MCQ");
            List<String> options = new ArrayList<>();
            n.path("options").forEach(o -> options.add(o.asText("").strip()));
            if (options.size() != 4 || options.stream().anyMatch(String::isBlank)
                    || new HashSet<>(lower(options)).size() != 4) {
                throw new InvalidOutput("an MCQ doesn't have four distinct options.");
            }
            String letter = n.path("correct_option").asText("").strip().toUpperCase(Locale.ROOT);
            int correct = letter.length() == 1 ? letter.charAt(0) - 'A' : -1;
            if (correct < 0 || correct > 3) throw new InvalidOutput("an MCQ has no valid correct_option (A–D).");
            unique(seen, q);
            mcqs.add(new Mcq(q, options, correct, n.path("explanation").asText(null), n.path("skill").asText(null)));
        }

        List<Open> open = new ArrayList<>();
        for (String section : List.of("experience", "bug_fixing", "devops", "agile")) {
            for (JsonNode n : sections.get(section)) {
                String q = req(n, "question", "a " + section + " question");
                String snippet = n.path("code_snippet").asText("").strip();
                if (section.equals("bug_fixing") && snippet.isEmpty()) {
                    throw new InvalidOutput("a bug-fixing question has no code_snippet.");
                }
                List<String> points = new ArrayList<>();
                n.path("key_points").forEach(p -> { if (!p.asText("").isBlank()) points.add(p.asText().strip()); });
                if (points.size() < 2) throw new InvalidOutput("a " + section + " question has fewer than 2 key_points.");
                unique(seen, q);
                open.add(new Open(section, q, snippet.isEmpty() ? null : snippet,
                        req(n, "model_answer", "a " + section + " question"), points.subList(0, Math.min(points.size(), 5)),
                        n.path("skill").asText(null)));
            }
        }
        return new Parsed(mcqs, open);
    }

    private static String req(JsonNode n, String field, String what) {
        String v = n.path(field).asText("").strip();
        if (v.isEmpty()) throw new InvalidOutput(what + " is missing " + field + ".");
        return v;
    }

    private static void unique(Set<String> seen, String question) {
        if (!seen.add(question.toLowerCase(Locale.ROOT).replaceAll("\\W+", " ").strip())) {
            throw new InvalidOutput("a question is repeated.");
        }
    }

    // ── saving ───────────────────────────────────────────────────────────

    private Assessment persist(Job job, Candidate candidate, Parsed p) {
        jobs.lockById(job.getId());   // versions are unique per job
        Assessment a = new Assessment();
        a.setJob(job);
        a.setCandidateId(candidate.getId());
        a.setVersion(assessments.maxVersion(job.getId()) + 1);
        a.setSource("AI");
        a.setCurrent(false);
        a.setStatus(AssessmentStatus.READY);
        a.setGeneratedAt(Instant.now());
        a.setProgress(GenerationProgress.start(p.mcqs().size() + p.open().size()));

        List<AssessmentQuestion> qs = new ArrayList<>();
        List<Blueprint.Slot> slots = new ArrayList<>();
        int seq = 0;
        for (Mcq m : p.mcqs()) {
            AssessmentQuestion q = base(a, seq, QuestionType.MCQ, 4, 75, m.skill(), "Section A · Multiple choice");
            q.setPrompt(m.question());
            q.setOptions(m.options());
            q.setCorrectIndices(List.of(m.correct()));
            q.setExplanation(m.explanation());
            qs.add(q);
            slots.add(slot(q));
            seq++;
        }
        for (Open o : p.open()) {
            AssessmentQuestion q = switch (o.section()) {
                case "experience" -> base(a, seq, QuestionType.SCENARIO, 10, 300, o.skill(), "Section B · Your experience");
                case "bug_fixing" -> {
                    AssessmentQuestion b = base(a, seq, QuestionType.SCENARIO, 10, 360, o.skill(), "Section C · Find and fix the bug");
                    b.setCompetency(Competency.DEBUGGING);
                    yield b;
                }
                case "devops" -> base(a, seq, QuestionType.SCENARIO, 8, 240, o.skill(), "Section D · DevOps & infrastructure");
                default -> base(a, seq, QuestionType.SCENARIO, 8, 240, o.skill(), "Section E · Agile & process");
            };
            q.setPrompt(o.question());
            if (o.snippet() != null) q.setStarterCode(new LinkedHashMap<>(Map.of("code", o.snippet())));
            q.setModelAnswer(o.modelAnswer());
            q.setKeyPoints(o.keyPoints());
            qs.add(q);
            slots.add(slot(q));
            seq++;
        }

        int total = qs.stream().mapToInt(AssessmentQuestion::getPoints).sum();
        int minutes = (int) Math.ceil(qs.stream().mapToInt(AssessmentQuestion::getTimeEstimateSec).sum() / 60.0);
        a.setBlueprint(new LinkedHashMap<>(Map.of(LEVEL, new Blueprint(LEVEL, JobRoleProfile.RoleFamily.BACKEND, true,
                List.of(), total, minutes, slots))));
        assessments.save(a);
        questions.saveAll(qs);
        reviewRouter.questionSetReady(a);   // SME approval first, if the job requires it
        activity.record(job.getId(), ActivityRecorder.ASSESSMENT,
                "Personal assessment v" + a.getVersion() + " written for " + name(candidate), a.getId());
        return a;
    }

    private static AssessmentQuestion base(Assessment a, int seq, QuestionType type, int points, int seconds,
                                           String skill, String title) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setAssessment(a);
        q.setLevel(LEVEL);
        q.setSeq(seq);
        q.setType(type);
        q.setPoints(points);
        q.setTimeEstimateSec(seconds);
        q.setSkill(blank(skill) ? null : skill.strip());
        q.setDifficulty(Difficulty.MEDIUM.name());
        q.setTitle(title);
        q.setOrigin("AI");
        q.setValidated(true);
        return q;
    }

    private static Blueprint.Slot slot(AssessmentQuestion q) {
        return new Blueprint.Slot(q.getSeq(), q.getType(), q.getCompetency(), q.getDimension(), q.getPoints(),
                Difficulty.MEDIUM, q.getSkill(), q.getTimeEstimateSec(), false);
    }

    // ── Gemini response schema ───────────────────────────────────────────

    static Map<String, Object> schema() {
        Map<String, Object> mcq = obj(Map.of(
                "question", str(), "options", arr(str()),
                "correct_option", Map.of("type", "STRING", "enum", List.of("A", "B", "C", "D")),
                "explanation", str(), "skill", str()),
                List.of("question", "options", "correct_option", "explanation", "skill"));
        Map<String, Object> open = obj(Map.of(
                "question", str(), "model_answer", str(), "key_points", arr(str()), "skill", str()),
                List.of("question", "model_answer", "key_points", "skill"));
        Map<String, Object> bug = obj(Map.of(
                "question", str(), "code_snippet", str(), "model_answer", str(), "key_points", arr(str()), "skill", str()),
                List.of("question", "code_snippet", "model_answer", "key_points", "skill"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mcqs", arr(mcq));
        props.put("experience", arr(open));
        props.put("bug_fixing", arr(bug));
        props.put("devops", arr(open));
        props.put("agile", arr(open));
        return obj(props, List.copyOf(COUNTS.keySet()));
    }

    private static Map<String, Object> str() { return Map.of("type", "STRING"); }
    private static Map<String, Object> arr(Map<String, Object> items) { return Map.of("type", "ARRAY", "items", items); }
    private static Map<String, Object> obj(Map<String, Object> props, List<String> required) {
        return Map.of("type", "OBJECT", "properties", props, "required", required);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }

    private static List<String> lower(List<String> xs) {
        return xs.stream().map(x -> x.toLowerCase(Locale.ROOT)).toList();
    }

    private static String name(Candidate c) {
        return blank(c.getCandidateName()) ? "the candidate" : c.getCandidateName();
    }
}

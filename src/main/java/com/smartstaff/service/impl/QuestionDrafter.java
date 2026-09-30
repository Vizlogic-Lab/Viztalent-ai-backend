package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.entity.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.*;

/** Drafts one question for one blueprint slot with a single Gemini call,
 *  using a JSON response schema per question type. Validation happens later
 *  (QuestionValidator); nothing here is trusted yet. */
@Component
public class QuestionDrafter {

    static final int JD_CHARS = 1500;

    /** Fixed weights (30% of a CODE_WRITE question); Gemini only describes them. */
    static final List<RubricCriterion> CODE_WRITE_RUBRIC = RubricCriterion.CODE_WRITE_DEFAULT;

    private static final Map<String, String> LANGUAGE_ALIASES = Map.of(
            "c++", "cpp", "js", "javascript", "node", "javascript", "nodejs", "javascript", "python3", "python");

    private final GeminiClient geminiClient;
    private final ObjectMapper json;

    public QuestionDrafter(GeminiClient geminiClient, ObjectMapper json) {
        this.geminiClient = geminiClient;
        this.json = json;
    }

    /** Everything a slot's prompt needs. codeLanguages are the runner-available
     *  languages this question may use (CODE_OUTPUT uses the first only). */
    public record SlotContext(Job job, JobRoleProfile profile, Blueprint blueprint, Blueprint.Slot slot,
                              List<String> codeLanguages) {
        List<String> languagesFor() {
            return slot.type() == QuestionType.CODE_OUTPUT ? codeLanguages.subList(0, 1) : codeLanguages;
        }
    }

    public static final class DraftException extends Exception {
        private final boolean geminiUnavailable;

        DraftException(String message, boolean geminiUnavailable, Throwable cause) {
            super(message, cause);
            this.geminiUnavailable = geminiUnavailable;
        }

        /** Gemini itself can't be used (network, 5xx, rate limit, bad key) — retrying this job is pointless. */
        public boolean geminiUnavailable() {
            return geminiUnavailable;
        }
    }

    public QuestionDraft draft(String apiKey, SlotContext ctx, String previousFailure) throws DraftException {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt(ctx, previousFailure))))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema(ctx.slot().type()),
                        "temperature", 0.4));

        String raw;
        try {
            raw = geminiClient.generateContent(apiKey, body);
        } catch (HttpClientErrorException e) {
            boolean unusable = e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403
                    || e.getStatusCode().value() == 429;
            throw new DraftException("Gemini rejected the request (" + e.getStatusCode().value() + ")", unusable, e);
        } catch (RestClientException e) {
            throw new DraftException("Gemini is unavailable", true, e);
        }

        try {
            JsonNode root = json.readTree(raw);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            return parse(json.readTree(text), ctx);
        } catch (IllegalArgumentException e) {
            throw new DraftException("Draft rejected: " + e.getMessage(), false, e);
        } catch (Exception e) {
            throw new DraftException("Gemini returned unreadable output", false, e);
        }
    }

    // ── prompts ─────────────────────────────────────────────────────────

    String prompt(SlotContext ctx, String previousFailure) {
        Job job = ctx.job();
        Blueprint.Slot slot = ctx.slot();
        JobRoleProfile p = ctx.profile();
        String jd = job.getJdText() == null ? "" : job.getJdText();
        if (jd.length() > JD_CHARS) jd = jd.substring(0, JD_CHARS);
        String skill = slot.targetSkill() == null ? "the core skills of this role" : "\"" + slot.targetSkill() + "\"";

        StringBuilder sb = new StringBuilder();
        sb.append("Write ONE ").append(label(slot.type())).append(" question for a skills assessment.\n")
                .append("Role: \"").append(job.getTitle()).append("\" (").append(p.getRoleFamily()).append(" role family, ")
                .append(p.getSeniority()).append(" seniority). Assessment level ").append(ctx.blueprint().level())
                .append(", difficulty ").append(slot.difficulty().label()).append(".\n")
                .append("Target skill: ").append(skill).append(". Test it the way this role actually uses it; ")
                .append("set the \"skill\" field to exactly that skill name.\n");
        if (slot.type().isCode()) {
            sb.append("Languages: ").append(String.join(", ", ctx.languagesFor()))
                    .append(" (give code for each of these, using exactly these language names).\n");
        }
        sb.append("Job description excerpt:\n").append(jd).append("\n\n")
                .append(instructions(slot)).append('\n')
                .append("Avoid well-known puzzles and textbook trivia; use situations from this role. No placeholders.\n");
        if (previousFailure != null) {
            sb.append("\nYour previous attempt was rejected: ").append(previousFailure).append(" Fix exactly this.\n");
        }
        return sb.toString();
    }

    private static String label(QuestionType type) {
        return switch (type) {
            case MCQ -> "multiple-choice (single answer)";
            case MSQ -> "multiple-select";
            case CODE_WRITE -> "coding";
            case CODE_DEBUG -> "debugging";
            case CODE_OUTPUT -> "predict-the-output code reading";
            case SCENARIO -> "applied scenario (short written answer)";
            case LOGIC -> "logical reasoning";
        };
    }

    private static String instructions(Blueprint.Slot slot) {
        return switch (slot.type()) {
            case MCQ -> "Exactly 4 distinct options and exactly ONE correct option; correct_indices holds its 0-based index. "
                    + "Explain briefly why it is correct.";
            case MSQ -> "Exactly 4 distinct options, 2 or 3 of them correct; correct_indices holds their 0-based indices. "
                    + "Explain briefly.";
            case CODE_WRITE -> "Programs read ALL input from stdin and print the answer to stdout; tests are stdin/stdout "
                    + "strings. Give at least 3 visible and at least 8 hidden tests, with categories BASIC, EDGE "
                    + "(empty, boundary, negative, duplicates) and LARGE (the largest input you can write out, which "
                    + "exposes slow solutions). The reference solution must pass every test. naive_solution is a plausible "
                    + "but flawed attempt (e.g. ignores edge cases) that fails at least 2 hidden tests. starter_code only "
                    + "reads input and must not solve the problem. Describe what good looks like for each rubric criterion. "
                    + "Target time: about " + slot.timeEstimateSec() / 60 + " minutes for a competent candidate.";
            case CODE_DEBUG -> "Write a realistic 20-60 line program with 1-3 planted bugs relevant to the target skill. "
                    + "buggy_code contains the bugs; fixed_code is identical except for the fixes; bug_descriptions "
                    + "lists each bug. Programs read stdin and print to stdout. Give at least 2 visible and at least 4 "
                    + "hidden tests: fixed_code passes all of them and buggy_code fails at least one hidden test.";
            case CODE_OUTPUT -> "Write a 10-30 line program that reads no input and is fully deterministic (no randomness, "
                    + "time, hashing order or threads). Predicting its exact output should require careful reading "
                    + "(evaluation order, references, collections, string handling). Output at most 10 short lines. "
                    + "Do not reveal the output.";
            case SCENARIO -> (slot.designScenario()
                    ? "Ask the candidate to design or architect something this role would build, at realistic scale. "
                    : "Describe a realistic situation this role faces (an incident, a trade-off, a decision) and ask "
                    + "what the candidate would do. ")
                    + "model_answer is what a strong candidate would write (120-200 words); key_points are 4 to 6 "
                    + "concrete, checkable points a grader can look for.";
            case LOGIC -> "A reasoning or algorithmic-thinking problem whose answer is a single short value (a number or "
                    + "one word). Give the answer and a short explanation.";
        };
    }

    // ── schemas ─────────────────────────────────────────────────────────

    static Map<String, Object> schema(QuestionType type) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill", str());
        switch (type) {
            case MCQ, MSQ -> {
                props.put("question", str());
                props.put("options", arr(str()));
                props.put("correct_indices", arr(Map.of("type", "INTEGER")));
                props.put("explanation", str());
            }
            case CODE_WRITE -> {
                props.put("title", str());
                props.put("statement", str());
                props.put("constraints", str());
                props.put("input_format", str());
                props.put("output_format", str());
                props.put("starter_code", codeList());
                props.put("reference_solution", codeList());
                props.put("naive_solution", codeList());
                props.put("tests", testList());
                props.put("rubric", arr(obj(Map.of(
                        "criterion", Map.of("type", "STRING", "enum", List.of("approach", "complexity", "edge_cases", "readability")),
                        "description", str()), List.of("criterion", "description"))));
                props.put("expected_complexity", str());
            }
            case CODE_DEBUG -> {
                props.put("title", str());
                props.put("statement", str());
                props.put("buggy_code", codeList());
                props.put("fixed_code", codeList());
                props.put("bug_descriptions", arr(str()));
                props.put("tests", testList());
            }
            case CODE_OUTPUT -> {
                props.put("title", str());
                props.put("code", codeList());
            }
            case SCENARIO -> {
                props.put("prompt", str());
                props.put("model_answer", str());
                props.put("key_points", arr(str()));
            }
            case LOGIC -> {
                props.put("prompt", str());
                props.put("answer", str());
                props.put("explanation", str());
            }
        }
        return obj(props, List.copyOf(props.keySet()));
    }

    private static Map<String, Object> str() {
        return Map.of("type", "STRING");
    }

    private static Map<String, Object> arr(Map<String, Object> items) {
        return Map.of("type", "ARRAY", "items", items);
    }

    private static Map<String, Object> obj(Map<String, Object> props, List<String> required) {
        return Map.of("type", "OBJECT", "properties", props, "required", required);
    }

    private static Map<String, Object> codeList() {
        return arr(obj(Map.of("language", str(), "code", str()), List.of("language", "code")));
    }

    private static Map<String, Object> testList() {
        return arr(obj(Map.of(
                "input", str(),
                "expected_output", str(),
                "visible", Map.of("type", "BOOLEAN"),
                "category", Map.of("type", "STRING", "enum", List.of("BASIC", "EDGE", "LARGE"))),
                List.of("input", "expected_output", "visible", "category")));
    }

    // ── parsing ─────────────────────────────────────────────────────────

    /** A question pre-filled from its slot: level, order, type, points, time, difficulty. */
    static AssessmentQuestion baseQuestion(String level, Blueprint.Slot slot) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setLevel(level);
        q.setSeq(slot.seq());
        q.setType(slot.type());
        q.setDimension(slot.dimension());
        q.setCompetency(slot.competency());
        q.setPoints(slot.points());
        q.setTimeEstimateSec(slot.timeEstimateSec());
        q.setDifficulty(slot.difficulty().label());
        return q;
    }

    QuestionDraft parse(JsonNode o, SlotContext ctx) {
        Blueprint.Slot slot = ctx.slot();
        AssessmentQuestion q = baseQuestion(ctx.blueprint().level(), slot);
        q.setOrigin("AI");
        String skill = text(o, "skill");
        q.setSkill(skill == null ? slot.targetSkill() : skill.trim());
        Map<String, String> naive = Map.of();

        switch (slot.type()) {
            case MCQ, MSQ -> {
                q.setPrompt(required(o, "question"));
                List<String> options = new ArrayList<>();
                o.path("options").forEach(n -> options.add(n.asText("").trim()));
                q.setOptions(options);
                List<Integer> correct = new ArrayList<>();
                o.path("correct_indices").forEach(n -> correct.add(n.asInt(-1)));
                q.setCorrectIndices(correct);
                q.setExplanation(text(o, "explanation"));
            }
            case CODE_WRITE -> {
                List<String> langs = ctx.languagesFor();
                q.setTitle(text(o, "title"));
                q.setPrompt(required(o, "statement"));
                q.setConstraints(text(o, "constraints"));
                q.setInputFormat(text(o, "input_format"));
                q.setOutputFormat(text(o, "output_format"));
                q.setLanguages(new ArrayList<>(langs));
                q.setStarterCode(codeMap(o.path("starter_code"), langs));
                q.setReferenceSolution(codeMap(o.path("reference_solution"), langs));
                naive = codeMap(o.path("naive_solution"), langs);
                addTests(q, o.path("tests"));
                q.setExpectedComplexity(truncate(text(o, "expected_complexity"), 64));
                Map<String, String> described = new HashMap<>();
                o.path("rubric").forEach(r -> described.put(r.path("criterion").asText(""), r.path("description").asText("")));
                q.setRubric(CODE_WRITE_RUBRIC.stream()
                        .map(c -> new RubricCriterion(c.criterion(), c.weight(),
                                described.getOrDefault(c.criterion(), "").isBlank() ? c.description() : described.get(c.criterion())))
                        .toList());
            }
            case CODE_DEBUG -> {
                List<String> langs = ctx.languagesFor();
                q.setTitle(text(o, "title"));
                q.setPrompt(required(o, "statement"));
                q.setLanguages(new ArrayList<>(langs));
                Map<String, String> buggy = codeMap(o.path("buggy_code"), langs);
                q.setBuggyCode(buggy);
                q.setStarterCode(new LinkedHashMap<>(buggy));
                q.setReferenceSolution(codeMap(o.path("fixed_code"), langs));
                List<String> bugs = new ArrayList<>();
                o.path("bug_descriptions").forEach(n -> bugs.add(n.asText("")));
                q.setBugDescriptions(bugs);
                addTests(q, o.path("tests"));
            }
            case CODE_OUTPUT -> {
                List<String> langs = ctx.languagesFor();
                q.setTitle(text(o, "title"));
                q.setPrompt("What is the exact output of this program?");
                q.setLanguages(new ArrayList<>(langs));
                q.setStarterCode(codeMap(o.path("code"), langs));
            }
            case SCENARIO -> {
                q.setPrompt(required(o, "prompt"));
                q.setModelAnswer(text(o, "model_answer"));
                List<String> points = new ArrayList<>();
                o.path("key_points").forEach(n -> points.add(n.asText("").trim()));
                q.setKeyPoints(points);
            }
            case LOGIC -> {
                q.setPrompt(required(o, "prompt"));
                q.setModelAnswer(text(o, "answer"));
                q.setExplanation(text(o, "explanation"));
            }
        }
        return new QuestionDraft(q, naive);
    }

    static void addTests(AssessmentQuestion q, JsonNode tests) {
        for (JsonNode t : tests) {
            QuestionTestCase tc = new QuestionTestCase();
            tc.setInput(t.path("input").asText(""));
            tc.setExpectedOutput(t.path("expected_output").asText(""));
            tc.setVisible(t.path("visible").asBoolean(false));
            TestCaseCategory category;
            try {
                category = TestCaseCategory.valueOf(t.path("category").asText("BASIC").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                category = TestCaseCategory.BASIC;
            }
            tc.setCategory(category);
            tc.setWeight(category == TestCaseCategory.BASIC ? BigDecimal.ONE : new BigDecimal("1.5"));
            q.addTestCase(tc);
        }
    }

    static Map<String, String> codeMap(JsonNode list, List<String> allowed) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode entry : list) {
            String lang = entry.path("language").asText("").trim().toLowerCase(Locale.ROOT);
            lang = LANGUAGE_ALIASES.getOrDefault(lang, lang);
            String code = entry.path("code").asText("");
            if (allowed.contains(lang) && !code.isBlank() && !out.containsKey(lang)) out.put(lang, code);
        }
        return out;
    }

    private static String required(JsonNode o, String field) {
        String v = text(o, field);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " is missing");
        return v.trim();
    }

    private static String text(JsonNode o, String field) {
        JsonNode n = o.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

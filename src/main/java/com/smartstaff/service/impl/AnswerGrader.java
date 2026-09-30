package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.QuestionScoreBreakdown;
import com.smartstaff.entity.RubricCriterion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The AI-graded parts of scoring: the CODE_WRITE rubric (quality) and SCENARIO
 *  key-point coverage. Every call goes through GeminiClient (stubbable). When
 *  Gemini is unavailable the grade comes back `graded=false`, and the scoring
 *  job flags those points for manual review rather than guessing. */
@Component
public class AnswerGrader {

    private static final Logger log = LoggerFactory.getLogger(AnswerGrader.class);

    private final GeminiClient geminiClient;
    private final ObjectMapper json;

    public AnswerGrader(GeminiClient geminiClient, ObjectMapper json) {
        this.geminiClient = geminiClient;
        this.json = json;
    }

    /** A grade over weighted lines; `graded=false` means Gemini couldn't be
     *  reached and nothing was scored. `ratio()` is the awarded fraction [0,1]. */
    public record Grade(List<QuestionScoreBreakdown> lines, boolean graded) {
        static Grade ungraded() {
            return new Grade(List.of(), false);
        }

        public double ratio() {
            double weight = lines.stream().mapToDouble(QuestionScoreBreakdown::weight).sum();
            if (weight <= 0) return 0.0;
            double awarded = lines.stream().mapToDouble(QuestionScoreBreakdown::awarded).sum();
            return Math.max(0.0, Math.min(1.0, awarded / weight));
        }
    }

    // ── CODE_WRITE rubric ───────────────────────────────────────────────

    public Grade gradeCodeWrite(String apiKey, AssessmentQuestion q, String language, String code) {
        if (apiKey == null || apiKey.isBlank()) return Grade.ungraded();
        List<RubricCriterion> rubric = q.getRubric().isEmpty() ? RubricCriterion.CODE_WRITE_DEFAULT : q.getRubric();

        StringBuilder p = new StringBuilder();
        p.append("You are grading the QUALITY of a candidate's solution to a coding problem, not whether it passes tests.\n")
                .append("Problem:\n").append(q.getPrompt()).append('\n');
        if (q.getExpectedComplexity() != null) p.append("Expected complexity: ").append(q.getExpectedComplexity()).append('\n');
        p.append("Language: ").append(language).append("\nCandidate's code:\n```\n").append(code).append("\n```\n\n")
                .append("Score each criterion from 0 to its max, judging only what the code shows:\n");
        for (RubricCriterion c : rubric) {
            p.append("- ").append(c.criterion()).append(" (0..").append(c.weight()).append("): ").append(c.description()).append('\n');
        }

        Map<String, Object> schema = objSchema(Map.of(
                "criteria", arr(objSchema(Map.of(
                        "criterion", strSchema(),
                        "score", Map.of("type", "NUMBER"),
                        "note", strSchema()), List.of("criterion", "score")))), List.of("criteria"));

        JsonNode out = call(apiKey, p.toString(), schema);
        if (out == null) return Grade.ungraded();

        Map<String, Double> byCriterion = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (JsonNode c : out.path("criteria")) {
            byCriterion.put(c.path("criterion").asText(""), c.path("score").asDouble(0));
            notes.put(c.path("criterion").asText(""), c.path("note").asText(""));
        }
        List<QuestionScoreBreakdown> lines = new ArrayList<>();
        for (RubricCriterion c : rubric) {
            double awarded = Math.max(0, Math.min(c.weight(), byCriterion.getOrDefault(c.criterion(), 0.0)));
            lines.add(new QuestionScoreBreakdown(c.criterion(), awarded, c.weight(),
                    notes.getOrDefault(c.criterion(), "")));
        }
        return new Grade(lines, true);
    }

    // ── SCENARIO key-point coverage ─────────────────────────────────────

    public Grade gradeScenario(String apiKey, AssessmentQuestion q, String answer) {
        if (apiKey == null || apiKey.isBlank()) return Grade.ungraded();
        List<String> keyPoints = q.getKeyPoints();
        if (keyPoints.isEmpty()) return Grade.ungraded();

        StringBuilder p = new StringBuilder();
        p.append("Grade a candidate's written answer by which key points it covers.\n")
                .append("Question:\n").append(q.getPrompt()).append('\n');
        if (q.getModelAnswer() != null) p.append("Model answer:\n").append(q.getModelAnswer()).append('\n');
        p.append("Key points (mark each covered or not, judging meaning not wording):\n");
        for (int i = 0; i < keyPoints.size(); i++) p.append(i + 1).append(". ").append(keyPoints.get(i)).append('\n');
        p.append("\nCandidate's answer:\n").append(answer == null ? "" : answer).append('\n');

        Map<String, Object> schema = objSchema(Map.of(
                "key_points", arr(objSchema(Map.of(
                        "index", Map.of("type", "INTEGER"),
                        "covered", Map.of("type", "BOOLEAN"),
                        "note", strSchema()), List.of("index", "covered")))), List.of("key_points"));

        JsonNode out = call(apiKey, p.toString(), schema);
        if (out == null) return Grade.ungraded();

        Map<Integer, Boolean> covered = new LinkedHashMap<>();
        Map<Integer, String> notes = new LinkedHashMap<>();
        for (JsonNode kp : out.path("key_points")) {
            int idx = kp.path("index").asInt(0) - 1;
            covered.put(idx, kp.path("covered").asBoolean(false));
            notes.put(idx, kp.path("note").asText(""));
        }
        List<QuestionScoreBreakdown> lines = new ArrayList<>();
        for (int i = 0; i < keyPoints.size(); i++) {
            double awarded = covered.getOrDefault(i, false) ? 1.0 : 0.0;
            lines.add(new QuestionScoreBreakdown(keyPoints.get(i), awarded, 1.0, notes.getOrDefault(i, "")));
        }
        return new Grade(lines, true);
    }

    // ── Gemini call ─────────────────────────────────────────────────────

    private JsonNode call(String apiKey, String prompt, Map<String, Object> schema) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema,
                        "temperature", 0.1));
        try {
            String raw = geminiClient.generateContent(apiKey, body);
            JsonNode root = json.readTree(raw);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            return json.readTree(text);
        } catch (RestClientException e) {
            log.warn("Gemini grading call failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Gemini grading returned unreadable output: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> strSchema() {
        return Map.of("type", "STRING");
    }

    private static Map<String, Object> arr(Map<String, Object> items) {
        return Map.of("type", "ARRAY", "items", items);
    }

    private static Map<String, Object> objSchema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "OBJECT", "properties", props, "required", required);
    }
}

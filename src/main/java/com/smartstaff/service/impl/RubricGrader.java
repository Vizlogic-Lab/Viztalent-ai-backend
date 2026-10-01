package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.entity.RubricCriterion;
import com.smartstaff.util.EvaluationRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.*;

/** The rubric layer of evaluation (F9): one Gemini call per grading run,
 *  temperature 0, strict JSON schema. Every criterion score must be backed by
 *  evidence quoted from the candidate's own code or answer — a score whose
 *  evidence isn't found in the answer is rejected and counts 0. Gemini
 *  failures surface as {@link Unavailable} so the job can retry later. */
@Component
public class RubricGrader {

    private static final Logger log = LoggerFactory.getLogger(RubricGrader.class);

    /** Marks around the candidate's answer in the prompt. */
    static final String ANSWER_START = "<<<CANDIDATE_ANSWER";
    static final String ANSWER_END = "CANDIDATE_ANSWER>>>";

    private final GeminiClient geminiClient;
    private final ObjectMapper json;

    public RubricGrader(GeminiClient geminiClient, ObjectMapper json) {
        this.geminiClient = geminiClient;
        this.json = json;
    }

    public static final class Unavailable extends Exception {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** What is being graded against. id is what Gemini must echo back. */
    public record Criterion(String id, String description, double max) {}

    public record CriterionScore(String criterion, double score, double max, String evidence, String comment,
                                 boolean accepted) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("criterion", criterion);
            m.put("score", score);
            m.put("max", max);
            m.put("evidence", evidence);
            m.put("comment", comment);
            m.put("accepted", accepted);
            return m;
        }
    }

    /** One grading run; fraction() is the awarded share of the rubric. */
    public record Run(List<CriterionScore> criteria) {
        public double total() {
            return criteria.stream().mapToDouble(CriterionScore::score).sum();
        }

        public double max() {
            return criteria.stream().mapToDouble(CriterionScore::max).sum();
        }

        public double fraction() {
            return max() <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, total() / max()));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total());
            m.put("max", max());
            m.put("criteria", criteria.stream().map(CriterionScore::toMap).toList());
            return m;
        }
    }

    // ── criteria ────────────────────────────────────────────────────────

    /** CODE_WRITE quality criteria (approach, complexity, edge cases, readability). */
    public static List<Criterion> codeCriteria(List<RubricCriterion> rubric) {
        List<RubricCriterion> r = rubric == null || rubric.isEmpty() ? RubricCriterion.CODE_WRITE_DEFAULT : rubric;
        return r.stream().map(c -> new Criterion(c.criterion(), c.description(), c.weight())).toList();
    }

    /** SCENARIO: one criterion per key point, worth 1 each. */
    public static List<Criterion> keyPointCriteria(List<String> keyPoints) {
        List<Criterion> out = new ArrayList<>();
        for (int i = 0; i < keyPoints.size(); i++) out.add(new Criterion("KP" + (i + 1), keyPoints.get(i), 1.0));
        return out;
    }

    // ── prompts ─────────────────────────────────────────────────────────

    public static String codePrompt(String statement, String language, String referenceSolution, String expectedComplexity,
                                    String testSummary, List<Criterion> criteria, String code) {
        StringBuilder p = new StringBuilder();
        p.append("You are grading the QUALITY of a candidate's solution to a coding problem. Correctness is already ")
                .append("measured by tests; judge only the criteria below, using only what the code shows.\n\n")
                .append("Problem:\n").append(statement).append("\n\n");
        if (expectedComplexity != null) p.append("Expected complexity: ").append(expectedComplexity).append('\n');
        if (referenceSolution != null) p.append("Reference solution (for comparison only):\n").append(referenceSolution).append("\n\n");
        if (testSummary != null) p.append("Test results of the candidate's code: ").append(testSummary).append("\n\n");
        appendCriteria(p, criteria);
        p.append("\nCandidate's ").append(language).append(" code:\n").append(ANSWER_START).append('\n')
                .append(code).append('\n').append(ANSWER_END).append('\n');
        return p.toString();
    }

    public static String scenarioPrompt(String question, String modelAnswer, List<Criterion> criteria, String answer) {
        StringBuilder p = new StringBuilder();
        p.append("You are grading a candidate's written answer by which key points it covers. Judge meaning, not ")
                .append("wording.\n\nQuestion:\n").append(question).append("\n\n");
        if (modelAnswer != null) p.append("Model answer (for comparison only):\n").append(modelAnswer).append("\n\n");
        appendCriteria(p, criteria);
        p.append("\nCandidate's answer:\n").append(ANSWER_START).append('\n').append(answer).append('\n')
                .append(ANSWER_END).append('\n');
        return p.toString();
    }

    private static void appendCriteria(StringBuilder p, List<Criterion> criteria) {
        p.append("Criteria (criterion id, what it means, maximum score):\n");
        for (Criterion c : criteria) {
            p.append("- ").append(c.id()).append(": ").append(c.description()).append(" (max ").append(fmt(c.max())).append(")\n");
        }
        p.append("\nFor EVERY criterion return: criterion (the id), score (0 to its max), max, evidence (copy the exact ")
                .append("words from the candidate's answer that justify the score, verbatim), and a short comment. If ")
                .append("nothing in the answer supports a criterion, give 0 and leave evidence empty. Never quote the ")
                .append("question, the reference or the model answer as evidence.\n");
    }

    // ── grading ─────────────────────────────────────────────────────────

    public Run grade(String apiKey, String prompt, List<Criterion> criteria, String candidateAnswer) throws Unavailable {
        if (apiKey == null || apiKey.isBlank()) throw new Unavailable("No Gemini API key is configured.", null);

        Map<String, Object> item = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "criterion", Map.of("type", "STRING"),
                        "score", Map.of("type", "NUMBER"),
                        "max", Map.of("type", "NUMBER"),
                        "evidence", Map.of("type", "STRING"),
                        "comment", Map.of("type", "STRING")),
                "required", List.of("criterion", "score", "max", "evidence", "comment"));
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("criteria", Map.of("type", "ARRAY", "items", item)),
                "required", List.of("criteria"));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema, "temperature", 0));

        JsonNode out;
        try {
            JsonNode root = json.readTree(geminiClient.generateContent(apiKey, body));
            out = json.readTree(root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(""));
        } catch (RestClientException e) {
            log.warn("Rubric grading call failed: {}", e.getMessage());
            throw new Unavailable("The AI grader is unavailable.", e);
        } catch (Exception e) {
            log.warn("Rubric grading returned unreadable output: {}", e.getMessage());
            throw new Unavailable("The AI grader returned unreadable output.", e);
        }

        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode c : out.path("criteria")) byId.put(c.path("criterion").asText("").trim().toLowerCase(Locale.ROOT), c);

        List<CriterionScore> scores = new ArrayList<>();
        for (Criterion c : criteria) {
            JsonNode r = byId.get(c.id().toLowerCase(Locale.ROOT));
            if (r == null) {
                scores.add(new CriterionScore(c.id(), 0, c.max(), "", "Not graded.", false));
                continue;
            }
            double score = Math.max(0, Math.min(c.max(), r.path("score").asDouble(0)));
            String evidence = r.path("evidence").asText("");
            String comment = r.path("comment").asText("");
            if (score > 0 && !EvaluationRules.evidenceFound(evidence, candidateAnswer)) {
                scores.add(new CriterionScore(c.id(), 0, c.max(), evidence,
                        "Rejected: the evidence isn't quoted from the candidate's answer.", false));
            } else {
                scores.add(new CriterionScore(c.id(), score, c.max(), evidence, comment, true));
            }
        }
        return new Run(scores);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}

package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.ai.AiFeature;
import com.smartstaff.client.GeminiClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Meaning-based CV vs JD match: one Gemini call (temperature 0) that reads both texts the way a
 *  recruiter would. Related tools, domain, projects and seniority count, not only exact skill names.
 *  Shown next to the keyword fit score; it never replaces it. Never call inside a transaction. */
@Component
public class CvMatcher {

    /** Enough for a full JD and a long CV, while keeping each call small. */
    static final int MAX_CHARS = 12_000;

    public record Result(int score, String summary, List<String> strengths, List<String> gaps) {}

    private final GeminiClient gemini;
    private final ObjectMapper json;

    public CvMatcher(GeminiClient gemini, ObjectMapper json) {
        this.gemini = gemini;
        this.json = json;
    }

    public Result match(String apiKey, String jdText, String cvText) throws Exception {
        String prompt = """
                You are an experienced recruiter. Compare the CV with the job description and rate how well \
                the candidate fits the role, from 0 to 100.
                Judge by meaning, not exact words: count equivalent or closely related tools and platforms \
                (for example different versions or vendor names of the same product), the domain, the projects, \
                the responsibilities held and the years of relevant experience. Do not reward keyword stuffing.
                Guide: 85-100 strong fit for the core requirements; 70-84 good fit with minor gaps; \
                50-69 partial fit; below 50 weak fit or a different role.
                Return: score (integer 0-100), summary (one or two sentences, no candidate name), \
                strengths (at most 4 short items), gaps (at most 4 short items; empty if none).

                JOB DESCRIPTION:
                """ + clip(jdText) + "\n\nCV:\n" + clip(cvText);
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "score", Map.of("type", "INTEGER"),
                        "summary", Map.of("type", "STRING"),
                        "strengths", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "gaps", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("score", "summary", "strengths", "gaps"));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema, "temperature", 0));
        JsonNode root = json.readTree(gemini.generateContent(apiKey, body, AiFeature.CV_MATCH));
        JsonNode out = json.readTree(root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(""));
        if (!out.has("score")) throw new IllegalStateException("AI match returned no score");
        int score = Math.max(0, Math.min(100, out.path("score").asInt()));
        return new Result(score, out.path("summary").asText("").trim(), items(out.path("strengths")), items(out.path("gaps")));
    }

    private static List<String> items(JsonNode arr) {
        List<String> list = new ArrayList<>();
        for (JsonNode n : arr) if (!n.asText("").isBlank() && list.size() < 4) list.add(n.asText().trim());
        return list;
    }

    static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS);
    }
}

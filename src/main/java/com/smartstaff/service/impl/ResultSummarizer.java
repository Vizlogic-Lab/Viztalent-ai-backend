package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.util.ResultNarrator;
import com.smartstaff.util.ResultNarrator.Facts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/** F10 summary: one Gemini call (temperature 0) that may only rephrase the given
 *  numbers and evidence into a theory line, a hands-on line and up to 4 gaps.
 *  Every number in the output must exist in the input, otherwise — or when
 *  Gemini is unavailable — the template summary is used. Never call inside a
 *  transaction. */
@Component
public class ResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ResultSummarizer.class);

    private final GeminiClient gemini;
    private final ObjectMapper json;

    public ResultSummarizer(GeminiClient gemini, ObjectMapper json) {
        this.gemini = gemini;
        this.json = json;
    }

    public Map<String, Object> summarize(String apiKey, Facts facts) {
        if (apiKey == null || apiKey.isBlank()) return ResultNarrator.template(facts);
        String input = facts.asText();
        String prompt = """
                You write a short assessment summary for a hiring manager. Use ONLY the facts below — do not add \
                numbers, skills or judgements that are not in them, and never mention the candidate's name.
                Return: theory_line (one sentence on theoretical understanding), hands_on_line (one sentence on \
                hands-on capability), gaps (at most 4 short bullets naming concrete weaknesses from the evidence).

                Facts:
                """ + input;
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "theory_line", Map.of("type", "STRING"),
                        "hands_on_line", Map.of("type", "STRING"),
                        "gaps", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("theory_line", "hands_on_line", "gaps"));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema, "temperature", 0));
        try {
            JsonNode root = json.readTree(gemini.generateContent(apiKey, body));
            JsonNode out = json.readTree(root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(""));
            String theory = out.path("theory_line").asText("").trim();
            String handsOn = out.path("hands_on_line").asText("").trim();
            List<String> gaps = new ArrayList<>();
            for (JsonNode g : out.path("gaps")) if (!g.asText("").isBlank() && gaps.size() < 4) gaps.add(g.asText().trim());
            if (theory.isEmpty() || handsOn.isEmpty()) return ResultNarrator.template(facts);
            String all = theory + "\n" + handsOn + "\n" + String.join("\n", gaps);
            if (!ResultNarrator.numbersSupported(all, input)) {
                log.info("AI summary used a number not in its input; using the template");
                return ResultNarrator.template(facts);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("theory_line", theory);
            m.put("hands_on_line", handsOn);
            m.put("gaps", gaps);
            m.put("source", "AI");
            return m;
        } catch (Exception e) {
            log.warn("AI summary unavailable: {}", e.getMessage());
            return ResultNarrator.template(facts);
        }
    }
}

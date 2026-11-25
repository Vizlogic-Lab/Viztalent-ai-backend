package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.ai.AiFeature;
import com.smartstaff.client.GeminiClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads a JD with one Gemini call (temperature 0) and returns its must-have and
 *  nice-to-have skills in short form, the phrases a CV may use for each skill, and the
 *  experience range. Works for any role — technical or not — unlike the fixed skill list,
 *  which stays as the fallback when there is no AI key or the call fails. */
@Component
public class JdSkillExtractor {

    static final int MAX_JD_CHARS = 15_000;
    static final int MAX_SKILLS = 15;
    static final int MAX_ALIASES = 6;

    public record Result(List<String> mustHave, List<String> niceToHave, Map<String, List<String>> aliases,
                         Integer minYears, Integer maxYears) {}

    private final GeminiClient gemini;
    private final ObjectMapper json;

    public JdSkillExtractor(GeminiClient gemini, ObjectMapper json) {
        this.gemini = gemini;
        this.json = json;
    }

    public Result extract(String apiKey, String jdText) throws Exception {
        String prompt = """
                You are an experienced recruiter. Read the job description and list the skills a CV must show.
                must_have: the core requirements (at most 10). nice_to_have: anything described as preferred, ideal, \
                an advantage, a plus, desirable or optional (at most 8). Include role skills of any kind — technical \
                tools, but also e.g. project coordination, stakeholder management, escalation management, status \
                reporting — whatever the role really needs. Leave out languages spoken, degrees, and soft traits such \
                as "reliable" or "assertive".
                Each skill: a short lowercase name of 1-4 words (e.g. "sap", "stakeholder management").
                For each skill give up to 6 lowercase aliases: other words or short phrases a CV would use to show the \
                same skill (e.g. "stakeholder management" -> "stakeholders", "stakeholder communication"; \
                "status reporting" -> "status reports", "executive reporting"). Keep aliases specific; never a single \
                generic word such as "management" or "project".
                experience_min_years / experience_max_years: the years of experience the JD asks for, as integers; \
                "several years" means 3 as the minimum; use 0 when the JD gives no hint.

                JOB DESCRIPTION:
                """ + clip(jdText);
        Map<String, Object> skill = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "skill", Map.of("type", "STRING"),
                        "aliases", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("skill", "aliases"));
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "must_have", Map.of("type", "ARRAY", "items", skill),
                        "nice_to_have", Map.of("type", "ARRAY", "items", skill),
                        "experience_min_years", Map.of("type", "INTEGER"),
                        "experience_max_years", Map.of("type", "INTEGER")),
                "required", List.of("must_have", "nice_to_have", "experience_min_years", "experience_max_years"));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema, "temperature", 0));
        JsonNode root = json.readTree(gemini.generateContent(apiKey, body, AiFeature.JD_SKILLS));
        String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        return parse(json.readTree(text));
    }

    /** Turns the model's JSON into clean, de-duplicated lists. Package-private for tests. */
    static Result parse(JsonNode out) {
        if (out == null || !out.has("must_have")) throw new IllegalStateException("JD skill read returned no skills");
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        List<String> must = skills(out.path("must_have"), aliases, List.of(), 10);
        List<String> nice = skills(out.path("nice_to_have"), aliases, must, 8);
        if (must.isEmpty() && nice.isEmpty()) throw new IllegalStateException("JD skill read returned no skills");
        Integer min = years(out.path("experience_min_years"));
        Integer max = years(out.path("experience_max_years"));
        if (min != null && max != null && min > max) max = null;
        return new Result(must, nice, aliases, min, max);
    }

    private static List<String> skills(JsonNode arr, Map<String, List<String>> aliases, List<String> taken, int cap) {
        List<String> list = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = clean(n.path("skill").asText(""));
            if (s.isEmpty() || s.length() > 40 || list.contains(s) || taken.contains(s) || list.size() >= cap) continue;
            list.add(s);
            List<String> al = new ArrayList<>();
            for (JsonNode a : n.path("aliases")) {
                String v = clean(a.asText(""));
                // A single short generic word would match almost any CV.
                if (v.isEmpty() || v.equals(s) || v.length() > 40 || al.contains(v) || al.size() >= MAX_ALIASES) continue;
                if (!v.contains(" ") && v.length() < 3) continue; // "pm" no, "pmo" / "sap" yes
                al.add(v);
            }
            aliases.put(s, al);
        }
        return list;
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static Integer years(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        int v = n.asInt();
        return v <= 0 || v > 40 ? null : v;
    }

    static String clip(String s) {
        if (s == null) return "";
        return s.length() <= MAX_JD_CHARS ? s : s.substring(0, MAX_JD_CHARS);
    }
}

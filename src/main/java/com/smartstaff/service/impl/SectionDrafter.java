package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.ai.AiFeature;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.entity.*;
import com.smartstaff.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/** Writes several questions of one type in a single Gemini call, for a hand-authored
 *  assessment's "Generate by AI" section button. The reply is bound to the same
 *  strict per-type schema the slot drafter uses ({@link QuestionDrafter#schema}),
 *  wrapped in {"questions": [...]}, and each item is parsed and content-checked on
 *  its own: a bad item is dropped, not the whole batch. Batches are kept small
 *  (MCQ / ONE_WORD 10, SCENARIO / PROJECT 5) so long written answers can't overflow
 *  the output limit. No database access and no transaction — callers persist. */
@Component
public class SectionDrafter {

    private static final Logger log = LoggerFactory.getLogger(SectionDrafter.class);
    private static final int JD_CHARS = 3000;
    private static final int SEEN_CHARS = 90;

    private final GeminiClient gemini;
    private final QuestionDrafter drafter;
    private final ObjectMapper json;

    public SectionDrafter(GeminiClient gemini, QuestionDrafter drafter, ObjectMapper json) {
        this.gemini = gemini;
        this.drafter = drafter;
        this.json = json;
    }

    /** Questions per Gemini call for a type. */
    public static int batchSize(QuestionType type) {
        return type == QuestionType.MCQ || type == QuestionType.ONE_WORD ? 10 : 5;
    }

    /** What to write. existingPrompts are the section's questions so far, so the AI avoids repeating them. */
    public record Request(Job job, QuestionType type, int count, int marks, Difficulty difficulty, String topicHint,
                          List<String> existingPrompts) {}

    /** Up to request.count() content-checked questions, unsaved and without seq.
     *  @throws ApiException 502 ai_bad_output when two replies in a row give nothing usable;
     *          GeminiException (an ApiException) when Gemini can't be reached or the key is bad. */
    public List<AssessmentQuestion> draft(String apiKey, Request r) {
        String problem = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String text = call(apiKey, prompt(r, problem), r.type());
            List<String> rejected = new ArrayList<>();
            List<AssessmentQuestion> out = parse(text, r, rejected);
            if (!out.isEmpty()) {
                if (!rejected.isEmpty()) log.info("Section draft for job {} {}: dropped {} item(s): {}",
                        r.job().getId(), r.type(), rejected.size(), rejected);
                return out.size() > r.count() ? out.subList(0, r.count()) : out;
            }
            problem = rejected.isEmpty() ? "the reply had no questions." : rejected.get(0);
            log.warn("Section draft for job {} {} unusable (attempt {}): {}", r.job().getId(), r.type(), attempt, problem);
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY,
                "The AI didn't return usable " + label(r.type()) + " questions (" + problem + "). Please try again.",
                "ai_bad_output");
    }

    String prompt(Request r, String previousFailure) {
        Job job = r.job();
        String jd = job.getJdText() == null ? "" : job.getJdText();
        if (jd.length() > JD_CHARS) jd = jd.substring(0, JD_CHARS);
        Blueprint.Slot slot = slot(r, 0);

        StringBuilder sb = new StringBuilder();
        sb.append("Write ").append(r.count()).append(" different ").append(label(r.type()))
                .append(" questions for a skills assessment, as {\"questions\": [...]}.\n")
                .append("Role: \"").append(job.getTitle()).append("\". Difficulty: ").append(r.difficulty().label()).append(".\n");
        if (r.topicHint() != null && !r.topicHint().isBlank()) {
            sb.append("Focus on: ").append(r.topicHint().strip()).append(".\n");
        }
        sb.append("Spread the questions across the role's main skills; set \"skill\" to the skill each one tests.\n");
        if (!jd.isBlank()) sb.append("Job description excerpt:\n").append(jd).append("\n\n");
        sb.append("Every question: ").append(QuestionDrafter.instructions(slot)).append('\n')
                .append("Set \"scope\" to GENERIC unless a question depends on this company's internal details.\n")
                .append("Avoid well-known puzzles and textbook trivia; use situations from this role. No placeholders.\n");
        if (!r.existingPrompts().isEmpty()) {
            sb.append("Do NOT repeat or rephrase any of these existing questions:\n");
            for (String p : r.existingPrompts()) {
                String one = p.replaceAll("\\s+", " ").strip();
                sb.append("- ").append(one.length() > SEEN_CHARS ? one.substring(0, SEEN_CHARS) + "…" : one).append('\n');
            }
        }
        if (previousFailure != null) {
            sb.append("\nYour previous answer was rejected: ").append(previousFailure).append(" Fix exactly this.\n");
        }
        return sb.toString();
    }

    private String call(String apiKey, String prompt, QuestionType type) {
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("questions", Map.of("type", "ARRAY", "items", QuestionDrafter.schema(type))),
                "required", List.of("questions"));
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema,
                        "temperature", 0.5));
        String raw = gemini.generateContent(apiKey, body, AiFeature.QUESTION_GENERATION);   // GeminiException on failure
        try {
            return json.readTree(raw).path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI service returned an unreadable reply. Please try again.",
                    "ai_bad_output");
        }
    }

    /** Valid questions from the reply; why each other item was dropped goes into rejected. */
    List<AssessmentQuestion> parse(String text, Request r, List<String> rejected) {
        JsonNode items;
        try {
            items = json.readTree(text).path("questions");
        } catch (Exception e) {
            rejected.add("the reply wasn't valid JSON.");
            return List.of();
        }
        List<AssessmentQuestion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        r.existingPrompts().forEach(p -> seen.add(PromptKey.of(p)));
        Blueprint blueprint = new Blueprint(CustomAssessmentService.LEVEL, null, false, List.of(), 0, 0, List.of());
        for (JsonNode item : items) {
            Blueprint.Slot slot = slot(r, out.size());
            AssessmentQuestion q;
            try {
                q = drafter.parse(item, new QuestionDrafter.SlotContext(r.job(), null, blueprint, slot, List.of())).question();
            } catch (IllegalArgumentException e) {
                rejected.add(e.getMessage());
                continue;
            }
            QuestionValidator.Result check = QuestionValidator.checkWritten(q);
            if (!check.ok()) {
                rejected.add(check.reason());
                continue;
            }
            if (!seen.add(PromptKey.of(q.getPrompt()))) {
                rejected.add("duplicate question");
                continue;
            }
            q.setValidated(true);
            q.setValidationLog(check.log());
            out.add(q);
        }
        return out;
    }

    private static Blueprint.Slot slot(Request r, int seq) {
        return new Blueprint.Slot(seq, r.type(), r.type().competency(), r.type().dimension(), r.marks(), r.difficulty(),
                null, CustomAssessmentService.timeEstimate(r.type()), false);
    }

    static String label(QuestionType type) {
        return switch (type) {
            case MCQ -> "multiple-choice (single answer)";
            case ONE_WORD -> "one-word answer";
            case SCENARIO -> "scenario-based (short written answer)";
            case PROJECT -> "project-based (longer written task)";
            default -> type.name();
        };
    }

    /** Duplicate check key: lower-case letters and digits only. */
    static final class PromptKey {
        static String of(String prompt) {
            return prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        }
    }
}

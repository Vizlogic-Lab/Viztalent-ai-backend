package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.dto.request.ToolDeclarationRequest;
import com.smartstaff.dto.request.UniversalExecuteRequest;
import com.smartstaff.dto.response.RunScreeningResponse;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.service.RecruiterChatService;
import com.smartstaff.service.ScreeningService;
import com.smartstaff.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Backs POST /api/universal_execute (VoiceScreening.jsx's recruiter chat).
 *  Gemini is given the caller's persona as a system instruction and its
 *  declared tools as real Gemini function declarations; the only tool this
 *  backend actually knows how to run is PROCESS_RESUMES (the sole tool the
 *  frontend ever declares), which delegates straight to
 *  {@link ScreeningService#runScreening}. Any other declared tool, or a
 *  plain conversational turn, just returns Gemini's text reply.
 *
 *  Like AssessmentServiceImpl's Gemini calls, failures here (no key, bad
 *  key, network error) never surface as an HTTP error — this is a chat
 *  endpoint, so they come back as a normal {reply, table_data:null}
 *  the recruiter can read like any other message. */
@Service
public class RecruiterChatServiceImpl implements RecruiterChatService {

    private static final Logger log = LoggerFactory.getLogger(RecruiterChatServiceImpl.class);
    private static final String PROCESS_RESUMES_TOOL = "PROCESS_RESUMES";
    private static final String DEFAULT_PERSONA = "You are a helpful recruiting assistant.";

    private final ScreeningService screeningService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final GeminiClient geminiClient;

    public RecruiterChatServiceImpl(ScreeningService screeningService, SettingsService settingsService,
                                     ObjectMapper objectMapper, GeminiClient geminiClient) {
        this.screeningService = screeningService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.geminiClient = geminiClient;
    }

    @Override
    public RunScreeningResponse execute(UniversalExecuteRequest req, User requester) {
        String key = settingsService.getGeminiApiKeyOrNull();
        if (key == null || key.isBlank()) {
            return reply("I don't have a Gemini API key configured yet — add one in Settings so I can help.");
        }

        GeminiOutcome outcome;
        try {
            outcome = callGemini(req, key);
        } catch (Exception e) {
            log.warn("universal_execute Gemini call failed: {}", e.toString());
            return reply("I couldn't reach the AI service just now — please try again in a moment.");
        }

        if (outcome.functionName() != null && PROCESS_RESUMES_TOOL.equalsIgnoreCase(outcome.functionName())) {
            return runProcessResumes(req.session_id());
        }

        return reply(outcome.text() != null && !outcome.text().isBlank() ? outcome.text() : "Got it.");
    }

    private RunScreeningResponse runProcessResumes(String sessionId) {
        UUID jobId = parseJobIdOrNull(sessionId);
        if (jobId == null) {
            return reply("I don't see an active job for this session — upload a JD first, then ask me to screen the resumes.");
        }
        try {
            return screeningService.runScreening(jobId);
        } catch (ApiException e) {
            return reply(e.getMessage());
        }
    }

    // ── Gemini call ─────────────────────────────────────────────────────

    private record GeminiOutcome(String text, String functionName) {}

    private GeminiOutcome callGemini(UniversalExecuteRequest req, String key) throws Exception {
        List<Map<String, Object>> declarations = new ArrayList<>();
        List<ToolDeclarationRequest> tools = req.platform_config() == null ? null : req.platform_config().available_tools();
        if (tools != null) {
            for (ToolDeclarationRequest t : tools) {
                if (t.tag_name() == null || t.tag_name().isBlank()) continue;
                declarations.add(toFunctionDeclaration(t));
            }
        }

        String persona = req.platform_config() != null && req.platform_config().persona() != null
                ? req.platform_config().persona()
                : DEFAULT_PERSONA;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", persona))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", req.command())))));
        if (!declarations.isEmpty()) {
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        }

        String raw = geminiClient.generateContent(key, body);

        JsonNode root = objectMapper.readTree(raw);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");

        String text = null;
        String functionName = null;
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                functionName = part.path("functionCall").path("name").asText(null);
            } else if (part.has("text")) {
                text = part.path("text").asText(null);
            }
        }
        return new GeminiOutcome(text, functionName);
    }

    /** expected_params is a bare, comma-separated list of parameter names
     *  (e.g. "job_description") rather than a schema — every declared
     *  parameter is modeled as a required string. */
    private static Map<String, Object> toFunctionDeclaration(ToolDeclarationRequest t) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (t.expected_params() != null && !t.expected_params().isBlank()) {
            for (String param : t.expected_params().split(",")) {
                String name = param.trim();
                if (!name.isEmpty()) properties.put(name, Map.of("type", "STRING"));
            }
        }

        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("name", t.tag_name());
        declaration.put("description", t.description() == null ? "" : t.description());
        if (!properties.isEmpty()) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "OBJECT");
            parameters.put("properties", properties);
            parameters.put("required", new ArrayList<>(properties.keySet()));
            declaration.put("parameters", parameters);
        }
        return declaration;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static RunScreeningResponse reply(String text) {
        return new RunScreeningResponse(text, null);
    }

    private static UUID parseJobIdOrNull(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            return UUID.fromString(sessionId.trim());
        } catch (IllegalArgumentException e) {
            return null; // e.g. the frontend's "local_react_user" legacy default
        }
    }
}

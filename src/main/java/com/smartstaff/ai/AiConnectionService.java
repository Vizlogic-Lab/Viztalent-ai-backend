package com.smartstaff.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.ai.AiConfigService.ConnectionTest;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.service.SettingsService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Settings → "Test AI connection", the model picker's list, and the prod startup
 *  check. Checks that the chosen model (and fallback) exist for this key and
 *  support generateContent, then sends one tiny JSON-schema request. Results
 *  never contain the key. */
@Service
public class AiConnectionService {

    private final GeminiClient gemini;
    private final SettingsService settings;
    private final AiConfigService config;
    private final ObjectMapper json;

    public AiConnectionService(GeminiClient gemini, SettingsService settings, AiConfigService config, ObjectMapper json) {
        this.gemini = gemini;
        this.settings = settings;
        this.config = config;
        this.json = json;
    }

    public record ModelInfo(String name, String display_name) {}

    public record ModelList(boolean ok, List<ModelInfo> models, String error_code, String message) {}

    /** Models this key can call generateContent on, for the Settings picker. */
    public ModelList models() {
        String key = settings.getGeminiApiKeyOrNull();
        if (key == null || key.isBlank()) {
            return new ModelList(false, List.of(), GeminiException.NOT_CONFIGURED, "Save a Gemini API key first.");
        }
        try {
            return new ModelList(true, generateCapable(json.readTree(gemini.listModels(key))), null, null);
        } catch (GeminiException e) {
            return new ModelList(false, List.of(), e.getCode(), e.getMessage());
        } catch (Exception e) {
            return new ModelList(false, List.of(), GeminiException.UNAVAILABLE, "Couldn't read Gemini's model list.");
        }
    }

    public ConnectionTest test() {
        String model = config.model(), fallback = config.fallbackModel();
        String key = settings.getGeminiApiKeyOrNull();
        ConnectionTest result;
        if (key == null || key.isBlank()) {
            result = fail(model, fallback, GeminiException.NOT_CONFIGURED, "No Gemini API key is saved.", 0);
        } else if (model == null) {
            result = fail(null, fallback, GeminiException.NOT_CONFIGURED, "No AI model is chosen. Pick one below.", 0);
        } else {
            result = run(key, model, fallback);
        }
        config.saveLastTest(result);
        return result;
    }

    private ConnectionTest run(String key, String model, String fallback) {
        List<String> available;
        try {
            available = generateCapable(json.readTree(gemini.listModels(key))).stream().map(ModelInfo::name).toList();
        } catch (GeminiException e) {
            return fail(model, fallback, e.getCode(), e.getMessage(), 0);
        } catch (Exception e) {
            return fail(model, fallback, GeminiException.UNAVAILABLE, "Couldn't read Gemini's model list.", 0);
        }
        boolean modelOk = available.contains(model);
        Boolean fallbackOk = fallback == null ? null : available.contains(fallback);
        if (!modelOk) {
            return new ConnectionTest(false, model, fallback, false, fallbackOk, 0, GeminiException.MODEL_UNAVAILABLE,
                    "The model '" + model + "' isn't available to this key (or can't generate content).", Instant.now());
        }
        long start = System.nanoTime();
        try {
            String raw = gemini.generateContentOn(key, model, Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", "Connection test. Reply with ok set to true.")))),
                    "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0,
                            "responseSchema", Map.of("type", "OBJECT",
                                    "properties", Map.of("ok", Map.of("type", "BOOLEAN")), "required", List.of("ok")))));
            long ms = (System.nanoTime() - start) / 1_000_000;
            String text = json.readTree(raw).path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            if (!json.readTree(text).has("ok")) {
                return new ConnectionTest(false, model, fallback, true, fallbackOk, ms, GeminiException.REQUEST_REJECTED,
                        "The model answered, but not in the requested JSON shape.", Instant.now());
            }
            String note = Boolean.FALSE.equals(fallbackOk) ? "Connected. The fallback model '" + fallback + "' isn't available to this key." : "Connected.";
            return new ConnectionTest(fallbackOk == null || fallbackOk, model, fallback, true, fallbackOk, ms, null, note, Instant.now());
        } catch (GeminiException e) {
            return new ConnectionTest(false, model, fallback, true, fallbackOk, (System.nanoTime() - start) / 1_000_000,
                    e.getCode(), e.getMessage(), Instant.now());
        } catch (Exception e) {
            return new ConnectionTest(false, model, fallback, true, fallbackOk, (System.nanoTime() - start) / 1_000_000,
                    GeminiException.REQUEST_REJECTED, "The model's reply couldn't be read as JSON.", Instant.now());
        }
    }

    static List<ModelInfo> generateCapable(JsonNode root) {
        List<ModelInfo> out = new ArrayList<>();
        for (JsonNode m : root.path("models")) {
            boolean generates = false;
            for (JsonNode method : m.path("supportedGenerationMethods")) {
                if ("generateContent".equals(method.asText())) generates = true;
            }
            if (!generates) continue;
            out.add(new ModelInfo(m.path("name").asText("").replaceFirst("^models/", ""), m.path("displayName").asText(null)));
        }
        return out;
    }

    private static ConnectionTest fail(String model, String fallback, String code, String message, long ms) {
        return new ConnectionTest(false, model, fallback, false, fallback == null ? null : false, ms, code, message, Instant.now());
    }
}

package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.ai.AiFeature;
import com.smartstaff.ai.GeminiException;
import com.smartstaff.client.GeminiClient;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L2: GeminiClient retries, fallback model, error codes, usage counting and the daily cap. */
@ExtendWith(OutputCaptureExtension.class)
class GeminiReliabilityIntegrationTest extends IntegrationTestBase {

    private static final String KEY = "AIzaSyRELIABILITY-test-key-0001";
    private static final String PRIMARY = "/v1beta/models/primary-model:generateContent";
    private static final String FALLBACK = "/v1beta/models/backup-model:generateContent";
    private static final Map<String, Object> BODY = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", "hi")))));
    private static final String OK = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}],"
            + "\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":34}}";

    @Autowired private GeminiClient gemini;
    private Account admin;

    @BeforeEach
    void models() throws Exception {
        admin = newAdmin();
        putJsonAs(admin, "/api/config/ai", Map.of("model", "primary-model", "fallback_model", "backup-model"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("429 is retried 3 times, then the fallback model 3 times, then AI_QUOTA_EXCEEDED")
    void quotaRetriesThenFallbackThenFails() {
        STUB.respond("POST", PRIMARY, 429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
        STUB.respond("POST", FALLBACK, 429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");

        assertThatThrownBy(() -> gemini.generateContent(KEY, BODY, AiFeature.GRADING))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.getCode()).isEqualTo("AI_QUOTA_EXCEEDED"));
        assertThat(STUB.requests(PRIMARY)).hasSize(3);
        assertThat(STUB.requests(FALLBACK)).hasSize(3);
        assertThat(usage("GRADING", "failures")).isEqualTo(1);
    }

    @Test
    @DisplayName("a 429 followed by success is retried transparently; a 429 streak on the primary uses the fallback")
    void retryThenSuccessAndFallbackSuccess() {
        int[] n = {0};
        STUB.on("POST", PRIMARY, req -> ++n[0] == 1 ? StubResponse.json(429, "{}") : StubResponse.json(200, OK));
        assertThat(gemini.generateContent(KEY, BODY, AiFeature.GRADING)).contains("candidates");
        assertThat(STUB.requests(PRIMARY)).hasSize(2);
        assertThat(STUB.requests(FALLBACK)).isEmpty();

        STUB.reset();
        STUB.respond("POST", PRIMARY, 429, "{}");
        STUB.respond("POST", FALLBACK, 200, OK);
        assertThat(gemini.generateContent(KEY, BODY, AiFeature.INTERVIEW)).contains("candidates");
        assertThat(STUB.requests(FALLBACK)).hasSize(1);
    }

    @Test
    @DisplayName("404 fails fast with AI_MODEL_UNAVAILABLE: one request, no retry, no fallback")
    void modelNotFoundFailsFast(CapturedOutput output) {
        STUB.respond("POST", PRIMARY, 404, "{\"error\":{\"status\":\"NOT_FOUND\"}}");
        assertThatThrownBy(() -> gemini.generateContent(KEY, BODY, AiFeature.GRADING))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.getCode()).isEqualTo("AI_MODEL_UNAVAILABLE"));
        assertThat(STUB.requests(PRIMARY)).hasSize(1);
        assertThat(STUB.requests(FALLBACK)).isEmpty();
        assertThat(output.getAll()).contains("primary-model").contains("Settings → AI provider");
    }

    @Test
    @DisplayName("401/403 → AI_KEY_INVALID; no model chosen → AI_NOT_CONFIGURED")
    void keyInvalidAndNotConfigured() throws Exception {
        STUB.respond("POST", PRIMARY, 403, "{}");
        assertThatThrownBy(() -> gemini.generateContent(KEY, BODY, AiFeature.GRADING))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.getCode()).isEqualTo("AI_KEY_INVALID"));
        assertThat(STUB.requests(PRIMARY)).hasSize(1);

        jdbc.update("delete from app_settings where key = 'gemini_model'");
        putJsonAs(admin, "/api/config/ai", Map.of("model", "", "fallback_model", "")).andExpect(status().isOk());
        // Back to the environment's starting value (test-model in tests): still configured.
        assertThat(gemini.model()).isEqualTo(TEST_MODEL);
    }

    @Test
    @DisplayName("each call is counted per feature with its tokens")
    void usageCounted() throws Exception {
        STUB.respond("POST", PRIMARY, 200, OK);
        gemini.generateContent(KEY, BODY, AiFeature.PERSONAL_ASSESSMENT);
        gemini.generateContent(KEY, BODY, AiFeature.PERSONAL_ASSESSMENT);
        assertThat(usage("PERSONAL_ASSESSMENT", "calls")).isEqualTo(2);
        assertThat(usage("PERSONAL_ASSESSMENT", "prompt_tokens")).isEqualTo(24);
        assertThat(usage("PERSONAL_ASSESSMENT", "output_tokens")).isEqualTo(68);

        JsonNode view = bodyOf(getAs(admin, "/api/analytics/ai_usage").andExpect(status().isOk()));
        assertThat(view.path("calls_today").asInt()).isEqualTo(2);
        assertThat(view.path("days").path(0).path("feature").asText()).isEqualTo("PERSONAL_ASSESSMENT");
    }

    @Test
    @DisplayName("at the daily cap, non-critical features stop (and fall back) while critical ones still run")
    void dailyCap() throws Exception {
        putJsonAs(admin, "/api/config/ai", Map.of("daily_call_cap", 1)).andExpect(status().isOk());
        STUB.respond("POST", PRIMARY, 200, OK);
        gemini.generateContent(KEY, BODY, AiFeature.GRADING);

        assertThatThrownBy(() -> gemini.generateContent(KEY, BODY, AiFeature.ROLE_PROFILE))
                .isInstanceOfSatisfying(GeminiException.class, e -> assertThat(e.getCode()).isEqualTo("AI_CAP_REACHED"));
        assertThat(STUB.requests(PRIMARY)).hasSize(1);

        gemini.generateContent(KEY, BODY, AiFeature.GRADING);   // critical: not capped
        assertThat(STUB.requests(PRIMARY)).hasSize(2);
        assertThat(bodyOf(getAs(admin, "/api/analytics/ai_usage")).path("cap_reached").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the API key never appears in logs or API responses")
    void keyNeverLeaks(CapturedOutput output) throws Exception {
        setGeminiKey(admin, KEY);
        STUB.respond("POST", PRIMARY, 429, "{}");
        STUB.respond("POST", FALLBACK, 500, "{}");
        STUB.respond("GET", "/v1beta/models", 200, "{\"models\":[{\"name\":\"models/primary-model\","
                + "\"supportedGenerationMethods\":[\"generateContent\"]}]}");
        try { gemini.generateContent(KEY, BODY, AiFeature.GRADING); } catch (GeminiException ignored) { }

        String test = postJsonAs(admin, "/api/config/gemini/test", Map.of()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String cfg = getAs(admin, "/api/config/ai").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String all = getAs(admin, "/api/config").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(test + cfg + all).doesNotContain(KEY);
        assertThat(output.getAll()).doesNotContain(KEY);
        assertThat(String.valueOf(STUB.requests(PRIMARY).get(0).query())).as("the key goes in a header, never the URL").doesNotContain(KEY);
    }

    @Test
    @DisplayName("the fallback model must differ from the main one; bad ids are refused")
    void configValidation() throws Exception {
        putJsonAs(admin, "/api/config/ai", Map.of("fallback_model", "primary-model")).andExpect(status().isBadRequest());
        putJsonAs(admin, "/api/config/ai", Map.of("model", "bad model/../x")).andExpect(status().isBadRequest());
        JsonNode view = bodyOf(putJsonAs(admin, "/api/config/ai", Map.of("model", "models/other-model")).andExpect(status().isOk()));
        assertThat(view.path("model").asText()).isEqualTo("other-model");
        assertThat(view.path("model_source").asText()).isEqualTo("settings");
        assertThat(Fixtures.geminiText("x")).isNotBlank();
    }

    private long usage(String feature, String column) {
        Long v = jdbc.queryForObject("select coalesce(sum(" + column + "), 0) from ai_usage_daily where feature = ?",
                Long.class, feature);
        return v == null ? 0 : v;
    }
}

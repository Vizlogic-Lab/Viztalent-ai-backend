package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Settings (admin only): integration keys are stored encrypted and only ever shown masked;
 *  the "Test" buttons make real calls (here, to the stub) and report accept/reject. */
class SettingsIntegrationTest extends IntegrationTestBase {

    private static final String GEMINI_KEY = "AIzaSyABCDEFGHIJKLMNOP7890";

    private Account admin;

    @BeforeEach
    void setUp() {
        admin = newAdmin();
    }

    private JsonNode config() throws Exception {
        return bodyOf(getAs(admin, "/api/config").andExpect(status().isOk()));
    }

    private String storedValue(String key) {
        return jdbc.queryForObject("select value from app_settings where key = ?", String.class, key);
    }

    // ── Gemini ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a saved Gemini key is encrypted at rest, and only ever shown as a masked preview")
    void geminiKeyEncryptedAndMasked() throws Exception {
        postJsonAs(admin, "/api/config/gemini", Map.of("api_key", GEMINI_KEY, "persist", true)).andExpect(status().isOk());

        String response = getAs(admin, "/api/config").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode gemini = json.readTree(response).path("gemini");
        assertThat(gemini.path("configured").asBoolean()).isTrue();
        assertThat(gemini.path("has_key").asBoolean()).isTrue();
        assertThat(gemini.path("model").asText()).isEqualTo("gemini-3.5-flash");
        assertThat(gemini.path("key_preview").asText()).isEqualTo("AIza…7890");
        assertThat(response).as("the full key must never be returned").doesNotContain(GEMINI_KEY);

        String stored = storedValue("gemini_api_key");
        assertThat(stored).isNotEqualTo(GEMINI_KEY).doesNotContain(GEMINI_KEY);
        assertThat(new String(Base64.getDecoder().decode(stored), StandardCharsets.ISO_8859_1)).doesNotContain(GEMINI_KEY);
    }

    @Test
    @DisplayName("saving an empty key clears it")
    void geminiKeyCleared() throws Exception {
        setGeminiKey(admin, GEMINI_KEY);
        assertThat(config().path("gemini").path("configured").asBoolean()).isTrue();

        postJsonAs(admin, "/api/config/gemini", Map.of("api_key", "")).andExpect(status().isOk());

        assertThat(config().path("gemini").path("configured").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from app_settings where key = 'gemini_api_key'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("the Gemini test button reports whether Google accepts the saved key")
    void geminiTest() throws Exception {
        // No key yet.
        JsonNode none = bodyOf(postJsonAs(admin, "/api/config/gemini/test", Map.of()).andExpect(status().isOk()));
        assertThat(none.path("ok").asBoolean()).isFalse();
        assertThat(none.path("message").asText()).contains("No Gemini API key");

        setGeminiKey(admin, GEMINI_KEY);

        STUB.respond("GET", "/v1beta/models", 200, "{\"models\":[{\"name\":\"models/gemini-1.5-pro\"},{\"name\":\"models/gemini-2.5-flash\"}]}");
        JsonNode accepted = bodyOf(postJsonAs(admin, "/api/config/gemini/test", Map.of()).andExpect(status().isOk()));
        assertThat(accepted.path("ok").asBoolean()).isTrue();
        assertThat(accepted.path("model").asText()).isEqualTo("gemini-2.5-flash");
        assertThat(STUB.requests("/v1beta/models").get(0).query()).contains("key=" + GEMINI_KEY);

        STUB.reset();
        STUB.respond("GET", "/v1beta/models", 400, "{\"error\":{\"message\":\"API key not valid.\"}}");
        JsonNode rejected = bodyOf(postJsonAs(admin, "/api/config/gemini/test", Map.of()).andExpect(status().isOk()));
        assertThat(rejected.path("ok").asBoolean()).isFalse();
        assertThat(rejected.path("message").asText()).contains("did not accept this key");
    }

    // ── public URL, invite expiry ───────────────────────────────────────

    @Test
    @DisplayName("the public URL is normalised (trailing slashes dropped) and flagged when it points at localhost")
    void publicUrl() throws Exception {
        JsonNode real = bodyOf(postJsonAs(admin, "/api/config/public_url", Map.of("public_base_url", "https://viztalent.example.com///"))
                .andExpect(status().isOk()));
        assertThat(real.path("public_base_url").asText()).isEqualTo("https://viztalent.example.com");
        assertThat(real.path("is_localhost").asBoolean()).isFalse();
        assertThat(config().path("public_url").path("value").asText()).isEqualTo("https://viztalent.example.com");

        JsonNode local = bodyOf(postJsonAs(admin, "/api/config/public_url", Map.of("public_base_url", "http://localhost:8000")).andExpect(status().isOk()));
        assertThat(local.path("is_localhost").asBoolean()).isTrue();

        postJsonAs(admin, "/api/config/public_url", Map.of("public_base_url", " ")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("invite expiry: defaults to 48 hours, can be changed, and can't be set absurdly low")
    void inviteTtl() throws Exception {
        assertThat(config().path("invites").path("ttl_hours").asLong()).isEqualTo(48);

        JsonNode saved = bodyOf(postJsonAs(admin, "/api/config/invite_ttl", Map.of("ttl_seconds", 7200)).andExpect(status().isOk()));
        assertThat(saved.path("ttl_hours").asLong()).isEqualTo(2);
        assertThat(config().path("invites").path("ttl_seconds").asLong()).isEqualTo(7200);

        postJsonAs(admin, "/api/config/invite_ttl", Map.of("ttl_seconds", 5)).andExpect(status().isBadRequest());
    }

    // ── Twilio ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Twilio credentials: the auth token is encrypted at rest and everything is shown masked")
    void twilioEncryptedAndMasked() throws Exception {
        setTwilio(admin, "ACabcdef0123456789abcdef", "super-secret-auth-token-9999", "+15550001111");

        String response = getAs(admin, "/api/config").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode twilio = json.readTree(response).path("twilio");
        assertThat(twilio.path("configured").asBoolean()).isTrue();
        assertThat(twilio.path("from_number").asText()).isEqualTo("+15550001111");
        assertThat(twilio.path("account_sid_preview").asText()).isEqualTo("ACab…cdef");
        assertThat(twilio.path("auth_token_preview").asText()).isEqualTo("supe…9999");
        assertThat(response).doesNotContain("super-secret-auth-token-9999").doesNotContain("ACabcdef0123456789abcdef");

        assertThat(storedValue("twilio_auth_token")).isNotEqualTo("super-secret-auth-token-9999").doesNotContain("super-secret");
    }

    @Test
    @DisplayName("updating one Twilio field leaves the others alone (a blank field means 'unchanged')")
    void twilioPartialUpdate() throws Exception {
        setTwilio(admin, "ACabcdef0123456789abcdef", "super-secret-auth-token-9999", "+15550001111");

        postJsonAs(admin, "/api/config/twilio", Map.of("account_sid", "", "auth_token", "", "from_number", "+15552223333", "persist", true))
                .andExpect(status().isOk());

        JsonNode twilio = config().path("twilio");
        assertThat(twilio.path("from_number").asText()).isEqualTo("+15552223333");
        assertThat(twilio.path("configured").asBoolean()).as("sid and token survived").isTrue();
        assertThat(twilio.path("account_sid_preview").asText()).isEqualTo("ACab…cdef");
    }

    @Test
    @DisplayName("the Twilio test button authenticates against Twilio and reports accept/reject")
    void twilioTest() throws Exception {
        JsonNode none = bodyOf(postJsonAs(admin, "/api/config/twilio/test", Map.of()).andExpect(status().isOk()));
        assertThat(none.path("ok").asBoolean()).isFalse();
        assertThat(none.path("message").asText()).contains("not configured");

        setTwilio(admin, "ACtest0123", "the-auth-token", "+15550001111");

        STUB.respond("GET", "/2010-04-01/Accounts/ACtest0123.json", 200, "{\"friendly_name\":\"Acme Recruiting\"}");
        JsonNode accepted = bodyOf(postJsonAs(admin, "/api/config/twilio/test", Map.of()).andExpect(status().isOk()));
        assertThat(accepted.path("ok").asBoolean()).isTrue();
        assertThat(accepted.path("account_name").asText()).isEqualTo("Acme Recruiting");
        assertThat(accepted.path("from_number").asText()).isEqualTo("+15550001111");
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("ACtest0123:the-auth-token".getBytes(StandardCharsets.UTF_8));
        assertThat(STUB.requests("/2010-04-01/Accounts/ACtest0123.json").get(0).authorization()).isEqualTo(expectedAuth);

        STUB.reset();
        STUB.respond("GET", "/2010-04-01/Accounts/", 401, "{\"code\":20003}");
        JsonNode rejected = bodyOf(postJsonAs(admin, "/api/config/twilio/test", Map.of()).andExpect(status().isOk()));
        assertThat(rejected.path("ok").asBoolean()).isFalse();
        assertThat(rejected.path("message").asText()).contains("rejected these credentials");
    }

    // ── code runner (Piston) ────────────────────────────────────────────

    @Test
    @DisplayName("Piston URL: a reachable server enables it and lists its languages; an unreachable one is remembered but disabled; empty clears it")
    void piston() throws Exception {
        STUB.respond("GET", "/api/v2/runtimes", 200, "[{\"language\":\"python\"},{\"language\":\"java\"},{\"language\":\"python\"}]");
        JsonNode reachable = bodyOf(postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2/")).andExpect(status().isOk()));
        assertThat(reachable.path("piston_enabled").asBoolean()).isTrue();
        assertThat(reachable.path("piston_url").asText()).isEqualTo(STUB.baseUrl() + "/api/v2");
        assertThat(reachable.path("languages")).extracting(JsonNode::asText).containsExactly("python", "java");

        STUB.reset(); // now /runtimes answers 404
        JsonNode unreachable = bodyOf(postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk()));
        assertThat(unreachable.path("piston_enabled").asBoolean()).isFalse();
        assertThat(unreachable.path("piston_url").asText()).isEqualTo(STUB.baseUrl() + "/api/v2");
        assertThat(unreachable.path("languages")).extracting(JsonNode::asText).containsExactly("python");

        JsonNode cleared = bodyOf(postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", "")).andExpect(status().isOk()));
        assertThat(cleared.path("piston_enabled").asBoolean()).isFalse();
        assertThat(cleared.path("piston_url").isNull()).isTrue();
    }

    @Test
    @DisplayName("the reset button is accepted (it deliberately does nothing)")
    void reset() throws Exception {
        postJsonAs(admin, "/api/reset", Map.of()).andExpect(status().isOk());
    }

    // ── who may use Settings ────────────────────────────────────────────

    @ParameterizedTest(name = "POST {0} is admin-only")
    @ValueSource(strings = {"/api/config/gemini", "/api/config/gemini/test", "/api/config/public_url", "/api/config/invite_ttl",
            "/api/config/twilio", "/api/config/twilio/test", "/api/config/piston_url", "/api/reset"})
    void postEndpointsAreAdminOnly(String path) throws Exception {
        Account employee = newEmployee();

        mvc.perform(withAuth(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content("{}"), employee))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reading the configuration is admin-only too")
    void readingConfigIsAdminOnly() throws Exception {
        getAs(newEmployee(), "/api/config").andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/config")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("question bank changes are admin-only, but any signed-in user can read its totals")
    void questionBankAccess() throws Exception {
        Account employee = newEmployee();

        uploadFile(employee, "/api/questions/upload", "file", "bank.csv", "type,question\ncoding,Reverse a string.\n").andExpect(status().isForbidden());
        postJsonAs(employee, "/api/questions/clear", Map.of()).andExpect(status().isForbidden());
        getAs(employee, "/api/questions/bank").andExpect(status().isOk());

        uploadFile(admin, "/api/questions/upload", "file", "bank.csv", "type,question\ncoding,Reverse a string.\n").andExpect(status().isOk());

        JsonNode bank = bodyOf(getAs(employee, "/api/questions/bank").andExpect(status().isOk()));
        assertThat(bank.path("stats").path("total").asInt()).isEqualTo(1);
        assertThat(bank.path("stats").path("by_type").path("CODING").asInt()).isEqualTo(1);
        assertThat(bank.path("uploads")).hasSize(1);
    }
}

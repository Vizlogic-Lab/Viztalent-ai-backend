package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.service.SettingsService;
import com.smartstaff.service.impl.EmailSettingsService;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L1: GEMINI_API_KEY / SMTP_PASSWORD from the environment win over Settings and are only shown masked. */
@TestPropertySource(properties = {
        "app.gemini.api-key=env-gemini-test-key-0000abcd",
        "app.smtp.password=env-smtp-test-password-9999"
})
class L1EnvOverrideIntegrationTest extends IntegrationTestBase {

    @Autowired SettingsService settings;
    @Autowired EmailSettingsService email;

    @Test
    @DisplayName("the env Gemini key overrides a saved one and is never returned in full")
    void envGeminiKeyWins() throws Exception {
        Account admin = newAdmin();
        setGeminiKey(admin, "fake-saved-gemini-key-tests-wxyz");
        String raw = getAs(admin, "/api/config").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode gemini = json.readTree(raw).path("gemini");
        assertThat(gemini.path("key_source").asText()).isEqualTo("environment");
        assertThat(gemini.path("key_preview").asText()).isEqualTo("****abcd");
        assertThat(raw).doesNotContain("env-gemini-test-key-0000abcd");
        assertThat(settings.getGeminiApiKeyOrNull()).isEqualTo("env-gemini-test-key-0000abcd");
    }

    @Test
    @DisplayName("the env SMTP password overrides the saved one and is shown masked")
    void envSmtpPasswordWins() throws Exception {
        Account admin = newAdmin();
        postJsonAs(admin, "/api/config/smtp", Map.of("host", "smtp.example.com", "port", 587, "username", "u",
                "password", "saved-smtp-password-1", "from_address", "hr@example.com", "tls", true)).andExpect(status().isOk());
        assertThat(email.smtpOrNull().password()).isEqualTo("env-smtp-test-password-9999");
        String raw = getAs(admin, "/api/config/smtp").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("env-smtp-test-password-9999").contains("\"password_source\":\"environment\"")
                .contains("****9999");
    }
}

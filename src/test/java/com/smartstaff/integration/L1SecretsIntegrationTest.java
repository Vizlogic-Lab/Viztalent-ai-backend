package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.service.SettingsService;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L1: forced password change for the bootstrap admin. */
class L1SecretsIntegrationTest extends IntegrationTestBase {

    @Autowired SettingsService settings;

    @Test
    @DisplayName("an account that must change its password can only reach me / change_password / logout until it does")
    void forcedPasswordChange() throws Exception {
        Account admin = newAdmin();
        jdbc.update("update users set must_change_password = true where id = ?", admin.user().getId());

        getAs(admin, "/api/config").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        getAs(admin, "/api/jobs").andExpect(status().isForbidden());
        JsonNode me = bodyOf(getAs(admin, "/api/auth/me").andExpect(status().isOk()));
        assertThat(me.toString()).contains("\"must_change_password\":true");

        // The same password again is refused; a new one clears the flag.
        postJsonAs(admin, "/api/auth/change_password", Map.of("current", "Passw0rd!", "new_password", "Passw0rd!"))
                .andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/auth/change_password", Map.of("current", "Passw0rd!", "new_password", "Brand-New-Pass-2026"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select must_change_password from users where id = ?", Boolean.class, admin.user().getId()))
                .isFalse();
        getAs(admin, "/api/config").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a saved Gemini key is reported as coming from Settings and masked as ****abcd")
    void savedKeyMasked() throws Exception {
        Account admin = newAdmin();
        setGeminiKey(admin, "fake-gemini-key-for-tests-wxyz");
        JsonNode gemini = bodyOf(getAs(admin, "/api/config").andExpect(status().isOk())).path("gemini");
        assertThat(gemini.path("key_preview").asText()).isEqualTo("****wxyz");
        assertThat(gemini.path("key_source").asText()).isEqualTo("settings");
        assertThat(settings.getGeminiApiKeyOrNull()).isEqualTo("fake-gemini-key-for-tests-wxyz");
    }
}

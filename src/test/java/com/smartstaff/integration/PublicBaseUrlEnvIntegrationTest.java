package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.service.SettingsService;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PUBLIC_BASE_URL (app.public-base-url): the address candidate links are built
 *  from when nothing is saved in Settings, so a fresh production deploy never
 *  hands out localhost links. A value saved in Settings still wins. */
@TestPropertySource(properties = "app.public-base-url=https://hire.example.com/")
class PublicBaseUrlEnvIntegrationTest extends IntegrationTestBase {

    @Autowired SettingsService settingsService;

    @BeforeEach
    void nothingSaved() {
        jdbc.update("delete from app_settings where key = 'public_base_url'");
    }

    @Test
    @DisplayName("with nothing saved, links use PUBLIC_BASE_URL (trailing slash trimmed), not localhost")
    void envUsedWhenNothingSaved() throws Exception {
        assertThat(settingsService.getPublicBaseUrl()).isEqualTo("https://hire.example.com");

        JsonNode publicUrl = bodyOf(getAs(newAdmin(), "/api/config").andExpect(status().isOk())).path("public_url");
        assertThat(publicUrl.path("value").asText()).isEqualTo("https://hire.example.com");
        assertThat(publicUrl.path("is_localhost").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a URL saved in Settings overrides PUBLIC_BASE_URL")
    void savedSettingWins() throws Exception {
        postJsonAs(newAdmin(), "/api/config/public_url", Map.of("public_base_url", "https://other.example.org"))
                .andExpect(status().isOk());

        assertThat(settingsService.getPublicBaseUrl()).isEqualTo("https://other.example.org");
    }
}

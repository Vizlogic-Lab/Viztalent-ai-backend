package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F3 — the admin "test the code runner" endpoint, against a stubbed Piston. */
class CodeRunnerIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("POST /api/config/piston/test runs hello-world per language and reports which work")
    void selfTest() throws Exception {
        Account admin = newAdmin();
        STUB.respond("GET", "/api/v2/runtimes", 200,
                "[{\"language\":\"python\",\"version\":\"3.12.0\",\"aliases\":[\"py\"]},"
                        + "{\"language\":\"java\",\"version\":\"15.0.2\",\"aliases\":[]}]");
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
        STUB.respond("POST", "/api/v2/execute", 200, Fixtures.pistonRun("hello\n"));

        JsonNode result = bodyOf(postJsonAs(admin, "/api/config/piston/test", Map.of()).andExpect(status().isOk()));

        assertThat(result.path("ok").asBoolean()).as("javascript and cpp aren't installed").isFalse();
        assertThat(result.path("piston_url").asText()).isEqualTo(STUB.baseUrl() + "/api/v2");
        Map<String, Boolean> byLanguage = new java.util.LinkedHashMap<>();
        result.path("languages").forEach(l -> byLanguage.put(l.path("language").asText(), l.path("ok").asBoolean()));
        assertThat(byLanguage).containsExactly(
                Map.entry("java", true), Map.entry("python", true), Map.entry("javascript", false), Map.entry("cpp", false));
        assertThat(STUB.requests("/api/v2/execute")).hasSize(2);
    }

    @Test
    @DisplayName("without a Piston URL every language reports the runner isn't configured")
    void notConfigured() throws Exception {
        JsonNode result = bodyOf(postJsonAs(newAdmin(), "/api/config/piston/test", Map.of()).andExpect(status().isOk()));
        assertThat(result.path("ok").asBoolean()).isFalse();
        result.path("languages").forEach(l -> assertThat(l.path("message").asText()).contains("isn't configured"));
    }

    @Test
    @DisplayName("only admins can run the code-runner test")
    void adminOnly() throws Exception {
        postJsonAs(newEmployee(), "/api/config/piston/test", Map.of()).andExpect(status().isForbidden());
    }
}

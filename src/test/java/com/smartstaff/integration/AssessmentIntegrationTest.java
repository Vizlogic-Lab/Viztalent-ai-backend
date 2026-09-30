package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssessmentIntegrationTest extends IntegrationTestBase {

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Backend Engineer", "java, docker");
        setPublicBaseUrl(admin, "https://public.example.com");
    }

    private JsonNode generate(String source) throws Exception {
        return bodyOf(postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", job, "question_source", source))
                .andExpect(status().isOk()));
    }

    @Test
    @DisplayName("a job with no assessment reports zero questions, and its answer key is a 404")
    void noAssessmentYet() throws Exception {
        getAs(admin, "/api/assessment/status/" + job)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.num_questions").value(0))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.generating").value(false));
        getAs(admin, "/api/assessment/answer_key/" + job).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("with an empty bank and no Gemini key there is nothing to build from, and it says so without queuing")
    void nothingToBuildFrom() throws Exception {
        JsonNode result = generate("custom");

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("message").asText()).contains("No questions available");
        assertThat(jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid", Integer.class, job)).isZero();
    }

    @Test
    @DisplayName("an unknown source, level or job is rejected clearly")
    void badRequests() throws Exception {
        JsonNode unknownSource = generate("bogus");
        assertThat(unknownSource.path("status").asText()).isEqualTo("error");
        assertThat(unknownSource.path("message").asText()).contains("Unknown question source");

        JsonNode badLevel = bodyOf(postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "custom", "levels", List.of("L9"))).andExpect(status().isOk()));
        assertThat(badLevel.path("message").asText()).contains("L1, L2 and/or L3");

        postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", "00000000-0000-0000-0000-000000000000", "question_source", "ai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("upload a JD first")));
        postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", job))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ai source without a Gemini key is refused up front, without calling Gemini")
    void aiNeedsKey() throws Exception {
        JsonNode result = generate("ai");

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("message").asText()).contains("No Gemini API key");
        assertThat(STUB.requests("/v1beta/")).isEmpty();
    }

    @Test
    @DisplayName("the answer key serves the current READY version")
    void answerKeyServesCurrentVersion() throws Exception {
        seedReadyAssessment(job, "L1", "L2");

        JsonNode key = bodyOf(getAs(admin, "/api/assessment/answer_key/" + job).andExpect(status().isOk()));
        assertThat(key.path("role_title").asText()).isEqualTo("Backend Engineer");
        assertThat(key.path("levels").get(0).path("count").asInt()).isEqualTo(1);
        assertThat(key.path("levels").get(0).path("questions").get(0).path("correct_index").asInt()).isZero();
        assertThat(key.path("levels").get(2).path("count").asInt()).isZero();
    }

    // ── invites ─────────────────────────────────────────────────────────

    private JsonNode mint(Object body) throws Exception {
        if (jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid and is_current", Integer.class, job) == 0) {
            seedReadyAssessment(job, "L1", "L2", "L3");
        }
        return bodyOf(postJsonAs(admin, "/api/invites/mint", body).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("minting per level returns one single-use link per level, valid for the configured TTL")
    void mintPerLevel() throws Exception {
        JsonNode result = mint(Map.of("session_id", job, "candidate_email", "jane@example.com", "candidate_name", "Jane",
                "levels", List.of("L1", "L2", "L3")));

        assertThat(result.path("ok").asBoolean()).isTrue();
        JsonNode invites = result.path("invites");
        assertThat(invites).hasSize(3);
        for (int i = 0; i < 3; i++) {
            String level = "L" + (i + 1);
            assertThat(invites.get(i).path("level").asText()).isEqualTo(level);
            assertThat(invites.get(i).path("url").asText())
                    .matches("https://public\\.example\\.com/assessment/JD-\\d+\\?level=" + level + "&token=[A-Za-z0-9_-]{40,}");
            assertThat(invites.get(i).path("remaining_seconds").asLong()).isEqualTo(48 * 3600);
        }
    }

    @Test
    @DisplayName("the invite TTL comes from Settings")
    void mintHonoursConfiguredTtl() throws Exception {
        postJsonAs(admin, "/api/config/invite_ttl", Map.of("ttl_seconds", 3600)).andExpect(status().isOk());

        JsonNode result = mint(Map.of("session_id", job, "candidate_email", "jane@example.com", "levels", List.of("L1")));

        assertThat(result.path("invites").get(0).path("remaining_seconds").asLong()).isEqualTo(3600);
    }

    @Test
    @DisplayName("combined minting returns ONE link covering every listed level")
    void mintCombined() throws Exception {
        JsonNode result = mint(Map.of("session_id", job, "candidate_email", "jane@example.com",
                "levels", List.of("L1", "L2"), "combined", true));

        assertThat(result.path("invites")).hasSize(1);
        assertThat(result.path("invites").get(0).path("url").asText()).contains("levels=L1,L2&token=");
        assertThat(jdbc.queryForObject("select combined from invites where candidate_email = 'jane@example.com' and job_id = ?::uuid",
                Boolean.class, job)).isTrue();
    }

    @Test
    @DisplayName("only a SHA-256 hash of each token is stored — never the token itself")
    void tokensStoredHashed() throws Exception {
        String email = "hash-" + shortId() + "@example.com"; // unique: the database is shared across test classes
        JsonNode result = mint(Map.of("session_id", job, "candidate_email", email, "levels", List.of("L1")));
        String url = result.path("invites").get(0).path("url").asText();
        String rawToken = url.substring(url.indexOf("token=") + "token=".length());

        String stored = jdbc.queryForObject("select token_hash from invites where candidate_email = ?", String.class, email);

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        assertThat(stored).isEqualTo(expected).isNotEqualTo(rawToken).hasSize(64);
        assertThat(jdbc.queryForObject("select count(*) from invites where token_hash = ?", Integer.class, rawToken)).isZero();
    }

    @Test
    @DisplayName("every mint yields a fresh token (tokens can't be re-served, since only their hash is kept)")
    void freshTokenEachTime() throws Exception {
        Map<String, Object> body = Map.of("session_id", job, "candidate_email", "jane@example.com", "levels", List.of("L1"));

        String first = mint(body).path("invites").get(0).path("url").asText();
        String second = mint(body).path("invites").get(0).path("url").asText();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("mint validates its input")
    void mintValidation() throws Exception {
        seedReadyAssessment(job, "L1");
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "levels", List.of("L1"))).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "candidate_email", "a@b.test", "levels", List.of())).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", "not-a-uuid", "candidate_email", "a@b.test", "levels", List.of("L1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("upload a JD first")));
    }
}

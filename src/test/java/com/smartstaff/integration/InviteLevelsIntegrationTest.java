package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A candidate with a readable resume gets a personal (resume-based) assessment only for a
 *  Level 1 link. Level 2, Level 3 and combined links use the job's shared assessment — they
 *  used to be silently turned into a Level 1 link, so HR picking Level 2 got no Level 2 link. */
class InviteLevelsIntegrationTest extends IntegrationTestBase {

    private static final String RESUME = "Kiran Das\nkiran.das@example.com\n6 years of experience in Java and Spring Boot.\n";

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring boot");
        seedReadyAssessment(job, "L1", "L2", "L3");
        uploadResumes(admin, job, "kiran.txt", RESUME).andExpect(status().isOk());
        postJsonAs(admin, "/api/run_screening", Map.of("session_id", job)).andExpect(status().isOk());
        setGeminiKey(admin, "AIza-test-key-123");
        STUB.reset();
        STUB.respond("POST", "/", 500, "{\"error\":{\"message\":\"down\"}}");   // any AI call fails (and is recorded)
    }

    private JsonNode mint(List<String> levels, boolean combined) throws Exception {
        return bodyOf(postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "candidate_email", "kiran.das@example.com",
                "candidate_name", "Kiran Das", "levels", levels, "combined", combined)).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("picking Level 2 or Level 3 gives a link for that level, with no AI call")
    void levelTwoAndThree() throws Exception {
        assertThat(mint(List.of("L2"), false).path("invites").get(0).path("level").asText()).isEqualTo("L2");
        assertThat(mint(List.of("L3"), false).path("invites").get(0).path("level").asText()).isEqualTo("L3");
        assertThat(mint(List.of("L1", "L2"), true).path("invites").get(0).path("level").asText()).isEqualTo("L1,L2");
        assertThat(STUB.requests("/")).as("no personal assessment is written for these").isEmpty();
    }

    @Test
    @DisplayName("Level 1 still tries the personal assessment first, and falls back to the shared one when the AI fails")
    void levelOneTriesPersonal() throws Exception {
        assertThat(mint(List.of("L1"), false).path("invites").get(0).path("level").asText()).isEqualTo("L1");
        assertThat(STUB.requests("/")).as("the personal assessment was attempted").isNotEmpty();
    }
}

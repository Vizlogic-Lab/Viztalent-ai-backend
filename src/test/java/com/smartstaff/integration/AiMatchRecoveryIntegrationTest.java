package com.smartstaff.integration;

import com.smartstaff.service.impl.AiMatchJob;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A restart during screening must not leave candidates stuck in the AI check. */
class AiMatchRecoveryIntegrationTest extends IntegrationTestBase {

    private static final String PRIYA = "Priya Nair\npriya.nair@example.com\n6 years of experience in Java and Spring Boot.\n";
    private static final String ASHA = "Asha Verma\nasha.verma@example.com\n1 year of experience in Python and React.\n";

    @Autowired AiMatchJob aiMatchJob;

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring boot, postgresql");
        uploadResumes(admin, job, "priya.txt", PRIYA, "asha.txt", ASHA).andExpect(status().isOk());
        postJsonAs(admin, "/api/run_screening", Map.of("session_id", job)).andExpect(status().isOk());
    }

    private List<String> statuses() {
        return jdbc.queryForList("select ai_match_status from candidates where job_id = ?::uuid", String.class, job);
    }

    @Test
    @DisplayName("screening finishes the AI check: nothing is left PENDING")
    void screeningLeavesNothingPending() {
        assertThat(statuses()).hasSize(2).doesNotContain(AiMatchJob.PENDING);
    }

    @Test
    @DisplayName("checks left PENDING by a restart are run again by the sweep, once")
    void sweepResumesPendingChecks() {
        // Simulate a restart in the middle of the check.
        jdbc.update("update candidates set ai_match_status = 'PENDING' where job_id = ?::uuid", job);
        assertThat(statuses()).containsOnly(AiMatchJob.PENDING);

        assertThat(aiMatchJob.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(statuses()).hasSize(2).doesNotContain(AiMatchJob.PENDING);

        // A second sweep has nothing left to resume for this job.
        List<String> after = statuses();
        aiMatchJob.sweep();
        assertThat(statuses()).containsExactlyInAnyOrderElementsOf(after);
    }
}

package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One shared link for a screened group: the roster is a snapshot of the job's scored candidates,
 *  the link is reused when asked for again, and a candidate gets their own invite only when the
 *  email and name they give match the roster. No email is sent and no AI is involved. */
class ScreeningBatchIntegrationTest extends IntegrationTestBase {

    private static final String PRIYA = "Priya Nair\npriya.nair@example.com\n7 years of experience in Java, Spring Boot and PostgreSQL.\n";
    private static final String RAVI = "Ravi Kumar\nravi.kumar@example.com\n6 years of experience. Skills: Java and Spring Boot.\n";
    private static final String ASHA = "Asha Verma\nasha.verma@example.com\n1 year of experience in Python and React.\n";
    private static final String NO_EMAIL = "Nina Roy\n3 years of experience in Java, Spring Boot and PostgreSQL.\n";

    private Account admin;
    private String job;
    private String assessmentId;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring boot, postgresql");
        setPublicBaseUrl(admin, "https://public.example.com");
        assessmentId = seedReadyAssessment(job, "L1", "L2");
        uploadResumes(admin, job, "priya.txt", PRIYA, "ravi.txt", RAVI, "asha.txt", ASHA, "nina.txt", NO_EMAIL)
                .andExpect(status().isOk());
        postJsonAs(admin, "/api/run_screening", Map.of("session_id", job)).andExpect(status().isOk());
    }

    private JsonNode createBatch(Map<String, Object> body) throws Exception {
        return bodyOf(postJsonAs(admin, "/api/jobs/" + job + "/batch", body).andExpect(status().isOk())).path("batch");
    }

    private static String tokenOf(JsonNode batch) {
        String url = batch.path("url").asText();
        return url.substring(url.indexOf("token=") + 6);
    }

    private ResultActions enter(String token, String email, String name) throws Exception {
        return postJson("/api/candidate/batch/" + token + "/enter", Map.of("email", email, "name", name));
    }

    private int invitesFor(String email) {
        return jdbc.queryForObject("select count(*) from invites where job_id = ?::uuid and candidate_email = ?",
                Integer.class, job, email);
    }

    @Test
    @DisplayName("the batch lists every screened candidate with a score, marks who clears the minimum, and reports resumes with no email")
    void rosterAndScores() throws Exception {
        JsonNode batch = createBatch(Map.of("min_score", 50));

        assertThat(batch.path("url").asText()).startsWith("https://public.example.com/batch/JD-");
        assertThat(batch.path("levels")).extracting(JsonNode::asText).containsExactly("L1", "L2");   // L3 isn't in the assessment
        assertThat(batch.path("roster")).hasSize(3);
        assertThat(batch.path("skipped_no_email")).extracting(JsonNode::asText).containsExactly("Nina Roy");
        for (JsonNode c : batch.path("roster")) {
            boolean strong = c.path("fit_score").asInt() >= 50;
            assertThat(c.path("included").asBoolean()).isEqualTo(strong);
        }
        JsonNode asha = null;
        for (JsonNode c : batch.path("roster")) if (c.path("email").asText().startsWith("asha")) asha = c;
        assertThat(asha).isNotNull();
        assertThat(asha.path("included").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("asking again returns the same link instead of creating another; force_new replaces it")
    void oneLink() throws Exception {
        JsonNode first = createBatch(Map.of("min_score", 50));
        JsonNode again = createBatch(Map.of("min_score", 50));
        assertThat(again.path("url").asText()).isEqualTo(first.path("url").asText());
        assertThat(again.path("reused").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from screening_batches where job_id = ?::uuid", Integer.class, job)).isEqualTo(1);

        JsonNode replaced = createBatch(Map.of("min_score", 50, "force_new", true));
        assertThat(replaced.path("url").asText()).isNotEqualTo(first.path("url").asText());
        // The old link no longer opens.
        mvc.perform(get("/api/candidate/batch/" + tokenOf(first))).andExpect(status().isGone());
        mvc.perform(get("/api/candidate/batch/" + tokenOf(replaced))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a job with no ready assessment can't get a batch link (nothing is generated for it)")
    void needsAssessment() throws Exception {
        jdbc.update("update assessments set is_current = false where job_id = ?::uuid", job);
        postJsonAs(admin, "/api/jobs/" + job + "/batch", Map.of()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid", Integer.class, job)).isEqualTo(1);
    }

    @Test
    @DisplayName("the email and name from the resume give the candidate their own link on the job's one shared assessment")
    void enterWithEmailAndName() throws Exception {
        String token = tokenOf(createBatch(Map.of("min_score", 50)));

        JsonNode entered = bodyOf(enter(token, "Priya.Nair@example.com", "Priya Nair").andExpect(status().isOk()));
        assertThat(entered.path("url").asText()).startsWith("https://public.example.com/assessment/JD-").contains("token=");
        assertThat(entered.path("path").asText()).startsWith("/assessment/JD-");
        assertThat(jdbc.queryForObject("select count(*) from invites where candidate_email = 'priya.nair@example.com' "
                + "and assessment_id = ?::uuid", Integer.class, assessmentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the name match ignores case, accents, punctuation, extra spaces and word order")
    void forgivingNameMatch() throws Exception {
        String token = tokenOf(createBatch(Map.of("min_score", 50)));
        enter(token, "ravi.kumar@example.com", "  KUMAR,  ravi ").andExpect(status().isOk());
        enter(token, "priya.nair@example.com", "Príya Nair").andExpect(status().isOk());
    }

    @Test
    @DisplayName("coming back replaces their link on the same invite, so nothing is duplicated")
    void reentryRotatesLink() throws Exception {
        String token = tokenOf(createBatch(Map.of("min_score", 50)));
        String first = bodyOf(enter(token, "ravi.kumar@example.com", "Ravi Kumar").andExpect(status().isOk())).path("url").asText();
        String second = bodyOf(enter(token, "ravi.kumar@example.com", "Ravi Kumar").andExpect(status().isOk())).path("url").asText();

        assertThat(second).isNotEqualTo(first);
        assertThat(invitesFor("ravi.kumar@example.com")).isEqualTo(1);
        // The first link's token no longer resolves to an invite.
        String oldToken = first.substring(first.indexOf("token=") + 6);
        mvc.perform(get("/api/candidate/assessment/" + oldToken)).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a wrong name, an unknown email and a below-minimum candidate all get the same refusal and no invite")
    void refusals() throws Exception {
        String token = tokenOf(createBatch(Map.of("min_score", 50)));

        String wrongName = bodyOf(enter(token, "priya.nair@example.com", "Priya Sharma").andExpect(status().isBadRequest()))
                .path("code").asText();
        String partName = bodyOf(enter(token, "priya.nair@example.com", "Priya").andExpect(status().isBadRequest()))
                .path("code").asText();
        String unknown = bodyOf(enter(token, "stranger@example.com", "Priya Nair").andExpect(status().isBadRequest()))
                .path("code").asText();
        String low = bodyOf(enter(token, "asha.verma@example.com", "Asha Verma").andExpect(status().isBadRequest()))
                .path("code").asText();
        String blank = bodyOf(enter(token, "priya.nair@example.com", "  ").andExpect(status().isBadRequest()))
                .path("code").asText();
        assertThat(java.util.List.of(wrongName, partName, unknown, low, blank)).containsOnly("not_on_list");
        assertThat(jdbc.queryForObject("select count(*) from invites where job_id = ?::uuid", Integer.class, job)).isZero();
    }
}

package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.service.CandidateAssessmentService;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F7 — the candidate attempt API under /api/candidate/assessment/{token}:
 *  start/resume, per-question autosave, run (visible tests only, capped),
 *  submit (exactly one wins), integrity events, deadline auto-submit, combined
 *  invites served level by level, and link messages matching interview links. */
class CandidateAttemptApiIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/candidate/assessment/";

    @Autowired CandidateAssessmentService candidateService;

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1", "L2"))).andExpect(status().isOk());
    }

    private String mint(String email, List<String> levels, boolean combined) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("session_id", job, "candidate_email", email,
                "candidate_name", "Cand", "levels", levels));
        body.put("combined", combined);
        JsonNode res = bodyOf(postJsonAs(admin, "/api/invites/mint", body).andExpect(status().isOk()));
        String url = res.path("invites").get(0).path("url").asText();
        return url.substring(url.indexOf("token=") + 6);
    }

    private ResultActions start(String token) throws Exception {
        return mvc.perform(get(BASE + token));
    }

    private JsonNode started(String token) throws Exception {
        return bodyOf(start(token).andExpect(status().isOk()));
    }

    private ResultActions save(String token, String questionId, Map<String, Object> body) throws Exception {
        return mvc.perform(put(BASE + token + "/answers/" + questionId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private String questionOf(JsonNode view, String type) {
        for (JsonNode q : view.path("questions")) if (q.path("type").asText().equals(type)) return q.path("question_id").asText();
        throw new AssertionError("no " + type);
    }

    @Test
    @DisplayName("first open starts the attempt; reopening resumes the same one (idempotent)")
    void startResumeIdempotent() throws Exception {
        String token = mint("c1@example.com", List.of("L1"), false);
        JsonNode first = started(token);
        JsonNode again = started(token);

        assertThat(first.path("status").asText()).isEqualTo("in_progress");
        assertThat(first.path("current_level").asText()).isEqualTo("L1");
        assertThat(again.path("attempt_id").asText()).isEqualTo(first.path("attempt_id").asText());
        assertThat(first.path("server_time").asText()).isNotBlank();
        assertThat(first.path("deadline_at").asText()).isNotBlank();
        assertThat(first.path("run_limit").asInt()).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from assessment_attempts where job_id = ?::uuid", Integer.class, job))
                .isOne();
        assertThat(jdbc.queryForObject("select version from assessment_attempts where job_id = ?::uuid", Integer.class, job))
                .isOne();
    }

    @Test
    @DisplayName("autosave stores the answer with a snapshot of the question; after the deadline it is refused")
    void autosaveAndDeadline() throws Exception {
        String token = mint("c2@example.com", List.of("L1"), false);
        String mcq = questionOf(started(token), "MCQ");

        save(token, mcq, Map.of("selected_indices", List.of(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.question_id").value(mcq));
        assertThat(jdbc.queryForObject("select question_snapshot->>'prompt' from attempt_answers where question_id = ?::uuid",
                String.class, mcq)).isNotBlank();
        assertThat(jdbc.queryForObject("select question_snapshot::text from attempt_answers where question_id = ?::uuid",
                String.class, mcq)).doesNotContain("correct");

        jdbc.update("update assessment_attempts set deadline_at = now() - interval '1 minute' where job_id = ?::uuid", job);
        save(token, mcq, Map.of("selected_indices", List.of(1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("time_up"));
        assertThat(jdbc.queryForObject("select selected_indices::text from attempt_answers where question_id = ?::uuid",
                String.class, mcq)).isEqualTo("[2]");
    }

    @Test
    @DisplayName("run executes only the visible tests, records each run, and stops at 20 per question")
    void runVisibleOnlyAndCapped() throws Exception {
        String token = mint("c3@example.com", List.of("L1"), false);
        String write = questionOf(started(token), "CODE_WRITE");

        JsonNode first = bodyOf(postJson(BASE + token + "/run/" + write, Map.of("language", "java", "code", "// REFERENCE"))
                .andExpect(status().isOk()));
        assertThat(first.path("total").asInt()).as("3 visible, never the 8 hidden").isEqualTo(3);
        assertThat(first.path("passed").asInt()).isEqualTo(3);
        assertThat(first.path("runs_remaining").asInt()).isEqualTo(19);
        for (JsonNode t : first.path("tests")) assertThat(t.has("input")).isTrue();

        for (int i = 2; i <= 20; i++) {
            postJson(BASE + token + "/run/" + write, Map.of("language", "java", "code", "// REFERENCE")).andExpect(status().isOk());
        }
        postJson(BASE + token + "/run/" + write, Map.of("language", "java", "code", "// REFERENCE"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("run_limit"));
        assertThat(jdbc.queryForObject("select count(*) from code_runs r join attempt_answers a on a.id = r.answer_id "
                + "join assessment_attempts t on t.id = a.attempt_id where r.mode = 'RUN' and t.job_id = ?::uuid",
                Integer.class, job)).isEqualTo(20);
        assertThat(started(token).path("saved_answers").get(0).path("runs_used").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("two submits at the same moment: exactly one wins, the other gets the used-link answer")
    void doubleSubmitExactlyOneWins() throws Exception {
        String token = mint("c4@example.com", List.of("L1"), false);
        started(token);

        List<Integer> statuses = inParallel(2, () -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(BASE + token + "/submit")).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertThat(jdbc.queryForObject("select count(*) from assessment_attempts where status <> 'IN_PROGRESS' and job_id = ?::uuid",
                Integer.class, job)).isOne();
        assertThat(jdbc.queryForObject("select used_at is not null from invites where candidate_email = 'c4@example.com'",
                Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("the scheduler auto-submits an attempt past its deadline and queues evaluation")
    void autoSubmitOnDeadline() throws Exception {
        String token = mint("c5@example.com", List.of("L1"), false);
        String mcq = questionOf(started(token), "MCQ");
        save(token, mcq, Map.of("selected_indices", List.of(1))).andExpect(status().isOk());
        jdbc.update("update assessment_attempts set deadline_at = now() - interval '1 minute' where job_id = ?::uuid", job);

        // The sweep covers every job (the test DB is shared), so assert on ours.
        assertThat(candidateService.autoSubmitOverdue()).isPositive();
        assertThat(candidateService.autoSubmitOverdue()).as("idempotent").isZero();

        Map<String, Object> row = jdbc.queryForMap("select status, auto_submitted, submitted_at = deadline_at as at_deadline "
                + "from assessment_attempts where job_id = ?::uuid", job);
        assertThat(row.get("status")).isNotEqualTo("IN_PROGRESS");
        assertThat(row.get("auto_submitted")).isEqualTo(true);
        assertThat(row.get("at_deadline")).isEqualTo(true);
        assertThat(jdbc.queryForObject("select count(*) from assessment_scorecards where job_id = ?::uuid", Integer.class, job))
                .as("evaluation was queued").isOne();
        start(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This assessment has already been submitted."));
    }

    @Test
    @DisplayName("invalid, expired and used links answer with the same wording as interview links")
    void linkMessages() throws Exception {
        start("no-such-token").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This assessment link is invalid."));

        String expired = mint("c6@example.com", List.of("L1"), false);
        jdbc.update("update invites set expires_at = now() - interval '1 minute' where candidate_email = 'c6@example.com'");
        start(expired).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This assessment link has expired."));

        String used = mint("c7@example.com", List.of("L1"), false);
        started(used);
        postJson(BASE + used + "/submit", Map.of()).andExpect(status().isOk());
        start(used).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This assessment has already been submitted."));
    }

    @Test
    @DisplayName("a combined invite serves one attempt per level, in order; the link is used only after the last")
    void combinedInviteLevelByLevel() throws Exception {
        String token = mint("c8@example.com", List.of("L1", "L2"), true);

        JsonNode l1 = started(token);
        assertThat(l1.path("current_level").asText()).isEqualTo("L1");
        assertThat(l1.path("level_statuses").get(1).path("status").asText()).isEqualTo("NOT_STARTED");

        JsonNode afterL1 = bodyOf(postJson(BASE + token + "/submit", Map.of()).andExpect(status().isOk()));
        assertThat(afterL1.path("next_level").asText()).isEqualTo("L2");
        assertThat(jdbc.queryForObject("select used_at is null from invites where candidate_email = 'c8@example.com'",
                Boolean.class)).as("still usable for L2").isTrue();

        JsonNode l2 = started(token);
        assertThat(l2.path("current_level").asText()).isEqualTo("L2");
        assertThat(l2.path("attempt_id").asText()).isNotEqualTo(l1.path("attempt_id").asText());

        JsonNode done = bodyOf(postJson(BASE + token + "/submit", Map.of()).andExpect(status().isOk()));
        assertThat(done.has("next_level")).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from assessment_attempts where job_id = ?::uuid", Integer.class, job))
                .isEqualTo(2);
        start(token).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("integrity events: stored, unknown types dropped, content never kept, capped at 500 per attempt")
    void integrityEvents() throws Exception {
        String token = mint("c9@example.com", List.of("L1"), false);
        started(token);

        JsonNode first = bodyOf(postJson(BASE + token + "/events", Map.of("events", List.of(
                Map.of("type", "TAB_SWITCH", "payload", Map.of()),
                Map.of("type", "PASTE", "payload", Map.of("length", 120, "content", "x".repeat(500))),
                Map.of("type", "SOMETHING_ELSE", "payload", Map.of())))).andExpect(status().isOk()));
        assertThat(first.path("stored").asInt()).isEqualTo(2);
        assertThat(first.path("dropped").asInt()).isEqualTo(1);
        String paste = jdbc.queryForObject("select e.payload::text from attempt_events e join assessment_attempts t "
                + "on t.id = e.attempt_id where e.type = 'PASTE' and t.job_id = ?::uuid", String.class, job);
        assertThat(paste).contains("120").doesNotContain("xxxx");

        // The 500-per-attempt cap: fill to 499, then only one more fits.
        String attemptId = jdbc.queryForObject("select id::text from assessment_attempts where job_id = ?::uuid", String.class, job);
        jdbc.update("insert into attempt_events (attempt_id, type, at) select ?::uuid, 'FOCUS_LOST', now() - (g || ' seconds')::interval "
                + "from generate_series(1, 497) g", attemptId);
        java.time.Instant t = java.time.Instant.now();
        JsonNode bulk = bodyOf(postJson(BASE + token + "/events", Map.of("events", List.of(
                Map.of("type", "PASTE", "payload", Map.of("length", 1), "at", t.toString()),
                Map.of("type", "FOCUS_LOST", "payload", Map.of(), "at", t.plusSeconds(2).toString())))).andExpect(status().isOk()));
        assertThat(bulk.path("stored").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from attempt_events e join assessment_attempts t "
                + "on t.id = e.attempt_id where t.job_id = ?::uuid", Integer.class, job)).isEqualTo(500);
    }

    @Test
    @DisplayName("an invite whose email matches a screened candidate is linked to that resume")
    void inviteLinksToResume() throws Exception {
        String resumeId = jdbc.queryForObject("insert into resumes (job_id, filename, file_path) values (?::uuid, 'cv.pdf', '/x/cv.pdf') "
                + "returning id::text", String.class, job);
        jdbc.update("insert into candidates (job_id, resume_id, candidate_name, email) values (?::uuid, ?::uuid, 'Linked', "
                + "'Linked@Example.com')", job, resumeId);

        String token = mint("linked@example.com", List.of("L1"), false);
        started(token);

        assertThat(jdbc.queryForObject("select resume_id::text from invites where candidate_email = 'linked@example.com'",
                String.class)).isEqualTo(resumeId);
        assertThat(jdbc.queryForObject("select resume_id::text from assessment_attempts where candidate_email = 'linked@example.com'",
                String.class)).isEqualTo(resumeId);
    }
}

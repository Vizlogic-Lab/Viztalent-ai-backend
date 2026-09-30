package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F6 — the candidate takes the assessment from an invite token: start/resume,
 *  autosave, run code against visible tests, and submit (which consumes the
 *  link). Generation uses the fake Gemini + fake Piston (PracticalStubs);
 *  generation and code runs happen inline in tests. */
class CandidateAssessmentIntegrationTest extends IntegrationTestBase {

    private Account admin;
    private String job;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());

        // Generate an L1 assessment, then mint an L1 invite and pull its token.
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1"))).andExpect(status().isOk());
        assertThat(bodyOf(getAs(admin, "/api/assessment/status/" + job)).path("status").asText()).isEqualTo("READY");

        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint",
                Map.of("session_id", job, "candidate_email", "cand@example.com", "candidate_name", "Cand", "levels", List.of("L1")))
                .andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        token = url.substring(url.indexOf("token=") + 6);
    }

    private JsonNode byToken() throws Exception {
        return bodyOf(mvc.perform(get("/api/assessment/by_token/" + token)).andExpect(status().isOk()));
    }

    private String firstQuestionOfType(JsonNode view, String type) {
        for (JsonNode q : view.path("questions")) {
            if (q.path("type").asText().equals(type)) return q.path("question_id").asText();
        }
        throw new AssertionError("no " + type + " question in the assessment");
    }

    @Test
    @DisplayName("by_token starts an attempt, shows the candidate view (no answers or hidden tests) and a deadline")
    void startsAttempt() throws Exception {
        JsonNode v = byToken();

        assertThat(v.path("status").asText()).isEqualTo("in_progress");
        assertThat(v.path("job_title").asText()).isEqualTo("Java Backend Developer");
        assertThat(v.path("candidate_name").asText()).isEqualTo("Cand");
        assertThat(v.path("num_questions").asInt()).isEqualTo(12);
        assertThat(v.path("total_points").asInt()).isEqualTo(100);
        assertThat(v.path("seconds_remaining").asLong()).isPositive();
        assertThat(v.path("deadline").asText()).isNotBlank();

        // Candidate view carries no answers, solutions, hidden tests or rubric.
        for (JsonNode q : v.path("questions")) {
            assertThat(q.has("correct_indices")).isFalse();
            assertThat(q.has("reference_solution")).isFalse();
            assertThat(q.has("rubric")).isFalse();
            assertThat(q.has("model_answer")).isFalse();
        }
        // A CODE_WRITE question exposes only its visible sample tests (3, from the stub).
        JsonNode codeWrite = null;
        for (JsonNode q : v.path("questions")) if (q.path("type").asText().equals("CODE_WRITE")) codeWrite = q;
        assertThat(codeWrite).isNotNull();
        assertThat(codeWrite.path("sample_tests")).hasSize(3);

        // Reopening returns the SAME attempt (resume), not a second one.
        byToken();
        assertThat(jdbc.queryForObject("select count(*) from assessment_attempts where job_id = ?::uuid", Integer.class, job))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("save persists answers; reopening the link echoes them back so the candidate can resume")
    void savesAndResumes() throws Exception {
        JsonNode v = byToken();
        String mcq = firstQuestionOfType(v, "MCQ");
        String logic = firstQuestionOfType(v, "LOGIC");

        postJson("/api/assessment/save_by_token/" + token, Map.of("answers", List.of(
                Map.of("question_id", mcq, "selected_indices", List.of(1)),
                Map.of("question_id", logic, "text", "12"))))
                .andExpect(status().isOk());

        JsonNode reopened = byToken();
        JsonNode saved = reopened.path("saved_answers");
        assertThat(saved).hasSize(2);
        boolean mcqSaved = false, logicSaved = false;
        for (JsonNode a : saved) {
            if (a.path("question_id").asText().equals(mcq)) {
                assertThat(a.path("selected_indices").get(0).asInt()).isEqualTo(1);
                mcqSaved = true;
            }
            if (a.path("question_id").asText().equals(logic)) {
                assertThat(a.path("text").asText()).isEqualTo("12");
                logicSaved = true;
            }
        }
        assertThat(mcqSaved && logicSaved).isTrue();
    }

    @Test
    @DisplayName("run executes the candidate's code against the visible tests only, never the hidden ones")
    void runVisibleTestsOnly() throws Exception {
        String codeWrite = firstQuestionOfType(byToken(), "CODE_WRITE");

        // The fake runner treats "// REFERENCE" as the correct (sum) program.
        JsonNode pass = bodyOf(postJson("/api/assessment/run_by_token/" + token,
                Map.of("question_id", codeWrite, "language", "java", "code", "// REFERENCE")).andExpect(status().isOk()));
        assertThat(pass.path("status").asText()).isEqualTo("OK");
        assertThat(pass.path("total").asInt()).as("only the 3 visible tests, never the 8 hidden").isEqualTo(3);
        assertThat(pass.path("passed").asInt()).isEqualTo(3);

        // "// NAIVE" prints 0, so it fails the visible tests.
        JsonNode fail = bodyOf(postJson("/api/assessment/run_by_token/" + token,
                Map.of("question_id", codeWrite, "language", "java", "code", "// NAIVE")).andExpect(status().isOk()));
        assertThat(fail.path("passed").asInt()).isZero();
        assertThat(fail.path("tests").get(0).has("expected_output")).isTrue();

        // Running saved the latest code as the answer.
        JsonNode saved = byToken().path("saved_answers");
        assertThat(saved).anySatisfy(a -> {
            if (a.path("question_id").asText().equals(codeWrite)) {
                assertThat(a.path("code").asText()).isEqualTo("// NAIVE");
                assertThat(a.path("language").asText()).isEqualTo("java");
            }
        });
    }

    @Test
    @DisplayName("submit stores answers, consumes the link, and blocks any further save or resubmit")
    void submitConsumesTheLink() throws Exception {
        String mcq = firstQuestionOfType(byToken(), "MCQ");

        JsonNode submitted = bodyOf(postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", List.of(
                Map.of("question_id", mcq, "selected_indices", List.of(2))))).andExpect(status().isOk()));
        assertThat(submitted.path("status").asText()).isEqualTo("submitted");
        assertThat(submitted.path("auto_submitted").asBoolean()).isFalse();
        assertThat(submitted.path("submitted_at").asText()).isNotBlank();
        assertThat(submitted.has("questions")).as("submitted view hides the questions").isFalse();

        // The invite is now used.
        assertThat(jdbc.queryForObject("select used_at is not null from invites where assessment_id is not null "
                + "and job_id = ?::uuid", Boolean.class, job)).isTrue();

        // by_token now reports it as already submitted; save and submit are refused.
        assertThat(byToken().path("status").asText()).isEqualTo("submitted");
        postJson("/api/assessment/save_by_token/" + token, Map.of("answers", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_submitted"));
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_submitted"));
    }

    @Test
    @DisplayName("a candidate cannot answer or run a question outside their invited assessment")
    void foreignQuestionRejected() throws Exception {
        byToken();
        String otherQuestion = "00000000-0000-0000-0000-000000000000";
        postJson("/api/assessment/save_by_token/" + token, Map.of("answers", List.of(
                Map.of("question_id", otherQuestion, "text", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("isn't part of this assessment")));
        postJson("/api/assessment/run_by_token/" + token,
                Map.of("question_id", otherQuestion, "language", "java", "code", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("when the deadline passes, the next call auto-submits the saved answers")
    void deadlineAutoSubmits() throws Exception {
        String mcq = firstQuestionOfType(byToken(), "MCQ");
        postJson("/api/assessment/save_by_token/" + token, Map.of("answers", List.of(
                Map.of("question_id", mcq, "selected_indices", List.of(0))))).andExpect(status().isOk());

        // Force the deadline into the past.
        jdbc.update("update assessment_attempts set deadline = now() - interval '1 minute' where job_id = ?::uuid", job);

        JsonNode v = byToken();
        assertThat(v.path("status").asText()).isEqualTo("submitted");
        assertThat(v.path("auto_submitted").asBoolean()).isTrue();
        assertThat(v.path("message").asText()).contains("Time is up");
        // The saved answer survived the auto-submit.
        assertThat(jdbc.queryForObject("select count(*) from assessment_answers a join assessment_attempts t "
                + "on t.id = a.attempt_id where t.job_id = ?::uuid", Integer.class, job)).isEqualTo(1);
        // Running after the deadline is refused.
        postJson("/api/assessment/run_by_token/" + token,
                Map.of("question_id", mcq, "language", "java", "code", "x"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("bad, unknown and wrong-kind tokens are refused; an unstarted link can't be saved to")
    void tokenValidation() throws Exception {
        mvc.perform(get("/api/assessment/by_token/not-a-real-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("invalid")));
        // Saving before opening the link (no attempt yet).
        postJson("/api/assessment/save_by_token/" + token, Map.of("answers", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("hasn't been started")));
    }

    @Test
    @DisplayName("HR sees the attempt in the submissions list with its answered count and status")
    void hrSeesSubmissions() throws Exception {
        String mcq = firstQuestionOfType(byToken(), "MCQ");
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", List.of(
                Map.of("question_id", mcq, "selected_indices", List.of(1))))).andExpect(status().isOk());

        JsonNode subs = bodyOf(getAs(admin, "/api/assessment/submissions/" + job).andExpect(status().isOk()));
        assertThat(subs.path("submissions")).hasSize(1);
        JsonNode row = subs.path("submissions").get(0);
        assertThat(row.path("candidate_email").asText()).isEqualTo("cand@example.com");
        assertThat(row.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(row.path("answered").asInt()).isEqualTo(1);
        assertThat(row.path("num_questions").asInt()).isEqualTo(12);
        assertThat(row.path("version").asInt()).isEqualTo(1);
    }
}

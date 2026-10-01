package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F6 — the extended answer key, single-question regenerate into the same
 *  version, and HR edits that are re-validated. Fake Gemini + fake Piston
 *  (PracticalStubs); generation runs inline in tests. */
class AnswerKeyAndQuestionEditIntegrationTest extends IntegrationTestBase {

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
        generateL1();
    }

    private void generateL1() throws Exception {
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1"))).andExpect(status().isOk());
    }

    private JsonNode answerKey(String query) throws Exception {
        return bodyOf(getAs(admin, "/api/assessment/answer_key/" + job + query).andExpect(status().isOk()));
    }

    private String questionId(String type) {
        return jdbc.queryForObject("select q.id::text from assessment_questions q join assessments a on a.id = q.assessment_id "
                + "where a.job_id = ?::uuid and a.is_current and q.type = ? order by q.seq limit 1", String.class, job, type);
    }

    private JsonNode find(JsonNode key, String type) {
        for (JsonNode q : key.path("levels").get(0).path("questions")) if (q.path("type").asText().equals(type)) return q;
        throw new AssertionError("no " + type);
    }

    private List<Map<String, Object>> sumTests() {
        List<Map<String, Object>> tests = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            int a = i + 2, b = i * 3;
            tests.add(Map.of("input", a + " " + b, "expected_output", String.valueOf(a + b),
                    "visible", i < 3, "category", i < 3 ? "BASIC" : "EDGE"));
        }
        return tests;
    }

    // ── answer key ──────────────────────────────────────────────────────

    @Test
    @DisplayName("answer key carries the full practical detail and keeps the original popup fields")
    void answerKeyShape() throws Exception {
        JsonNode key = answerKey("");
        assertThat(key.path("version").asInt()).isEqualTo(1);
        assertThat(key.path("status").asText()).isEqualTo("READY");
        assertThat(key.path("is_current").asBoolean()).isTrue();
        assertThat(key.path("has_attempts").asBoolean()).isFalse();

        JsonNode mcq = find(key, "MCQ");
        assertThat(mcq.path("question").asText()).isNotBlank();                 // original field
        assertThat(mcq.path("correct_index").asInt()).isEqualTo(1);             // original field
        assertThat(mcq.path("options")).hasSize(4);
        assertThat(mcq.path("explanation").asText()).isNotBlank();
        assertThat(mcq.path("dimension").asText()).isEqualTo("THEORY");

        JsonNode write = find(key, "CODE_WRITE");
        assertThat(write.path("statement").asText()).isEqualTo(write.path("question").asText());
        assertThat(write.path("reference_solution").path("java").asText()).contains("REFERENCE");
        assertThat(write.path("starter_code").path("java").asText()).isNotBlank();
        assertThat(write.path("rubric")).hasSize(4);
        assertThat(write.path("expected_complexity").asText()).isEqualTo("O(1)");
        assertThat(write.path("validated").asBoolean()).isTrue();
        assertThat(write.path("tests")).hasSize(11);
        int hidden = 0;
        for (JsonNode t : write.path("tests")) if (!t.path("visible").asBoolean()) hidden++;
        assertThat(hidden).as("hidden tests are included and marked").isEqualTo(8);

        JsonNode debug = find(key, "CODE_DEBUG");
        assertThat(debug.path("buggy_code").path("java").asText()).contains("BUGGY");
        assertThat(debug.path("fixed_code").path("java").asText()).contains("FIXED");
        assertThat(debug.path("bug_descriptions")).isNotEmpty();
    }

    @Test
    @DisplayName("?version= returns that version; without it the current one")
    void answerKeyByVersion() throws Exception {
        generateL1();   // v2 becomes current

        assertThat(answerKey("").path("version").asInt()).isEqualTo(2);
        JsonNode v1 = answerKey("?version=1");
        assertThat(v1.path("version").asInt()).isEqualTo(1);
        assertThat(v1.path("is_current").asBoolean()).isFalse();
        getAs(admin, "/api/assessment/answer_key/" + job + "?version=9").andExpect(status().isNotFound());
    }

    // ── regenerate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("regenerate replaces just that question, in the same version")
    void regenerateInSameVersion() throws Exception {
        String old = questionId("LOGIC");
        int before = jdbc.queryForObject("select count(*) from assessment_questions", Integer.class);

        JsonNode fresh = bodyOf(postJsonAs(admin, "/api/assessment/questions/" + old + "/regenerate", Map.of())
                .andExpect(status().isOk()));

        assertThat(fresh.path("question_id").asText()).isNotEqualTo(old);
        assertThat(fresh.path("type").asText()).isEqualTo("LOGIC");
        assertThat(fresh.path("validation_log").asText()).contains("Regenerated");
        assertThat(jdbc.queryForObject("select count(*) from assessment_questions where id = ?::uuid", Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from assessment_questions", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select a.version from assessment_questions q join assessments a on a.id = q.assessment_id "
                + "where q.id = ?::uuid", Integer.class, fresh.path("question_id").asText())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid", Integer.class, job))
                .as("no new version").isEqualTo(1);
    }

    @Test
    @DisplayName("a regenerate that can't produce a valid question keeps the original")
    void failedRegenerateKeepsOriginal() throws Exception {
        String old = questionId("LOGIC");
        STUB.respond("POST", "/v1beta/models/", 503, "{\"error\":{\"message\":\"overloaded\"}}");

        postJsonAs(admin, "/api/assessment/questions/" + old + "/regenerate", Map.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("regenerate_failed"));
        assertThat(jdbc.queryForObject("select count(*) from assessment_questions where id = ?::uuid", Integer.class, old)).isOne();
    }

    @Test
    @DisplayName("once a candidate has opened the version, regenerate and edit are refused")
    void frozenAfterAttempt() throws Exception {
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job,
                "candidate_email", "c@example.com", "levels", List.of("L1"))).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        mvc.perform(get("/api/assessment/by_token/" + url.substring(url.indexOf("token=") + 6))).andExpect(status().isOk());

        String q = questionId("LOGIC");
        postJsonAs(admin, "/api/assessment/questions/" + q + "/regenerate", Map.of())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_in_use"));
        putJsonAs(admin, "/api/assessment/questions/" + q, Map.of("statement", "changed"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_in_use"));
        assertThat(answerKey("").path("has_attempts").asBoolean()).isTrue();
    }

    // ── edit ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a valid MCQ edit is saved; an invalid one is refused and changes nothing")
    void editMcq() throws Exception {
        String q = questionId("MCQ");
        JsonNode saved = bodyOf(putJsonAs(admin, "/api/assessment/questions/" + q, Map.of(
                "statement", "Which collection keeps insertion order?",
                "options", List.of("LinkedHashMap", "HashMap", "HashSet"),
                "correct_indices", List.of(0),
                "points", 6)).andExpect(status().isOk()));
        assertThat(saved.path("question").asText()).isEqualTo("Which collection keeps insertion order?");
        assertThat(saved.path("correct_index").asInt()).isZero();
        assertThat(saved.path("points").asInt()).isEqualTo(6);
        assertThat(saved.path("validation_log").asText()).contains("Edited by HR");

        putJsonAs(admin, "/api/assessment/questions/" + q, Map.of("correct_indices", List.of(0, 2), "statement", "broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_question"));
        assertThat(jdbc.queryForObject("select prompt from assessment_questions where id = ?::uuid", String.class, q))
                .isEqualTo("Which collection keeps insertion order?");
    }

    @Test
    @DisplayName("a coding edit re-runs validation: a failing reference is refused, a passing one saved")
    void editCodingRevalidates() throws Exception {
        String q = questionId("CODE_WRITE");

        putJsonAs(admin, "/api/assessment/questions/" + q, Map.of("reference_solution", Map.of("java", "// BADREF")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("reference solution fails")));
        assertThat(jdbc.queryForObject("select reference_solution::text from assessment_questions where id = ?::uuid",
                String.class, q)).contains("REFERENCE");

        JsonNode saved = bodyOf(putJsonAs(admin, "/api/assessment/questions/" + q, Map.of(
                "statement", "Print the sum of two integers.",
                "reference_solution", Map.of("java", "// REFERENCE v2"))).andExpect(status().isOk()));
        assertThat(saved.path("statement").asText()).isEqualTo("Print the sum of two integers.");
        assertThat(saved.path("validation_log").asText()).contains("reference passed");
    }

    @Test
    @DisplayName("an edit can replace a coding question's tests (old rows go before new ones arrive)")
    void editReplacesTests() throws Exception {
        String q = questionId("CODE_WRITE");
        JsonNode saved = bodyOf(putJsonAs(admin, "/api/assessment/questions/" + q, Map.of("tests", sumTests()))
                .andExpect(status().isOk()));

        assertThat(saved.path("tests")).hasSize(11);
        assertThat(saved.path("tests").get(0).path("input").asText()).isEqualTo("2 0");
        assertThat(jdbc.queryForObject("select count(*) from question_test_cases where question_id = ?::uuid and visible",
                Integer.class, q)).isEqualTo(3);
    }

    @Test
    @DisplayName("an employee can't regenerate or edit a question of someone else's job")
    void accessControl() throws Exception {
        Account employee = newEmployee();
        String q = questionId("MCQ");
        postJsonAs(employee, "/api/assessment/questions/" + q + "/regenerate", Map.of()).andExpect(status().isForbidden());
        putJsonAs(employee, "/api/assessment/questions/" + q, Map.of("statement", "x")).andExpect(status().isForbidden());
    }
}

package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F9 — automatic scoring. After submit, the attempt is graded in the
 *  background (inline in tests): deterministic parts always, CODE_WRITE rubric
 *  and SCENARIO via the fake Gemini grader, coding tests via the fake Piston.
 *  When the runner or Gemini is down, those parts are flagged for manual
 *  review and re-scoring recovers them. */
class AssessmentScoringIntegrationTest extends IntegrationTestBase {

    private Account admin;
    private String job;
    private String token;
    private String assessmentId;

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
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1"))).andExpect(status().isOk());
        assertThat(bodyOf(getAs(admin, "/api/assessment/status/" + job)).path("status").asText()).isEqualTo("READY");
        assessmentId = jdbc.queryForObject("select id::text from assessments where job_id = ?::uuid and is_current", String.class, job);

        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint",
                Map.of("session_id", job, "candidate_email", "cand@example.com", "candidate_name", "Cand", "levels", List.of("L1")))
                .andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        token = url.substring(url.indexOf("token=") + 6);
    }

    /** Correct answer for every L1 question, read from the stored answer key. */
    private List<Map<String, Object>> correctAnswers() throws Exception {
        List<Map<String, Object>> answers = new ArrayList<>();
        var rows = jdbc.queryForList("select id::text as id, type, correct_indices::text as ci, model_answer "
                + "from assessment_questions where assessment_id = ?::uuid order by level, seq", assessmentId);
        for (var r : rows) {
            String id = (String) r.get("id");
            switch ((String) r.get("type")) {
                case "MCQ", "MSQ" -> {
                    List<Integer> ci = new ArrayList<>();
                    json.readTree((String) r.get("ci")).forEach(n -> ci.add(n.asInt()));
                    answers.add(Map.of("question_id", id, "selected_indices", ci));
                }
                case "CODE_WRITE", "CODE_DEBUG" -> answers.add(Map.of("question_id", id, "language", "java", "code", "// REFERENCE"));
                default -> answers.add(Map.of("question_id", id, "text", String.valueOf(r.get("model_answer"))));
            }
        }
        return answers;
    }

    private JsonNode scorecard() throws Exception {
        String attemptId = jdbc.queryForObject("select id::text from assessment_attempts where job_id = ?::uuid", String.class, job);
        return bodyOf(getAs(admin, "/api/assessment/scorecard/" + job + "/" + attemptId).andExpect(status().isOk()));
    }

    private void start() throws Exception {
        mvc.perform(get("/api/assessment/by_token/" + token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a fully-correct submission scores 100%, passes, and needs no manual review")
    void perfectScore() throws Exception {
        start();
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", correctAnswers())).andExpect(status().isOk());

        JsonNode card = scorecard();
        assertThat(card.path("status").asText()).isEqualTo("SCORED");
        assertThat(card.path("max_score").asDouble()).isEqualTo(100.0);
        assertThat(card.path("total_score").asDouble()).isEqualTo(100.0);
        assertThat(card.path("percent").asDouble()).isEqualTo(100.0);
        assertThat(card.path("passed").asBoolean()).isTrue();
        assertThat(card.path("needs_review").asBoolean()).isFalse();
        assertThat(card.path("questions")).hasSize(12);

        // A CODE_WRITE: all tests pass (70%) + full rubric (30%) = full points, with a breakdown.
        JsonNode codeWrite = null;
        for (JsonNode q : card.path("questions")) if (q.path("type").asText().equals("CODE_WRITE")) codeWrite = q;
        assertThat(codeWrite).isNotNull();
        assertThat(codeWrite.path("score").asDouble()).isEqualTo(codeWrite.path("max_points").asDouble());
        assertThat(codeWrite.path("tests_passed").asInt()).isEqualTo(codeWrite.path("tests_total").asInt());
        assertThat(codeWrite.path("tests_total").asInt()).as("all tests scored, visible and hidden").isGreaterThanOrEqualTo(11);
        assertThat(codeWrite.path("breakdown")).hasSize(4);
    }

    @Test
    @DisplayName("MCQ/MSQ are scored deterministically; a wrong choice earns nothing, an unanswered question too")
    void deterministicScoring() throws Exception {
        start();
        // Answer only the MCQs — correctly — and nothing else.
        List<Map<String, Object>> mcqAnswers = new ArrayList<>();
        for (var a : correctAnswers()) {
            String type = jdbc.queryForObject("select type from assessment_questions where id = ?::uuid",
                    String.class, a.get("question_id"));
            if (type.equals("MCQ")) mcqAnswers.add(a);
        }
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", mcqAnswers)).andExpect(status().isOk());

        JsonNode card = scorecard();
        int mcqPoints = 0, answered = 0;
        for (JsonNode q : card.path("questions")) {
            if (q.path("type").asText().equals("MCQ")) {
                assertThat(q.path("score").asDouble()).isEqualTo(q.path("max_points").asDouble());
                assertThat(q.path("answered").asBoolean()).isTrue();
                mcqPoints += q.path("max_points").asInt();
                answered++;
            } else {
                assertThat(q.path("score").asDouble()).isZero();
                assertThat(q.path("answered").asBoolean()).isFalse();
            }
        }
        assertThat(answered).isEqualTo(4);
        assertThat(card.path("total_score").asInt()).isEqualTo(mcqPoints);
        assertThat(card.path("passed").asBoolean()).isEqualTo(mcqPoints >= 50);
    }

    @Test
    @DisplayName("when Gemini is down at scoring time, CODE_WRITE keeps its test credit but the rubric needs review")
    void aiGraderDownNeedsReview() throws Exception {
        start();
        // Grading calls now fail (drafting already happened during setUp).
        STUB.respond("POST", "/v1beta/models/", 503, "{\"error\":{\"message\":\"overloaded\"}}");
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", correctAnswers())).andExpect(status().isOk());

        JsonNode card = scorecard();
        assertThat(card.path("needs_review").asBoolean()).isTrue();
        assertThat(card.path("review_points").asDouble()).isPositive();

        JsonNode codeWrite = null;
        for (JsonNode q : card.path("questions")) if (q.path("type").asText().equals("CODE_WRITE")) codeWrite = q;
        assertThat(codeWrite.path("needs_review").asBoolean()).isTrue();
        // 70% test share only (tests all pass), rubric withheld for review.
        double expected = 0.70 * codeWrite.path("max_points").asDouble();
        assertThat(codeWrite.path("score").asDouble()).isEqualTo(round2(expected));
    }

    @Test
    @DisplayName("re-scoring after the AI grader recovers fills in the rubric and clears the review flag")
    void rescoreRecovers() throws Exception {
        start();
        STUB.respond("POST", "/v1beta/models/", 503, "{\"error\":{\"message\":\"overloaded\"}}");
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", correctAnswers())).andExpect(status().isOk());
        assertThat(scorecard().path("needs_review").asBoolean()).isTrue();

        // Restore the grader and re-score.
        PracticalStubs.fakeGemini(STUB, Map.of());
        String attemptId = jdbc.queryForObject("select id::text from assessment_attempts where job_id = ?::uuid", String.class, job);
        postJsonAs(admin, "/api/assessment/rescore/" + job + "/" + attemptId, Map.of()).andExpect(status().isOk());

        JsonNode card = scorecard();
        assertThat(card.path("status").asText()).isEqualTo("SCORED");
        assertThat(card.path("needs_review").asBoolean()).isFalse();
        assertThat(card.path("percent").asDouble()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("when the code runner is down, coding questions are flagged for manual review, not failed")
    void runnerDownNeedsReview() throws Exception {
        start();
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", "")).andExpect(status().isOk());
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", correctAnswers())).andExpect(status().isOk());

        JsonNode card = scorecard();
        assertThat(card.path("needs_review").asBoolean()).isTrue();
        for (JsonNode q : card.path("questions")) {
            if (q.path("type").asText().startsWith("CODE_") && !q.path("type").asText().equals("CODE_OUTPUT")) {
                assertThat(q.path("needs_review").asBoolean()).as(q.path("type").asText()).isTrue();
                assertThat(q.path("detail").asText()).containsIgnoringCase("runner");
            }
        }
    }

    @Test
    @DisplayName("the submissions list carries each attempt's score once it is graded")
    void submissionsShowScore() throws Exception {
        start();
        postJson("/api/assessment/submit_by_token/" + token, Map.of("answers", correctAnswers())).andExpect(status().isOk());

        JsonNode row = bodyOf(getAs(admin, "/api/assessment/submissions/" + job).andExpect(status().isOk()))
                .path("submissions").get(0);
        assertThat(row.path("score_status").asText()).isEqualTo("SCORED");
        assertThat(row.path("percent").asDouble()).isEqualTo(100.0);
        assertThat(row.path("passed").asBoolean()).isTrue();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

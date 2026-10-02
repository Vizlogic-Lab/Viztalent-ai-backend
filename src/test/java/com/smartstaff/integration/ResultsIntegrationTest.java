package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import com.smartstaff.support.PracticalStubs.SummaryMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F10 — theory vs hands-on results, gates, summary and the results API, on top
 *  of the F9 evaluation (fake Gemini + fake Piston; evaluation runs inline). */
class ResultsIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/candidate/assessment/";
    private static final String GOOD = "// REFERENCE\nScanner in = new Scanner(System.in);\nSystem.out.println(in.nextLong() + in.nextLong());";

    private Account admin;
    private String job;
    private String token;
    private JsonNode view;

    @BeforeEach
    void setUp() throws Exception {
        PracticalStubs.resetGrader();
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1"))).andExpect(status().isOk());
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint",
                Map.of("session_id", job, "candidate_email", "r@example.com", "levels", List.of("L1"))).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        token = url.substring(url.indexOf("token=") + 6);
        view = bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
    }

    private String q(String type) {
        for (JsonNode x : view.path("questions")) if (x.path("type").asText().equals(type)) return x.path("question_id").asText();
        throw new AssertionError("no " + type);
    }

    private void answerCodeAndSubmit() throws Exception {
        mvc.perform(put(BASE + token + "/answers/" + q("CODE_WRITE")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("language", "java", "code", GOOD)))).andExpect(status().isOk());
        postJson(BASE + token + "/submit", Map.of()).andExpect(status().isOk());
    }

    private Map<String, Object> attempt() {
        return jdbc.queryForMap("select id::text as id, status, theory_score, hands_on_score, overall_score, theory_band, "
                + "hands_on_band, recommendation, flags::text as flags, summary::text as summary, "
                + "evidence_lines::text as lines, competency_scores::text as comps from assessment_attempts where job_id = ?::uuid", job);
    }

    /** Expected dimension score straight from the stored per-question scores. */
    private double expected(String dimension) {
        return jdbc.queryForObject("select 100.0 * sum(coalesce(a.final_score, 0)) / sum(q.points) from attempt_answers a "
                + "join assessment_questions q on q.id = a.question_id join assessment_attempts t on t.id = a.attempt_id "
                + "where t.job_id = ?::uuid and q.dimension = ?", Double.class, job, dimension);
    }

    private double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    @DisplayName("evaluation stores theory/hands-on/overall, bands, gate, competencies, evidence lines and an AI summary")
    void resultStored() throws Exception {
        answerCodeAndSubmit();
        Map<String, Object> a = attempt();
        assertThat(a.get("status")).isEqualTo("EVALUATED");
        double theory = expected("THEORY"), handsOn = expected("HANDS_ON");
        assertThat(num(a.get("theory_score"))).isCloseTo(theory, within(0.01));
        assertThat(num(a.get("hands_on_score"))).isCloseTo(handsOn, within(0.01));
        assertThat(num(a.get("overall_score"))).isCloseTo(0.3 * theory + 0.7 * handsOn, within(0.02));
        assertThat(a.get("theory_band")).isEqualTo("NOT_DEMONSTRATED");   // MCQs unanswered
        assertThat(a.get("recommendation")).isEqualTo(handsOn >= 50 ? "HR_REVIEW" : "NOT_RECOMMENDED");
        assertThat((String) a.get("comps")).contains("CODING").contains("CONCEPTS");
        assertThat((String) a.get("lines")).contains("hidden tests");
        assertThat(json.readTree((String) a.get("lines"))).hasSize(view.path("questions").size());
        JsonNode summary = json.readTree((String) a.get("summary"));
        assertThat(summary.path("source").asText()).isEqualTo("AI");
        assertThat(summary.path("theory_line").asText()).startsWith("AI:");
    }

    @Test
    @DisplayName("an AI summary that invents a number is replaced by the template")
    void summaryNumberCheck() throws Exception {
        PracticalStubs.summaryMode = SummaryMode.INVENTED;
        answerCodeAndSubmit();
        JsonNode summary = json.readTree((String) attempt().get("summary"));
        assertThat(summary.path("source").asText()).isEqualTo("TEMPLATE");
        assertThat(summary.path("hands_on_line").asText()).startsWith("Hands-on capability:").doesNotContain("97.3");
    }

    @Test
    @DisplayName("more than 5 tab switches → HR_REVIEW with EXCESSIVE_TAB_SWITCHES")
    void tabSwitchesForceReview() throws Exception {
        List<Map<String, Object>> evs = new ArrayList<>();
        for (int i = 0; i < 6; i++) evs.add(Map.of("type", "TAB_SWITCH", "payload", Map.of()));
        postJson(BASE + token + "/events", Map.of("events", evs)).andExpect(status().isOk());
        answerCodeAndSubmit();
        Map<String, Object> a = attempt();
        assertThat(a.get("recommendation")).isEqualTo("HR_REVIEW");
        assertThat((String) a.get("flags")).contains("EXCESSIVE_TAB_SWITCHES");
    }

    @Test
    @DisplayName("submissions, attempt payload and status counts expose the provisional result")
    void resultsApi() throws Exception {
        answerCodeAndSubmit();
        String attemptId = (String) attempt().get("id");

        JsonNode row = bodyOf(getAs(admin, "/api/assessment/submissions/" + job).andExpect(status().isOk())).path("submissions").get(0);
        assertThat(row.path("attempt_id").asText()).isEqualTo(attemptId);
        assertThat(row.path("level").asText()).isEqualTo("L1");
        assertThat(row.has("theory_score") && row.has("hands_on_score") && row.has("overall_score")).isTrue();
        assertThat(row.path("theory_band").asText()).isEqualTo("NOT_DEMONSTRATED");
        assertThat(row.path("recommendation").asText()).isNotBlank();
        assertThat(row.path("result_status").asText()).isEqualTo("PROVISIONAL");
        assertThat(row.path("review_status").asText()).isEqualTo("NOT_REVIEWED");
        assertThat(row.path("flags").isArray()).isTrue();

        JsonNode full = bodyOf(getAs(admin, "/api/assessment/attempts/" + attemptId).andExpect(status().isOk()));
        assertThat(full.path("questions")).hasSize(view.path("questions").size());
        JsonNode code = null;
        for (JsonNode x : full.path("questions")) if (x.path("question_id").asText().equals(q("CODE_WRITE"))) code = x;
        assertThat(code.path("code").asText()).contains("REFERENCE");
        assertThat(code.path("runs").get(0).path("mode").asText()).isEqualTo("SUBMIT");
        assertThat(code.path("evidence").toString()).contains("hidden_passed");
        assertThat(code.path("rubric_runs")).hasSize(2);
        // F13: the answer key rides along for the side-by-side review (HR/SME only endpoint).
        assertThat(code.path("answer_key").path("reference_solution").size()).isPositive();
        assertThat(code.path("answer_key").path("tests").findValuesAsText("visible")).contains("false");
        assertThat(full.path("summary").path("theory_line").asText()).isNotBlank();
        assertThat(full.path("event_counts").path("TAB_SWITCH").asInt()).isZero();
        assertThat(full.path("evidence_lines").isArray()).isTrue();

        JsonNode st = bodyOf(getAs(admin, "/api/assessment/status/" + job).andExpect(status().isOk()));
        assertThat(st.path("num_submissions").asInt()).isEqualTo(1);
        assertThat(st.path("num_evaluated").asInt()).isEqualTo(1);
        assertThat(st.path("latest_submission_at").isMissingNode()).isFalse();
        assertThat(st.path("recommendations").path(row.path("recommendation").asText()).asInt()).isEqualTo(1);
        assertThat(st.path("approval_status").isMissingNode()).as("no approval policy → no approval state").isTrue();
    }

    @Test
    @DisplayName("access: other HR users get 403 on the job's results and see none of it in submissions_all; admin sees it")
    void accessControl() throws Exception {
        answerCodeAndSubmit();
        String attemptId = (String) attempt().get("id");
        Account other = newEmployee();

        getAs(other, "/api/assessment/submissions/" + job).andExpect(status().isForbidden());
        getAs(other, "/api/assessment/attempts/" + attemptId).andExpect(status().isForbidden());
        getAs(other, "/api/assessment/scoring_settings/" + job).andExpect(status().isForbidden());
        putJsonAs(other, "/api/assessment/scoring_settings/" + job, Map.of("theory_weight", 0.5, "hands_on_weight", 0.5))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/assessment/attempts/" + attemptId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assessment/submissions_all")).andExpect(status().isUnauthorized());

        JsonNode mine = bodyOf(getAs(other, "/api/assessment/submissions_all").andExpect(status().isOk()));
        mine.path("submissions").forEach(r -> assertThat(r.path("attempt_id").asText()).isNotEqualTo(attemptId));
        JsonNode all = bodyOf(getAs(admin, "/api/assessment/submissions_all").andExpect(status().isOk()));
        assertThat(all.path("submissions").findValuesAsText("attempt_id")).contains(attemptId);
        assertThat(all.path("submissions").get(0).has("job_title")).isTrue();
    }

    @Test
    @DisplayName("per-job scoring settings: validated, stored, and applied on re-score")
    void scoringSettings() throws Exception {
        answerCodeAndSubmit();
        String attemptId = (String) attempt().get("id");
        JsonNode defaults = bodyOf(getAs(admin, "/api/assessment/scoring_settings/" + job).andExpect(status().isOk()));
        assertThat(defaults.path("hands_on_weight").asDouble()).isEqualTo(0.7);

        putJsonAs(admin, "/api/assessment/scoring_settings/" + job, Map.of("theory_weight", 0.5, "hands_on_weight", 0.6,
                "proceed_hands_on_min", 60, "proceed_theory_min", 50, "review_hands_on_min", 50, "max_tab_switches", 5,
                "theory_practice_gap", 25)).andExpect(status().isBadRequest());
        putJsonAs(admin, "/api/assessment/scoring_settings/" + job, Map.of("theory_weight", 0.0, "hands_on_weight", 1.0,
                "proceed_hands_on_min", 0, "proceed_theory_min", 0, "review_hands_on_min", 0, "max_tab_switches", 5,
                "theory_practice_gap", 25)).andExpect(status().isOk());

        postJsonAs(admin, "/api/assessment/rescore/" + job + "/" + attemptId, Map.of()).andExpect(status().isOk());
        Map<String, Object> a = attempt();
        assertThat(num(a.get("overall_score"))).isCloseTo(num(a.get("hands_on_score")), within(0.01));
        assertThat(a.get("recommendation")).isEqualTo("PROCEED");
    }
}

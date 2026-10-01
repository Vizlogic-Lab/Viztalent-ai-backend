package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.service.impl.EvaluationRetryScheduler;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import com.smartstaff.support.PracticalStubs.GraderMode;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F9 — the evaluation engine against the fake Gemini grader and fake Piston.
 *  Evaluation runs inline after submit; the retry scheduler is called directly. */
class EvaluationIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/candidate/assessment/";
    /** Correct, input-reading code (the fake runner prints the sum for the REFERENCE marker). */
    private static final String GOOD = "// REFERENCE\nScanner in = new Scanner(System.in);\nSystem.out.println(in.nextLong() + in.nextLong());";

    @Autowired EvaluationRetryScheduler sweeper;

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
    }

    private void open(String level) throws Exception {
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of(level))).andExpect(status().isOk());
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint",
                Map.of("session_id", job, "candidate_email", "e@example.com", "levels", List.of(level))).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        token = url.substring(url.indexOf("token=") + 6);
        view = bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
    }

    private String q(String type) {
        for (JsonNode x : view.path("questions")) if (x.path("type").asText().equals(type)) return x.path("question_id").asText();
        throw new AssertionError("no " + type);
    }

    private int points(String questionId) {
        for (JsonNode x : view.path("questions")) if (x.path("question_id").asText().equals(questionId)) return x.path("points").asInt();
        throw new AssertionError();
    }

    private void answer(String questionId, Map<String, Object> body) throws Exception {
        mvc.perform(put(BASE + token + "/answers/" + questionId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isOk());
    }

    private void submit() throws Exception {
        postJson(BASE + token + "/submit", Map.of()).andExpect(status().isOk());
    }

    private Map<String, Object> row(String questionId) {
        return jdbc.queryForMap("select auto_score, final_score, flags::text as flags, rubric_score::text as rubric, "
                + "evidence::text as evidence from attempt_answers where question_id = ?::uuid", questionId);
    }

    private Map<String, Object> attempt() {
        return jdbc.queryForMap("select id::text as id, status, flags::text as flags, evaluated_at, next_evaluation_at, "
                + "rubric_pending_since, evaluation_tries from assessment_attempts where job_id = ?::uuid", job);
    }

    /** Gemini grading calls only (the F10 result summary is a separate call). */
    private long graderCalls() {
        return STUB.requests("/v1beta/models/").stream().filter(r -> !r.body().contains("theory_line")).count();
    }

    private double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    @DisplayName("rubric happy path: tests (70%) + quoted-evidence rubric (30%), two runs stored, SUBMIT run over all tests")
    void rubricHappyPath() throws Exception {
        open("L1");
        String write = q("CODE_WRITE");
        answer(write, Map.of("language", "java", "code", GOOD));
        submit();

        assertThat(attempt().get("status")).isEqualTo("EVALUATED");
        assertThat(attempt().get("evaluated_at")).isNotNull();
        Map<String, Object> r = row(write);
        int pts = points(write);
        assertThat(num(r.get("auto_score"))).isCloseTo(0.7 * pts, within(0.01));
        assertThat(num(r.get("final_score"))).isCloseTo(pts, within(0.01));
        JsonNode rubric = json.readTree((String) r.get("rubric"));
        assertThat(rubric).as("graded twice").hasSize(2);
        assertThat((String) r.get("evidence")).contains("\"accepted\": true").contains("REFERENCE");
        assertThat(jdbc.queryForObject("select jsonb_array_length(results) from code_runs r join attempt_answers a on a.id = r.answer_id "
                + "where a.question_id = ?::uuid and r.mode = 'SUBMIT'", Integer.class, write)).as("all 11 tests run").isEqualTo(11);
    }

    @Test
    @DisplayName("a rubric score whose evidence isn't quoted from the answer is rejected (0)")
    void missingEvidenceRejected() throws Exception {
        open("L1");
        PracticalStubs.graderMode = GraderMode.NO_EVIDENCE;
        String write = q("CODE_WRITE");
        answer(write, Map.of("language", "java", "code", GOOD));
        submit();

        Map<String, Object> r = row(write);
        int pts = points(write);
        assertThat(num(r.get("final_score"))).as("tests only, rubric rejected").isCloseTo(0.7 * pts, within(0.01));
        assertThat((String) r.get("evidence")).contains("\"accepted\": false").contains("isn't quoted");
    }

    @Test
    @DisplayName("graders that disagree by more than 15% trigger a third run; the median is used and flagged")
    void disagreementThirdRunMedian() throws Exception {
        open("L1");
        PracticalStubs.graderMode = GraderMode.DISAGREE;   // runs award 100%, 0%, 50%
        String write = q("CODE_WRITE");
        answer(write, Map.of("language", "java", "code", GOOD));
        submit();

        Map<String, Object> r = row(write);
        int pts = points(write);
        assertThat(json.readTree((String) r.get("rubric"))).hasSize(3);
        assertThat(num(r.get("final_score"))).isCloseTo(0.7 * pts + 0.5 * 0.3 * pts, within(0.01));
        assertThat((String) r.get("flags")).contains("GRADER_DISAGREEMENT");
        assertThat((String) attempt().get("flags")).contains("GRADER_DISAGREEMENT");
    }

    @Test
    @DisplayName("Gemini down: objective scores kept, rubric pending and retried; it recovers when Gemini is back")
    void geminiDownThenRecovers() throws Exception {
        open("L1");
        STUB.respond("POST", "/v1beta/models/", 401, "{\"error\":{\"message\":\"key rejected\"}}");
        String write = q("CODE_WRITE");
        String mcq = q("MCQ");
        answer(write, Map.of("language", "java", "code", GOOD));
        answer(mcq, Map.of("selected_indices", List.of(1)));
        submit();

        Map<String, Object> a = attempt();
        assertThat(a.get("status")).isEqualTo("EVALUATING");
        assertThat(a.get("next_evaluation_at")).isNotNull();
        assertThat(a.get("rubric_pending_since")).isNotNull();
        int pts = points(write);
        assertThat(num(row(write).get("final_score"))).as("tests kept").isCloseTo(0.7 * pts, within(0.01));
        assertThat((String) row(write).get("flags")).contains("RUBRIC_PENDING");
        assertThat(num(row(mcq).get("final_score"))).as("objective scores kept").isPositive();

        // Gemini is back and the retry is due.
        PracticalStubs.fakeGemini(STUB, Map.of());
        jdbc.update("update assessment_attempts set next_evaluation_at = now() - interval '1 second' where job_id = ?::uuid", job);
        assertThat(sweeper.sweep()).isPositive();

        assertThat(attempt().get("status")).isEqualTo("EVALUATED");
        assertThat((String) row(write).get("flags")).doesNotContain("RUBRIC_PENDING");
        assertThat(num(row(write).get("final_score"))).isCloseTo(pts, within(0.01));
    }

    @Test
    @DisplayName("Gemini down past the retry window: flagged RUBRIC_UNAVAILABLE for human review")
    void geminiDownGivesUp() throws Exception {
        open("L1");
        STUB.respond("POST", "/v1beta/models/", 401, "{\"error\":{\"message\":\"key rejected\"}}");
        String write = q("CODE_WRITE");
        answer(write, Map.of("language", "java", "code", GOOD));
        submit();

        jdbc.update("update assessment_attempts set rubric_pending_since = now() - interval '61 minutes', "
                + "next_evaluation_at = now() - interval '1 second' where job_id = ?::uuid", job);
        assertThat(sweeper.sweep()).isPositive();

        Map<String, Object> a = attempt();
        assertThat(a.get("status")).isEqualTo("EVALUATED");
        assertThat((String) a.get("flags")).contains("RUBRIC_UNAVAILABLE").doesNotContain("PENDING");
        assertThat((String) row(write).get("flags")).contains("RUBRIC_UNAVAILABLE");
    }

    @Test
    @DisplayName("hard-coded code (ignores its input) scores 0 and is flagged, without calling the grader")
    void hardcoded() throws Exception {
        open("L1");
        String write = q("CODE_WRITE");
        long before = graderCalls();
        answer(write, Map.of("language", "java", "code",
                "public class Main { public static void main(String[] a) { System.out.println(3); } } // REFERENCE"));
        submit();

        assertThat(num(row(write).get("final_score"))).isZero();
        assertThat((String) row(write).get("flags")).contains("HARDCODED");
        assertThat((String) attempt().get("flags")).contains("HARDCODED");
        assertThat(graderCalls()).isEqualTo(before);
    }

    @Test
    @DisplayName("CODE_DEBUG counts hidden tests only (visible ones excluded) and earns nothing for the untouched bug")
    void debugHiddenOnly() throws Exception {
        open("L1");
        String debug = q("CODE_DEBUG");
        answer(debug, Map.of("language", "java", "code", GOOD.replace("REFERENCE", "FIXED")));
        submit();

        Map<String, Object> r = row(debug);
        assertThat(num(r.get("final_score"))).isCloseTo(points(debug), within(0.01));
        assertThat((String) r.get("evidence")).contains("\"hidden_total\": 4").contains("\"hidden_passed\": 4");
    }

    @Test
    @DisplayName("an attempt stuck in SUBMITTED for over 5 minutes is picked up; a fresh one isn't")
    void stuckSubmittedPickedUp() throws Exception {
        open("L1");
        submit();
        String id = (String) attempt().get("id");

        jdbc.update("update assessment_attempts set status = 'SUBMITTED', submitted_at = now() - interval '1 minute' where id = ?::uuid", id);
        sweeper.sweep();
        assertThat(attempt().get("status")).as("too recent").isEqualTo("SUBMITTED");

        jdbc.update("update assessment_attempts set submitted_at = now() - interval '6 minutes' where id = ?::uuid", id);
        assertThat(sweeper.sweep()).isPositive();
        assertThat(attempt().get("status")).isEqualTo("EVALUATED");
    }

    @Test
    @DisplayName("re-scoring replaces the scores: same totals, one SUBMIT run per answer")
    void rescoreIsIdempotent() throws Exception {
        open("L1");
        String write = q("CODE_WRITE");
        answer(write, Map.of("language", "java", "code", GOOD));
        submit();
        String id = (String) attempt().get("id");
        Object first = row(write).get("final_score");

        postJsonAs(admin, "/api/assessment/rescore/" + job + "/" + id, Map.of()).andExpect(status().isOk());
        postJsonAs(admin, "/api/assessment/rescore/" + job + "/" + id, Map.of()).andExpect(status().isOk());

        assertThat(row(write).get("final_score")).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from code_runs r join attempt_answers a on a.id = r.answer_id "
                + "where a.question_id = ?::uuid and r.mode = 'SUBMIT'", Integer.class, write)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from attempt_answers where attempt_id = ?::uuid", Integer.class, id))
                .as("every question has a graded row, answered or not").isEqualTo(12);
    }

    @Test
    @DisplayName("status goes SUBMITTED -> EVALUATING (while the code runs) -> EVALUATED")
    void statusSequence() throws Exception {
        open("L1");
        String write = q("CODE_WRITE");
        answer(write, Map.of("language", "java", "code", GOOD));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        STUB.on("POST", "/api/v2/execute", req -> {
            seen.add(jdbc.queryForObject("select status from assessment_attempts where job_id = ?::uuid", String.class, job));
            return StubResponse.json(200, Fixtures.pistonRun(PracticalStubs.runFake(req)));
        });
        submit();

        assertThat(seen).isNotEmpty().containsOnly("EVALUATING");
        assertThat(attempt().get("status")).isEqualTo("EVALUATED");
    }

    @Test
    @DisplayName("SCENARIO is 100% rubric over its key points; an empty answer scores 0 without calling the grader")
    void scenario() throws Exception {
        open("L2");
        String scenario = q("SCENARIO");
        answer(scenario, Map.of("text", "First I would look at the latency metrics and the connection pool."));
        submit();

        Map<String, Object> r = row(scenario);
        assertThat(num(r.get("final_score"))).isCloseTo(points(scenario), within(0.01));
        assertThat((String) r.get("evidence")).contains("KP1");

        // Another candidate leaves it blank.
        long before = graderCalls();
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint",
                Map.of("session_id", job, "candidate_email", "blank@example.com", "levels", List.of("L2"))).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        String blank = url.substring(url.indexOf("token=") + 6);
        mvc.perform(get(BASE + blank)).andExpect(status().isOk());
        postJson(BASE + blank + "/submit", Map.of()).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select a.final_score from attempt_answers a join assessment_attempts t on t.id = a.attempt_id "
                + "where t.candidate_email = 'blank@example.com' and a.question_id = ?::uuid", Double.class, scenario)).isZero();
        assertThat(graderCalls()).as("no grader call for empty answers").isEqualTo(before);
    }
}

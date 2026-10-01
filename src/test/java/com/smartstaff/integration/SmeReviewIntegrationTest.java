package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.entity.Role;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import com.smartstaff.util.ReviewRules;
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

/** F12 — SME validation: question-set approval gates invites; results are routed
 *  by policy, signed off with adjustments (scores recomputed), locked, and
 *  reopened only by an admin; every step is audited. */
class SmeReviewIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/candidate/assessment/";
    private static final String GOOD = "// REFERENCE\nScanner in = new Scanner(System.in);\nSystem.out.println(in.nextLong() + in.nextLong());";

    private Account admin;
    private Account sme;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        PracticalStubs.resetGrader();
        admin = newAdmin();
        sme = newReviewer();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
    }

    private Account newReviewer() throws Exception {
        String email = "sme-" + shortId() + "@test.local";
        postJsonAs(admin, "/api/auth/signup", Map.of("role", "reviewer", "name", "Dr SME", "email", email,
                "password", "Reviewer123x", "reviewer_skills", List.of("java"))).andExpect(status().isOk());
        var user = users.findByRoleAndEmailIgnoreCase(Role.REVIEWER, email).orElseThrow();
        return tokenFor(user);
    }

    private void assign(Account who, boolean approval, double sample, int theoryAtLeast) throws Exception {
        putJsonAs(admin, "/api/jobs/" + job + "/reviewers", Map.of("user_ids", List.of(who.user().getId().toString()),
                "review_policy", Map.of("question_set_approval_required", approval, "sample_rate_not_recommended", sample,
                        "review_if_theory_at_least", theoryAtLeast))).andExpect(status().isOk());
    }

    private void generate() throws Exception {
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1"))).andExpect(status().isOk());
    }

    private Map<String, Object> mintBody() {
        return Map.of("session_id", job, "candidate_email", "c-" + shortId() + "@example.com", "levels", List.of("L1"));
    }

    /** Mints, answers the coding question, submits; returns the attempt id. */
    private String takeAndSubmit() throws Exception {
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint", mintBody()).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        String token = url.substring(url.indexOf("token=") + 6);
        JsonNode view = bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
        String code = null;
        for (JsonNode x : view.path("questions")) if (x.path("type").asText().equals("CODE_WRITE")) code = x.path("question_id").asText();
        mvc.perform(put(BASE + token + "/answers/" + code).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("language", "java", "code", GOOD)))).andExpect(status().isOk());
        postJson(BASE + token + "/submit", Map.of()).andExpect(status().isOk());
        return latestAttempt();   // evaluation runs inline in tests
    }

    private String latestAttempt() {
        return jdbc.queryForObject("select id::text from assessment_attempts where job_id = ?::uuid order by created_at desc limit 1",
                String.class, job);
    }

    private String reviewFor(String subjectId) {
        return jdbc.queryForObject("select id::text from sme_reviews where subject_id = ?::uuid", String.class, subjectId);
    }

    private Map<String, Object> attemptRow(String id) {
        return jdbc.queryForMap("select status, theory_score, hands_on_score, overall_score, recommendation, validation_reason "
                + "from assessment_attempts where id = ?::uuid", id);
    }

    private void forceGate(String recommendation) throws Exception {
        Map<String, Object> s = new HashMap<>(Map.of("theory_weight", 0.3, "hands_on_weight", 0.7, "max_tab_switches", 5,
                "theory_practice_gap", 100));
        int t = recommendation.equals("PROCEED") ? 0 : 100;
        s.put("proceed_hands_on_min", t);
        s.put("proceed_theory_min", t);
        s.put("review_hands_on_min", t);
        putJsonAs(admin, "/api/assessment/scoring_settings/" + job, s).andExpect(status().isOk());
    }

    @Test
    @DisplayName("question-set approval: invites 409 until the SME approves; SME can edit a question from the review")
    void inviteBlockedUntilApproval() throws Exception {
        assign(sme, true, 0, 101);
        generate();
        String assessment = jdbc.queryForObject("select id::text from assessments where job_id = ?::uuid and is_current", String.class, job);
        String review = reviewFor(assessment);

        JsonNode blocked = bodyOf(postJsonAs(admin, "/api/invites/mint", mintBody()).andExpect(status().isConflict()));
        assertThat(blocked.toString()).contains("awaiting expert approval");

        JsonNode queue = bodyOf(getAs(sme, "/api/review/queue?subject_type=QUESTION_SET").andExpect(status().isOk()));
        assertThat(queue.path("reviews").findValuesAsText("review_id")).contains(review);
        JsonNode detail = bodyOf(getAs(sme, "/api/review/" + review).andExpect(status().isOk()));
        JsonNode mcq = null;
        for (JsonNode q : detail.path("questions")) if (q.path("type").asText().equals("MCQ")) mcq = q;
        putJsonAs(sme, "/api/review/question_sets/" + review + "/questions/" + mcq.path("question_id").asText(),
                Map.of("points", 7)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select points from assessment_questions where id = ?::uuid", Integer.class,
                mcq.path("question_id").asText())).isEqualTo(7);

        postJsonAs(sme, "/api/review/question_sets/" + review, Map.of("action", "APPROVE", "comment", "Good set"))
                .andExpect(status().isOk());
        postJsonAs(admin, "/api/invites/mint", mintBody()).andExpect(status().isOk());
        // Approved and locked: no more edits from the review.
        putJsonAs(sme, "/api/review/question_sets/" + review + "/questions/" + mcq.path("question_id").asText(),
                Map.of("points", 8)).andExpect(status().isConflict());
        assertThat(jdbc.queryForList("select action from review_audit where review_id = ?::uuid order by id", String.class, review))
                .containsExactly("CREATED", "QUESTION_EDITED", "APPROVED");
    }

    @Test
    @DisplayName("without an approval policy invites work at once; without reviewers results stay provisional EVALUATED")
    void noPolicyNoChange() throws Exception {
        generate();
        String id = takeAndSubmit();
        assertThat(attemptRow(id).get("status")).isEqualTo("EVALUATED");
        assertThat(jdbc.queryForObject("select count(*) from sme_reviews where job_id = ?::uuid", Integer.class, job)).isZero();
    }

    @Test
    @DisplayName("NOT_RECOMMENDED below the theory threshold and not sampled → VALIDATED by sampling, no reviewer")
    void sampledOut() throws Exception {
        assign(sme, false, 0, 101);
        generate();
        forceGate("NOT_RECOMMENDED");
        String id = takeAndSubmit();
        Map<String, Object> a = attemptRow(id);
        assertThat(a.get("recommendation")).isEqualTo("NOT_RECOMMENDED");
        assertThat(a.get("status")).isEqualTo("VALIDATED");
        assertThat(a.get("validation_reason")).isEqualTo("sampling");
        JsonNode row = bodyOf(getAs(admin, "/api/assessment/submissions/" + job)).path("submissions").get(0);
        assertThat(row.path("result_status").asText()).isEqualTo("VALIDATED");
        assertThat(row.path("validation_reason").asText()).isEqualTo("sampling");
        assertThat(row.has("reviewer_name")).isFalse();

        // Rate 1 → the same kind of result is always reviewed, and the decision matches ReviewRules.
        assign(sme, false, 1, 101);
        String id2 = takeAndSubmit();
        assertThat(attemptRow(id2).get("status")).isEqualTo("AWAITING_REVIEW");
        assertThat(ReviewRules.sampled(UUID.fromString(id2), 1)).isTrue();
    }

    @Test
    @DisplayName("sign-off: adjustments validated, scores recomputed, attempt VALIDATED + locked; admin reopen; full audit")
    void signOffLockReopen() throws Exception {
        assign(sme, false, 0, 101);
        generate();
        forceGate("PROCEED");
        String id = takeAndSubmit();
        Map<String, Object> before = attemptRow(id);
        assertThat(before.get("recommendation")).isEqualTo("PROCEED");
        assertThat(before.get("status")).isEqualTo("AWAITING_REVIEW");
        String review = reviewFor(id);
        assertThat(jdbc.queryForObject("select due_at > now() + interval '1 day' from sme_reviews where id = ?::uuid",
                Boolean.class, review)).isTrue();

        // A reviewer of another job can't touch it; the HR owner can't either.
        Account outsider = newReviewer();
        postJsonAs(outsider, "/api/review/" + review + "/claim", Map.of()).andExpect(status().isForbidden());
        postJsonAs(outsider, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF")).andExpect(status().isForbidden());
        getAs(outsider, "/api/assessment/attempts/" + id).andExpect(status().isForbidden());
        getAs(sme, "/api/assessment/attempts/" + id).andExpect(status().isOk());

        postJsonAs(sme, "/api/review/" + review + "/claim", Map.of()).andExpect(status().isOk());
        postJsonAs(outsider, "/api/review/" + review + "/claim", Map.of()).andExpect(status().isForbidden());

        Map<String, Object> q = jdbc.queryForMap("select a.question_id::text as qid, a.final_score, q.points from attempt_answers a "
                + "join assessment_questions q on q.id = a.question_id where a.attempt_id = ?::uuid and q.type = 'CODE_WRITE' and a.code is not null", id);
        String qid = (String) q.get("qid");
        double auto = ((Number) q.get("final_score")).doubleValue();
        int points = ((Number) q.get("points")).intValue();

        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF",
                "adjustments", List.of(Map.of("question_id", qid, "sme_score", 1)))).andExpect(status().isBadRequest());
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF",
                "adjustments", List.of(Map.of("question_id", qid, "sme_score", points + 1, "reason", "x")))).andExpect(status().isBadRequest());
        assertThat(attemptRow(id).get("status")).isEqualTo("AWAITING_REVIEW");   // rejected edits changed nothing

        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF", "comment", "Solution misses overflow",
                "adjustments", List.of(Map.of("question_id", qid, "sme_score", 1, "reason", "Ignores long overflow")),
                "recommendation", "HR_REVIEW")).andExpect(status().isOk());

        Map<String, Object> after = attemptRow(id);
        assertThat(after.get("status")).isEqualTo("VALIDATED");
        assertThat(after.get("recommendation")).isEqualTo("HR_REVIEW");
        double handsOn = jdbc.queryForObject("select 100.0 * sum(coalesce(a.final_score, 0)) / sum(q.points) from attempt_answers a "
                + "join assessment_questions q on q.id = a.question_id where a.attempt_id = ?::uuid and q.dimension = 'HANDS_ON'",
                Double.class, id);
        assertThat(((Number) after.get("hands_on_score")).doubleValue()).isCloseTo(handsOn, within(0.01));
        assertThat(((Number) after.get("hands_on_score")).doubleValue()).isLessThan(((Number) before.get("hands_on_score")).doubleValue());
        Map<String, Object> ans = jdbc.queryForMap("select sme_score, sme_reason, auto_score from attempt_answers "
                + "where attempt_id = ?::uuid and question_id = ?::uuid", id, qid);
        assertThat(((Number) ans.get("sme_score")).doubleValue()).isEqualTo(1.0);
        assertThat(ans.get("sme_reason")).isEqualTo("Ignores long overflow");
        JsonNode stored = json.readTree(jdbc.queryForObject("select adjustments::text from sme_reviews where id = ?::uuid", String.class, review));
        assertThat(stored.get(0).path("auto_score").asDouble()).isEqualTo(auto);
        assertThat(jdbc.queryForObject("select status from sme_reviews where id = ?::uuid", String.class, review)).isEqualTo("ADJUSTED");

        JsonNode row = bodyOf(getAs(admin, "/api/assessment/submissions/" + job)).path("submissions").get(0);
        assertThat(row.path("result_status").asText()).isEqualTo("VALIDATED");
        assertThat(row.path("review_status").asText()).isEqualTo("ADJUSTED");
        assertThat(row.path("reviewer_name").asText()).isEqualTo("Dr SME");
        assertThat(row.path("sme_comment").asText()).isEqualTo("Solution misses overflow");
        assertThat(row.has("signed_off_at")).isTrue();

        // Locked.
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF")).andExpect(status().isConflict());
        postJsonAs(admin, "/api/assessment/rescore/" + job + "/" + id, Map.of()).andExpect(status().isConflict());
        postJsonAs(sme, "/api/review/" + review + "/reopen", Map.of("reason", "please")).andExpect(status().isForbidden());
        postJsonAs(admin, "/api/review/" + review + "/reopen", Map.of()).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/review/" + review + "/reopen", Map.of("reason", "Candidate appeal")).andExpect(status().isOk());
        assertThat(attemptRow(id).get("status")).isEqualTo("AWAITING_REVIEW");
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF")).andExpect(status().isOk());

        assertThat(jdbc.queryForList("select action from review_audit where review_id = ?::uuid order by id", String.class, review))
                .containsExactly("CREATED", "CLAIMED", "SIGNED_OFF", "REOPENED", "SIGNED_OFF");
        String reopened = jdbc.queryForObject("select after::text from review_audit where review_id = ?::uuid and action = 'REOPENED'",
                String.class, review);
        assertThat(reopened).contains("Candidate appeal");
        String signed = jdbc.queryForObject("select before::text from review_audit where review_id = ?::uuid and action = 'SIGNED_OFF' "
                + "order by id limit 1", String.class, review);
        assertThat(signed).contains("PROCEED");
    }

    @Test
    @DisplayName("queue is scoped to assigned jobs; overdue list is admin-only")
    void queueAndOverdue() throws Exception {
        assign(sme, false, 0, 101);
        generate();
        forceGate("PROCEED");
        String id = takeAndSubmit();
        String review = reviewFor(id);
        Account outsider = newReviewer();

        assertThat(bodyOf(getAs(sme, "/api/review/queue")).path("reviews").findValuesAsText("review_id")).contains(review);
        assertThat(bodyOf(getAs(outsider, "/api/review/queue")).path("reviews").findValuesAsText("review_id")).doesNotContain(review);
        assertThat(bodyOf(getAs(admin, "/api/review/queue?job_id=" + job)).path("reviews").findValuesAsText("review_id")).contains(review);

        jdbc.update("update sme_reviews set due_at = now() - interval '1 hour' where id = ?::uuid", review);
        JsonNode overdue = bodyOf(getAs(admin, "/api/review/overdue").andExpect(status().isOk()));
        assertThat(overdue.path("reviews").findValuesAsText("review_id")).contains(review);
        getAs(sme, "/api/review/overdue").andExpect(status().isForbidden());

        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "RETURN_TO_HR")).andExpect(status().isBadRequest());
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "RETURN_TO_HR", "comment", "Not my area"))
                .andExpect(status().isOk());
        assertThat(attemptRow(id).get("status")).isEqualTo("EVALUATED");
    }
}

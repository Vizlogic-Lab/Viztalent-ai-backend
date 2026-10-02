package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.entity.Role;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F15 — calibration and question-quality analytics over seeded attempts and
 *  reviews with known statistics, the calibration-set import, and the SME's
 *  optional per-criterion scores on sign-off. */
class AnalyticsIntegrationTest extends IntegrationTestBase {

    private static final String CW_EVIDENCE = "[{\"kind\":\"criterion\",\"criterion\":\"approach\",\"score\":8,\"max\":10},"
            + "{\"kind\":\"criterion\",\"criterion\":\"readability\",\"score\":5,\"max\":6}]";
    private static final String SC_EVIDENCE = "[{\"kind\":\"criterion\",\"criterion\":\"KP1\",\"score\":1,\"max\":1},"
            + "{\"kind\":\"criterion\",\"criterion\":\"KP2\",\"score\":0,\"max\":1}]";

    private Account admin;

    @BeforeEach
    void setUp() {
        PracticalStubs.resetGrader();
        admin = newAdmin();
    }

    // ── seeding ─────────────────────────────────────────────────────────

    private String assessment(String job) {
        return seedReadyAssessment(job);
    }

    private String question(String assessment, int seq, String type, int points) {
        String dim = type.equals("MCQ") || type.equals("MSQ") ? "THEORY" : "HANDS_ON";
        String comp = switch (type) {
            case "MCQ", "MSQ" -> "CONCEPTS";
            case "SCENARIO" -> "APPLICATION";
            case "LOGIC" -> "LOGIC";
            default -> "CODING";
        };
        return jdbc.queryForObject("insert into assessment_questions (assessment_id, level, seq, type, dimension, competency, points, "
                + "prompt, validated) values (?::uuid, 'L1', ?, ?, ?, ?, ?, ?, true) returning id::text",
                String.class, assessment, seq, type, dim, comp, points, type + " question " + seq);
    }

    private String attempt(String job, String assessment, String email, String status, String engineRec, String rec, int minutesAgo) {
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String invite = jdbc.queryForObject("insert into invites (token_hash, kind, job_id, candidate_email, levels, expires_at) "
                + "values (?, 'ASSESSMENT', ?::uuid, ?, '[\"L1\"]', now() + interval '1 day') returning id::text", String.class, hash, job, email);
        int version = jdbc.queryForObject("select version from assessments where id = ?::uuid", Integer.class, assessment);
        return jdbc.queryForObject("insert into assessment_attempts (invite_id, assessment_id, version, level, job_id, candidate_email, "
                + "status, deadline_at, submitted_at, engine_recommendation, recommendation) values (?::uuid, ?::uuid, ?, 'L1', ?::uuid, ?, ?, "
                + "now(), now() - (? * interval '1 minute'), ?, ?) returning id::text",
                String.class, invite, assessment, version, job, email, status, minutesAgo, engineRec, rec);
    }

    /** sme null = the SME accepted the engine's score. */
    private void answer(String attempt, String question, double engine, Double sme, String flags, String evidence, String smeCriteria) {
        jdbc.update("insert into attempt_answers (attempt_id, question_id, engine_score, final_score, sme_score, flags, evidence, sme_criteria) "
                + "values (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)",
                attempt, question, engine, sme == null ? engine : sme, sme, flags, evidence, smeCriteria);
    }

    private void answer(String attempt, String question, double score) {
        answer(attempt, question, score, null, "[]", "[]", null);
    }

    private void review(String job, String attempt, String status, String finalRec, int minutesAgo) {
        jdbc.update("insert into sme_reviews (subject_type, subject_id, job_id, status, final_recommendation, signed_off_at) "
                + "values ('ATTEMPT', ?::uuid, ?::uuid, ?, ?, now() - (? * interval '1 minute'))", attempt, job, status, finalRec, minutesAgo);
    }

    private static JsonNode find(JsonNode list, String key, String value) {
        for (JsonNode n : list) if (n.path(key).asText().equals(value)) return n;
        throw new AssertionError("no " + key + "=" + value + " in " + list);
    }

    // ── 1: engine vs SME ────────────────────────────────────────────────

    @Test
    @DisplayName("calibration: agreement per type, criterion and job; ungraded and unreviewed answers don't count")
    void calibrationAgreement() throws Exception {
        String job = createJob(admin, "Calibration Job " + shortId(), "java, sql");
        String set = assessment(job);
        String mcq = question(set, 1, "MCQ", 5), sc = question(set, 2, "SCENARIO", 10), cw = question(set, 3, "CODE_WRITE", 20);

        // A1: all accepted.
        String a1 = attempt(job, set, "a1@example.com", "VALIDATED", "PROCEED", "PROCEED", 40);
        answer(a1, mcq, 5);
        answer(a1, sc, 6, null, "[]", SC_EVIDENCE, null);
        answer(a1, cw, 14, null, "[]", CW_EVIDENCE, null);
        review(job, a1, "APPROVED", "PROCEED", 40);
        // A2: scenario 8 → 4 (40 points off), no criterion detail.
        String a2 = attempt(job, set, "a2@example.com", "VALIDATED", "PROCEED", "PROCEED", 30);
        answer(a2, mcq, 5);
        answer(a2, sc, 8, 4.0, "[]", SC_EVIDENCE, null);
        answer(a2, cw, 14, null, "[]", CW_EVIDENCE, null);
        review(job, a2, "ADJUSTED", "PROCEED", 30);
        // A3: code 14 → 12 (exactly 10 points: within), SME approach 6 vs engine 8 of 10.
        String a3 = attempt(job, set, "a3@example.com", "VALIDATED", "PROCEED", "PROCEED", 20);
        answer(a3, mcq, 0);
        answer(a3, sc, 6, null, "[]", SC_EVIDENCE, null);
        answer(a3, cw, 14, 12.0, "[]", CW_EVIDENCE, "{\"approach\": 6}");
        review(job, a3, "ADJUSTED", "PROCEED", 20);
        // A4: code 18 → 10 (40 off), scenario ungraded (rubric unavailable).
        String a4 = attempt(job, set, "a4@example.com", "VALIDATED", "PROCEED", "HR_REVIEW", 10);
        answer(a4, mcq, 5);
        answer(a4, sc, 0, null, "[\"RUBRIC_UNAVAILABLE\"]", "[]", null);
        answer(a4, cw, 18, 10.0, "[]", CW_EVIDENCE, null);
        review(job, a4, "ADJUSTED", "HR_REVIEW", 10);
        // Not counted: an open review, sampling (no review) and an HR preview.
        String open = attempt(job, set, "open@example.com", "AWAITING_REVIEW", "PROCEED", "PROCEED", 5);
        answer(open, cw, 20, 0.0, "[]", CW_EVIDENCE, null);
        review(job, open, "IN_REVIEW", null, 5);
        String sampled = attempt(job, set, "s@example.com", "VALIDATED", "NOT_RECOMMENDED", "NOT_RECOMMENDED", 5);
        answer(sampled, cw, 2);
        String preview = attempt(job, set, "assessment-preview@viztalent.internal", "VALIDATED", "PROCEED", "PROCEED", 5);
        answer(preview, mcq, 5, 0.0, "[]", "[]", null);
        review(job, preview, "ADJUSTED", "PROCEED", 5);

        JsonNode c = bodyOf(getAs(admin, "/api/analytics/calibration?job_id=" + job).andExpect(status().isOk()));
        assertThat(c.path("tolerance_pct").asDouble()).isEqualTo(10.0);
        assertThat(c.path("target").asDouble()).isEqualTo(0.85);
        JsonNode overall = c.path("overall");
        assertThat(overall.path("pairs").asInt()).isEqualTo(11);
        assertThat(overall.path("within_tolerance").asInt()).isEqualTo(9);
        assertThat(overall.path("share").asDouble()).isEqualTo(0.818);
        assertThat(overall.path("meets_target").asBoolean()).isFalse();
        assertThat(c.path("ungraded").asInt()).isEqualTo(1);

        JsonNode byType = c.path("by_type");
        assertThat(find(byType, "type", "MCQ").path("share").asDouble()).isEqualTo(1.0);
        JsonNode scenario = find(byType, "type", "SCENARIO");
        assertThat(scenario.path("pairs").asInt()).isEqualTo(3);
        assertThat(scenario.path("within_tolerance").asInt()).isEqualTo(2);
        assertThat(scenario.path("mean_signed_diff_pct").asDouble()).as("engine more generous").isEqualTo(13.33);
        JsonNode code = find(byType, "type", "CODE_WRITE");
        assertThat(code.path("pairs").asInt()).isEqualTo(4);
        assertThat(code.path("share").asDouble()).isEqualTo(0.75);

        JsonNode byCriterion = c.path("by_criterion");
        JsonNode approach = find(byCriterion, "criterion", "CODE_WRITE:approach");
        assertThat(approach.path("pairs").asInt()).isEqualTo(3);
        assertThat(approach.path("within_tolerance").asInt()).as("A3's SME approach score is 20 points lower").isEqualTo(2);
        assertThat(approach.path("unattributed").asInt()).isEqualTo(1);
        JsonNode readability = find(byCriterion, "criterion", "CODE_WRITE:readability");
        assertThat(readability.path("pairs").asInt()).isEqualTo(2);
        assertThat(readability.path("unattributed").asInt()).isEqualTo(2);
        JsonNode keyPoints = find(byCriterion, "criterion", "SCENARIO:key_point");
        assertThat(keyPoints.path("pairs").asInt()).isEqualTo(4);
        assertThat(keyPoints.path("share").asDouble()).isEqualTo(1.0);
        assertThat(keyPoints.path("unattributed").asInt()).isEqualTo(2);

        assertThat(c.path("by_job")).hasSize(1);
        assertThat(c.path("by_job").get(0).path("job_title").asText()).startsWith("Calibration Job");

        JsonNode most = c.path("most_adjusted");
        assertThat(most).hasSize(2);
        assertThat(most.get(0).path("question_id").asText()).isEqualTo(cw);
        assertThat(most.get(0).path("adjusted").asInt()).isEqualTo(2);
        assertThat(most.get(0).path("reviews").asInt()).isEqualTo(4);
        assertThat(most.get(1).path("question_id").asText()).isEqualTo(sc);

        JsonNode proceed = find(c.path("recommendations"), "engine_recommendation", "PROCEED");
        assertThat(proceed.path("reviews").asInt()).isEqualTo(4);
        assertThat(proceed.path("sme_agreed").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("analytics are admin-only")
    void adminOnly() throws Exception {
        Account employee = newEmployee();
        getAs(employee, "/api/analytics/calibration").andExpect(status().isForbidden());
        getAs(employee, "/api/analytics/questions").andExpect(status().isForbidden());
        postJsonAs(employee, "/api/analytics/calibration_set", Map.of("name", "x", "items", List.of())).andExpect(status().isForbidden());
        getAs(null, "/api/analytics/bank_items").andExpect(status().isUnauthorized());
        String email = "sme-" + shortId() + "@test.local";
        postJsonAs(admin, "/api/auth/signup", Map.of("role", "reviewer", "name", "Dr SME", "email", email,
                "password", "Reviewer123x", "reviewer_skills", List.of("java"))).andExpect(status().isOk());
        Account sme = tokenFor(users.findByRoleAndEmailIgnoreCase(Role.REVIEWER, email).orElseThrow());
        getAs(sme, "/api/analytics/calibration").andExpect(status().isForbidden());
    }

    // ── 3: sample rate ──────────────────────────────────────────────────

    @Test
    @DisplayName("sample rate: suggested lower only with 50 reviewed NOT_RECOMMENDED results at ≥ 90% agreement; never changed")
    void sampleRateSuggestion() throws Exception {
        String job = createJob(admin, "Sampling Job " + shortId(), "java");
        jdbc.update("update jobs set review_policy = '{\"question_set_approval_required\": false, "
                + "\"sample_rate_not_recommended\": 0.2, \"review_if_theory_at_least\": 70}'::jsonb where id = ?::uuid", job);
        String set = assessment(job);
        int n = 0;
        for (int i = 0; i < 10; i++) seedNotRecommended(job, set, n++, false, 1000 + i);   // oldest: SME disagreed
        for (int i = 0; i < 39; i++) seedNotRecommended(job, set, n++, true, 500 + i);

        JsonNode before = find(bodyOf(getAs(admin, "/api/analytics/calibration?job_id=" + job)).path("sample_rate"), "job_id", job);
        assertThat(before.path("reviews").asInt()).isEqualTo(49);
        assertThat(before.path("suggest").asBoolean()).isFalse();
        assertThat(before.path("reason").asText()).contains("Needs 50");

        for (int i = 0; i < 7; i++) seedNotRecommended(job, set, n++, true, 100 + i);
        for (int i = 0; i < 4; i++) seedNotRecommended(job, set, n++, false, 50 + i);

        JsonNode c = bodyOf(getAs(admin, "/api/analytics/calibration?job_id=" + job));
        JsonNode after = find(c.path("sample_rate"), "job_id", job);
        assertThat(after.path("reviews").asInt()).isEqualTo(50);
        assertThat(after.path("agreement").asDouble()).as("46 of the newest 50").isEqualTo(0.92);
        assertThat(after.path("current_rate").asDouble()).isEqualTo(0.2);
        assertThat(after.path("suggest").asBoolean()).isTrue();
        assertThat(after.path("suggested_rate").asDouble()).isEqualTo(0.1);
        JsonNode nr = find(c.path("recommendations"), "engine_recommendation", "NOT_RECOMMENDED");
        assertThat(nr.path("reviews").asInt()).isEqualTo(60);
        assertThat(nr.path("sme_agreed").asInt()).isEqualTo(46);
        assertThat(jdbc.queryForObject("select review_policy ->> 'sample_rate_not_recommended' from jobs where id = ?::uuid",
                String.class, job)).as("suggest only").isEqualTo("0.2");
    }

    private void seedNotRecommended(String job, String set, int i, boolean agreed, int minutesAgo) {
        String rec = agreed ? "NOT_RECOMMENDED" : "HR_REVIEW";
        String a = attempt(job, set, "nr" + i + "@example.com", "VALIDATED", "NOT_RECOMMENDED", rec, minutesAgo);
        review(job, a, agreed ? "APPROVED" : "ADJUSTED", rec, minutesAgo);
    }

    // ── 2: question quality ─────────────────────────────────────────────

    @Test
    @DisplayName("question stats after 20 attempts: too easy / too hard / low discrimination; a flagged bank item is excluded until re-enabled")
    void questionQualityAndBankExclusion() throws Exception {
        uploadFile(admin, "/api/questions/upload", "file", "bank.json", """
                [{"type": "MCQ", "level": "L1", "skill": "java", "question": "Which keyword makes a field constant?",
                  "options": ["static", "final", "const", "volatile"], "correct_indices": [1]}]
                """).andExpect(status().isOk());
        String item = jdbc.queryForObject("select id::text from question_bank_items", String.class);

        String job = createJob(admin, "Quality Job " + shortId(), "java, spring, sql");
        String set = assessment(job);
        String easy = question(set, 1, "MCQ", 5), hard = question(set, 2, "LOGIC", 5);
        String good = question(set, 3, "SCENARIO", 10), code = question(set, 4, "CODE_WRITE", 20);
        jdbc.update("update assessment_questions set origin = 'BANK', bank_item_id = ?::uuid where id = ?::uuid", item, easy);
        for (int i = 0; i < 20; i++) {
            String a = attempt(job, set, "q" + i + "@example.com", "EVALUATED", "PROCEED", "PROCEED", 30 + i);
            answer(a, easy, 5);
            answer(a, hard, 0);
            answer(a, good, i % 2 == 0 ? 10 : 0);
            answer(a, code, i % 2 == 0 ? 20 : 0);
        }
        String preview = attempt(job, set, "assessment-preview@viztalent.internal", "EVALUATED", "PROCEED", "PROCEED", 1);
        answer(preview, hard, 5);

        JsonNode stats = bodyOf(getAs(admin, "/api/analytics/questions?job_id=" + job).andExpect(status().isOk()));
        assertThat(stats.path("min_attempts_for_flags").asInt()).isEqualTo(20);
        JsonNode e = find(stats.path("questions"), "question_id", easy);
        assertThat(e.path("attempts").asInt()).isEqualTo(20);
        assertThat(e.path("full_marks_rate").asDouble()).isEqualTo(1.0);
        assertThat(e.path("bank_item_id").asText()).isEqualTo(item);
        assertThat(texts(e.path("flags"))).containsExactly("TOO_EASY", "LOW_DISCRIMINATION");
        JsonNode h = find(stats.path("questions"), "question_id", hard);
        assertThat(h.path("attempts").asInt()).as("the HR preview is not counted").isEqualTo(20);
        assertThat(texts(h.path("flags"))).containsExactly("TOO_HARD", "LOW_DISCRIMINATION");
        JsonNode g = find(stats.path("questions"), "question_id", good);
        assertThat(g.path("mean_score").asDouble()).isEqualTo(5.0);
        assertThat(g.path("mean_percent").asDouble()).isEqualTo(50.0);
        assertThat(g.path("pass_rate").asDouble()).isEqualTo(0.5);
        assertThat(g.path("any_credit_rate").asDouble()).isEqualTo(0.5);
        assertThat(g.path("discrimination").asDouble()).isEqualTo(1.0);
        assertThat(texts(g.path("flags"))).isEmpty();
        JsonNode flagged = bodyOf(getAs(admin, "/api/analytics/questions?flagged=true&job_id=" + job));
        assertThat(texts(flagged.path("questions"), "question_id")).containsExactlyInAnyOrder(easy, hard);

        // Refresh: the bank item is flagged and excluded from generation.
        JsonNode refreshed = bodyOf(postJsonAs(admin, "/api/analytics/bank_items/refresh", Map.of()).andExpect(status().isOk()));
        assertThat(texts(refreshed.path("newly_excluded"))).containsExactly(item);
        assertThat(jdbc.queryForObject("select quality_excluded from question_bank_items where id = ?::uuid", Boolean.class, item)).isTrue();
        JsonNode bank = find(bodyOf(getAs(admin, "/api/analytics/bank_items")).path("bank_items"), "bank_item_id", item);
        assertThat(bank.path("excluded").asBoolean()).isTrue();
        assertThat(texts(bank.path("flags"))).containsExactly("TOO_EASY", "LOW_DISCRIMINATION");
        assertThat(bank.path("stats").path("attempts").asInt()).isEqualTo(20);

        String other = createJob(admin, "Java Backend Developer " + shortId(), "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", other, "question_source", "custom", "levels", List.of("L1"))).andExpect(status().isOk());
        assertThat(bankUses(other, item)).as("excluded from selection").isZero();

        // Re-enabled by an admin: used again, and its stats start a fresh window.
        JsonNode enabled = bodyOf(postJsonAs(admin, "/api/analytics/bank_items/" + item + "/enable", Map.of()).andExpect(status().isOk()));
        assertThat(enabled.path("excluded").asBoolean()).isFalse();
        assertThat(enabled.path("reenabled_by").asText()).isEqualTo(admin.user().publicId());
        assertThat(texts(bodyOf(postJsonAs(admin, "/api/analytics/bank_items/refresh", Map.of())).path("newly_excluded"))).isEmpty();
        assertThat(find(bodyOf(getAs(admin, "/api/analytics/bank_items")).path("bank_items"), "bank_item_id", item)
                .path("stats").path("attempts").asInt()).isZero();
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", other, "question_source", "custom", "levels", List.of("L1"))).andExpect(status().isOk());
        assertThat(bankUses(other, item)).isEqualTo(1);

        postJsonAs(admin, "/api/analytics/bank_items/" + UUID.randomUUID() + "/enable", Map.of()).andExpect(status().isNotFound());
    }

    private int bankUses(String job, String item) {
        return jdbc.queryForObject("select count(*) from assessment_questions q join assessments a on a.id = q.assessment_id "
                + "where a.job_id = ?::uuid and q.bank_item_id = ?::uuid", Integer.class, job, item);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    // ── 4: calibration set ──────────────────────────────────────────────

    @Test
    @DisplayName("calibration set: SME-graded answers scored by the current engine, per item and per criterion; re-run keeps history")
    void calibrationSet() throws Exception {
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakeGemini(STUB, Map.of());   // grader: full marks, evidence quoted from the answer
        String job = createJob(admin, "Calibration Set Job " + shortId(), "java");
        String set = assessment(job);
        String mcq = question(set, 1, "MCQ", 5), logic = question(set, 2, "LOGIC", 5), sc = question(set, 3, "SCENARIO", 10);
        jdbc.update("update assessment_questions set options = '[\"a\", \"b\", \"c\"]', correct_indices = '[1]' where id = ?::uuid", mcq);
        jdbc.update("update assessment_questions set model_answer = '42' where id = ?::uuid", logic);
        jdbc.update("update assessment_questions set model_answer = 'Index the column.', "
                + "key_points = '[\"adds an index\", \"explains the write cost\"]' where id = ?::uuid", sc);

        List<Map<String, Object>> items = List.of(
                Map.of("question_id", mcq, "answer", Map.of("selected_indices", List.of(1)), "sme_score", 5),
                Map.of("question_id", mcq, "answer", Map.of("selected_indices", List.of(0)), "sme_score", 3, "note", "partial credit"),
                Map.of("question_id", logic, "answer", Map.of("text_answer", " 42 "), "sme_score", 5),
                Map.of("question_id", sc, "answer", Map.of("text_answer", "I would add an index on email; writes get slower."),
                        "sme_score", 5, "sme_criteria", Map.of("KP1", 1, "kp2", 0)));

        // Validation.
        postJsonAs(admin, "/api/analytics/calibration_set", Map.of("name", "x", "items", List.of())).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/analytics/calibration_set", Map.of("items", items)).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/analytics/calibration_set", Map.of("name", "x", "items", List.of(
                Map.of("question_id", UUID.randomUUID().toString(), "answer", Map.of(), "sme_score", 1)))).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/analytics/calibration_set", Map.of("name", "x", "items", List.of(
                Map.of("question_id", mcq, "answer", Map.of(), "sme_score", 6)))).andExpect(status().isBadRequest());
        JsonNode badCriterion = bodyOf(postJsonAs(admin, "/api/analytics/calibration_set", Map.of("name", "x", "items", List.of(
                Map.of("question_id", sc, "answer", Map.of("text_answer", "x"), "sme_score", 1, "sme_criteria", Map.of("approach", 1)))))
                .andExpect(status().isBadRequest()));
        assertThat(badCriterion.toString()).contains("approach");
        postJsonAs(admin, "/api/analytics/calibration_set", Map.of("name", "x", "items", List.of(
                Map.of("question_id", sc, "answer", Map.of("text_answer", "x"), "sme_score", 1, "sme_criteria", Map.of("KP1", 2)))))
                .andExpect(status().isBadRequest());

        JsonNode run = bodyOf(postJsonAs(admin, "/api/analytics/calibration_set", Map.of("name", "Prompt v1 baseline", "items", items))
                .andExpect(status().isOk()));
        assertThat(run.path("status").asText()).isEqualTo("DONE");
        assertThat(run.path("item_count").asInt()).isEqualTo(4);
        JsonNode summary = run.path("summary");
        assertThat(summary.path("scored").asInt()).isEqualTo(4);
        assertThat(summary.path("overall").path("pairs").asInt()).isEqualTo(4);
        assertThat(summary.path("overall").path("within_tolerance").asInt()).isEqualTo(2);
        assertThat(find(summary.path("by_type"), "type", "MCQ").path("within_tolerance").asInt()).isEqualTo(1);
        assertThat(find(summary.path("by_type"), "type", "LOGIC").path("share").asDouble()).isEqualTo(1.0);
        JsonNode kp = find(summary.path("by_criterion"), "criterion", "SCENARIO:key_point");
        assertThat(kp.path("pairs").asInt()).isEqualTo(2);
        assertThat(kp.path("within_tolerance").asInt()).isEqualTo(1);

        JsonNode results = run.path("results");
        assertThat(results.get(1).path("engine_score").asDouble()).isZero();
        assertThat(results.get(1).path("diff_pct").asDouble()).isEqualTo(-60.0);
        assertThat(results.get(1).path("within_tolerance").asBoolean()).isFalse();
        assertThat(results.get(1).path("note").asText()).isEqualTo("partial credit");
        JsonNode scenario = results.get(3);
        assertThat(scenario.path("engine_score").asDouble()).isEqualTo(10.0);
        assertThat(scenario.path("diff_pct").asDouble()).isEqualTo(50.0);
        assertThat(find(scenario.path("criteria"), "criterion", "KP2").path("within_tolerance").asBoolean()).isFalse();

        // Nothing written to any attempt.
        assertThat(jdbc.queryForObject("select count(*) from assessment_attempts where job_id = ?::uuid", Integer.class, job)).isZero();

        // Re-run with the grader down: the scenario can't be scored and doesn't count as a disagreement.
        jdbc.update("delete from app_settings where key = 'gemini_api_key'");
        String id = run.path("id").asText();
        JsonNode rerun = bodyOf(postJsonAs(admin, "/api/analytics/calibration_set/" + id + "/rerun", Map.of()).andExpect(status().isOk()));
        assertThat(rerun.path("id").asText()).isNotEqualTo(id);
        assertThat(rerun.path("source_run").asText()).isEqualTo(id);
        assertThat(rerun.path("summary").path("not_scored").asInt()).isEqualTo(1);
        assertThat(rerun.path("summary").path("overall").path("pairs").asInt()).isEqualTo(3);
        assertThat(rerun.path("results").get(3).path("scored").asBoolean()).isFalse();
        assertThat(rerun.path("results").get(3).path("not_scored_reason").asText()).contains("AI grader unavailable");

        JsonNode list = bodyOf(getAs(admin, "/api/analytics/calibration_set").andExpect(status().isOk()));
        assertThat(texts(list.path("runs"), "id")).contains(id, rerun.path("id").asText());
        assertThat(bodyOf(getAs(admin, "/api/analytics/calibration_set/" + id)).path("results")).hasSize(4);
        getAs(admin, "/api/analytics/calibration_set/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ── sign-off with per-criterion SME scores (real evaluation) ─────────

    @Test
    @DisplayName("evaluation keeps the engine's score and verdict; SME criterion scores are validated and feed per-criterion agreement")
    void signOffWithCriteria() throws Exception {
        String email = "sme-" + shortId() + "@test.local";
        postJsonAs(admin, "/api/auth/signup", Map.of("role", "reviewer", "name", "Dr SME", "email", email,
                "password", "Reviewer123x", "reviewer_skills", List.of("java"))).andExpect(status().isOk());
        Account sme = tokenFor(users.findByRoleAndEmailIgnoreCase(Role.REVIEWER, email).orElseThrow());
        String job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
        putJsonAs(admin, "/api/jobs/" + job + "/reviewers", Map.of("user_ids", List.of(sme.user().getId().toString()),
                "review_policy", Map.of("question_set_approval_required", false, "sample_rate_not_recommended", 0,
                        "review_if_theory_at_least", 101))).andExpect(status().isOk());
        putJsonAs(admin, "/api/assessment/scoring_settings/" + job, Map.of("theory_weight", 0.3, "hands_on_weight", 0.7,
                "max_tab_switches", 5, "theory_practice_gap", 100, "proceed_hands_on_min", 0, "proceed_theory_min", 0,
                "review_hands_on_min", 0)).andExpect(status().isOk());
        postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1")))
                .andExpect(status().isOk());

        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job,
                "candidate_email", "c-" + shortId() + "@example.com", "levels", List.of("L1"))).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        String token = url.substring(url.indexOf("token=") + 6);
        JsonNode view = bodyOf(mvc.perform(get("/api/candidate/assessment/" + token)).andExpect(status().isOk()));
        String qid = null;
        for (JsonNode x : view.path("questions")) if (x.path("type").asText().equals("CODE_WRITE")) qid = x.path("question_id").asText();
        mvc.perform(put("/api/candidate/assessment/" + token + "/answers/" + qid).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("language", "java",
                        "code", "// REFERENCE\nScanner in = new Scanner(System.in);\nSystem.out.println(in.nextLong() + in.nextLong());"))))
                .andExpect(status().isOk());
        postJson("/api/candidate/assessment/" + token + "/submit", Map.of()).andExpect(status().isOk());
        String attempt = jdbc.queryForObject("select id::text from assessment_attempts where job_id = ?::uuid", String.class, job);
        Map<String, Object> graded = jdbc.queryForMap("select engine_score, final_score from attempt_answers "
                + "where attempt_id = ?::uuid and question_id = ?::uuid", attempt, qid);
        assertThat(graded.get("engine_score")).isEqualTo(graded.get("final_score"));
        assertThat(jdbc.queryForObject("select engine_recommendation from assessment_attempts where id = ?::uuid", String.class, attempt))
                .isEqualTo("PROCEED");

        String review = jdbc.queryForObject("select id::text from sme_reviews where subject_id = ?::uuid", String.class, attempt);
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF", "adjustments", List.of(Map.of(
                "question_id", qid, "sme_score", 10, "reason", "x", "criteria", List.of(Map.of("criterion", "speed", "sme_score", 1))))))
                .andExpect(status().isBadRequest());
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF", "adjustments", List.of(Map.of(
                "question_id", qid, "sme_score", 10, "reason", "x", "criteria", List.of(Map.of("criterion", "approach", "sme_score", 11))))))
                .andExpect(status().isBadRequest());
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF", "recommendation", "HR_REVIEW",
                "adjustments", List.of(Map.of("question_id", qid, "sme_score", 10, "reason", "Brute force",
                        "criteria", List.of(Map.of("criterion", "Approach", "sme_score", 4)))))).andExpect(status().isOk());

        Map<String, Object> after = jdbc.queryForMap("select engine_score, final_score, sme_criteria::text as crit from attempt_answers "
                + "where attempt_id = ?::uuid and question_id = ?::uuid", attempt, qid);
        assertThat(after.get("engine_score")).as("the engine's score survives sign-off").isEqualTo(graded.get("engine_score"));
        assertThat(((Number) after.get("final_score")).doubleValue()).isEqualTo(10.0);
        assertThat(json.readTree((String) after.get("crit")).path("approach").asDouble()).isEqualTo(4.0);
        Map<String, Object> a = jdbc.queryForMap("select engine_recommendation, recommendation from assessment_attempts where id = ?::uuid", attempt);
        assertThat(a.get("engine_recommendation")).isEqualTo("PROCEED");
        assertThat(a.get("recommendation")).isEqualTo("HR_REVIEW");

        JsonNode c = bodyOf(getAs(admin, "/api/analytics/calibration?job_id=" + job));
        JsonNode approach = find(c.path("by_criterion"), "criterion", "CODE_WRITE:approach");
        assertThat(approach.path("pairs").asInt()).isEqualTo(1);
        assertThat(approach.path("within_tolerance").asInt()).as("engine 10/10 vs SME 4/10").isZero();
        assertThat(find(c.path("by_criterion"), "criterion", "CODE_WRITE:complexity").path("unattributed").asInt()).isEqualTo(1);
        assertThat(find(c.path("recommendations"), "engine_recommendation", "PROCEED").path("sme_agreed").asInt()).isZero();
    }
}

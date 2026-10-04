package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartstaff.entity.Role;
import com.smartstaff.service.impl.EvaluationRetryScheduler;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F16 — recruiter interview questions asked first, AI-filled questions and
 *  expected points, grading of the answers with quoted evidence, interview
 *  results in Theory/Hands-on and the SME review, and the phone-silence fix. */
class InterviewGradingIntegrationTest extends IntegrationTestBase {

    private static final String AUTH_TOKEN = "twilio-secret-token";
    private static final String PUBLIC_URL = "https://tunnel.example.com";
    private static final Pattern CRITERION = Pattern.compile("^- (EP\\d+): .*\\(max ([0-9.]+)\\)$", Pattern.MULTILINE);

    @Autowired EvaluationRetryScheduler sweep;

    private Account admin;
    private String job;
    private final AtomicInteger prepCalls = new AtomicInteger();
    private final AtomicInteger gradeCalls = new AtomicInteger();
    private final List<String> prepPrompts = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, PUBLIC_URL);
        setGeminiKey(admin, "AIza-test-key-123");
        prepCalls.set(0);
        gradeCalls.set(0);
        prepPrompts.clear();
        fakeGemini();
    }

    // ── fake Gemini: prep (3 theory + 3 practical offered) and grading ───

    private void fakeGemini() {
        STUB.on("POST", "/v1beta/models/", req -> {
            String prompt = PracticalStubs.promptOf(req);
            if (req.body().contains("practical_questions")) {
                prepCalls.incrementAndGet();
                prepPrompts.add(prompt);
                return StubResponse.json(200, Fixtures.geminiText(prep(prompt)));
            }
            gradeCalls.incrementAndGet();
            return StubResponse.json(200, Fixtures.geminiText(grade(prompt)));
        });
    }

    private String prep(String prompt) {
        ObjectNode o = json.createObjectNode();
        o.put("intro", "Hello and welcome.");
        o.put("outro", "Thanks, we'll be in touch.");
        ArrayNode qs = o.putArray("questions");
        ArrayNode ps = o.putArray("practical_questions");
        for (int i = 1; i <= 3; i++) {
            ObjectNode q = qs.addObject();
            q.put("category", "Technical");
            q.put("skill", "java");
            q.put("question", "AI concept question " + i + "?");
            q.putArray("expected_points").add("concept point " + i + "a").add("concept point " + i + "b");
            ObjectNode p = ps.addObject();
            p.put("category", "Practical");
            p.put("skill", "spring");
            p.put("question", "Walk me through how you would debug problem " + i + ".");
            p.putArray("expected_points").add("debug point " + i + "a").add("debug point " + i + "b");
        }
        ArrayNode rec = o.putArray("recruiter_expected_points");
        Matcher m = Pattern.compile("\\bR(\\d+)\\b").matcher(prompt.substring(Math.max(0, prompt.indexOf("recruiter_expected_points"))));
        while (m.find()) {
            ObjectNode r = rec.addObject();
            r.put("index", Integer.parseInt(m.group(1)));
            r.putArray("expected_points").add("AI point one").add("AI point two");
        }
        return o.toString();
    }

    /** Full marks with evidence quoted from the answer; an answer containing NOEVIDENCE gets
     *  full marks with evidence that isn't in it (the grader must reject that). */
    private String grade(String prompt) {
        int a = prompt.indexOf("<<<CANDIDATE_ANSWER"), b = prompt.indexOf("CANDIDATE_ANSWER>>>");
        String answer = prompt.substring(prompt.indexOf('\n', a) + 1, b).trim();
        String evidence = answer.contains("NOEVIDENCE") ? "words the candidate never said" : answer.substring(0, Math.min(20, answer.length()));
        ObjectNode o = json.createObjectNode();
        ArrayNode criteria = o.putArray("criteria");
        Matcher m = CRITERION.matcher(prompt);
        while (m.find()) {
            double max = Double.parseDouble(m.group(2));
            criteria.addObject().put("criterion", m.group(1)).put("score", max).put("max", max)
                    .put("evidence", evidence).put("comment", "covered");
        }
        return o.toString();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String base() {
        return "/api/jobs/" + job + "/interview_questions";
    }

    private String addQuestion(String question, String dimension, List<String> points) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("question", question, "dimension", dimension, "category", "Recruiter"));
        if (points != null) body.put("expected_points", points);
        return bodyOf(postJsonAs(admin, base(), body).andExpect(status().isOk())).path("question_id").asText();
    }

    private JsonNode prepare() throws Exception {
        return bodyOf(postJsonAs(admin, "/api/interview/prepare", Map.of("session_id", job, "candidate_name", "Asha Rao"))
                .andExpect(status().isOk()));
    }

    private void save(String interviewId, List<String> answers) throws Exception {
        List<Map<String, Object>> transcript = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) transcript.add(Map.of("seq", i, "answer", answers.get(i)));
        postJsonAs(admin, "/api/interview/save", Map.of("session_id", job, "interview_id", interviewId, "transcript", transcript))
                .andExpect(status().isOk());
    }

    private JsonNode transcriptRow(String interviewId) throws Exception {
        for (JsonNode row : bodyOf(getAs(admin, "/api/interview/transcripts/" + job)).path("interviews")) {
            if (row.path("interview_id").asText().equals(interviewId)) return row;
        }
        throw new AssertionError("no transcript " + interviewId);
    }

    private Account newReviewer() throws Exception {
        String email = "sme-" + shortId() + "@test.local";
        postJsonAs(admin, "/api/auth/signup", Map.of("role", "reviewer", "name", "Dr SME", "email", email,
                "password", "Reviewer123x", "reviewer_skills", List.of("java"))).andExpect(status().isOk());
        return tokenFor(users.findByRoleAndEmailIgnoreCase(Role.REVIEWER, email).orElseThrow());
    }

    /** Two recruiter questions (the one without points is asked first after a reorder), total 5. */
    private void recruiterSetup() throws Exception {
        String withPoints = addQuestion("Tell me about a Spring Boot service you built.", "THEORY",
                List.of("names the service's purpose", "explains its main components"));
        String withoutPoints = addQuestion("How do you keep SQL queries fast?", "THEORY", null);
        putJsonAs(admin, base() + "/order", Map.of("question_ids", List.of(withoutPoints, withPoints))).andExpect(status().isOk());
    }

    // ── recruiter questions ─────────────────────────────────────────────

    @Test
    @DisplayName("recruiter questions: create, edit, reorder, delete, total config, validation and job access")
    void recruiterQuestionsCrud() throws Exception {
        String q1 = addQuestion("First?", "THEORY", List.of(" a ", "A", "b", ""));
        String q2 = addQuestion("Second?", "hands_on", null);
        JsonNode list = bodyOf(getAs(admin, base()).andExpect(status().isOk()));
        assertThat(list.path("total_questions").asInt()).isEqualTo(5);
        assertThat(list.path("questions").get(0).path("expected_points").toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(list.path("questions").get(0).path("expected_points_source").asText()).isEqualTo("HR");
        assertThat(list.path("questions").get(1).path("dimension").asText()).isEqualTo("HANDS_ON");
        assertThat(list.path("questions").get(1).path("expected_points_source").isNull()).isTrue();

        putJsonAs(admin, base() + "/" + q2, Map.of("question", "Second, edited?", "expected_points", List.of("x"))).andExpect(status().isOk());
        putJsonAs(admin, base() + "/order", Map.of("question_ids", List.of(q2, q1))).andExpect(status().isOk());
        list = bodyOf(getAs(admin, base()));
        assertThat(list.path("questions").get(0).path("question").asText()).isEqualTo("Second, edited?");
        assertThat(list.path("questions").get(0).path("seq").asInt()).isZero();

        putJsonAs(admin, base() + "/order", Map.of("question_ids", List.of(q2))).andExpect(status().isBadRequest());
        postJsonAs(admin, base(), Map.of("question", " ")).andExpect(status().isBadRequest());
        postJsonAs(admin, base(), Map.of("question", "x?", "dimension", "PRACTICE")).andExpect(status().isBadRequest());
        postJsonAs(admin, base(), Map.of("question", "x?", "expected_points", List.of("1", "2", "3", "4", "5", "6", "7")))
                .andExpect(status().isBadRequest());
        putJsonAs(admin, base() + "/config", Map.of("total_questions", 11)).andExpect(status().isBadRequest());
        putJsonAs(admin, base() + "/config", Map.of("total_questions", 0)).andExpect(status().isBadRequest());
        assertThat(bodyOf(putJsonAs(admin, base() + "/config", Map.of("total_questions", 7))).path("total_questions").asInt()).isEqualTo(7);

        for (int i = 0; i < 8; i++) addQuestion("Filler " + i + "?", "THEORY", null);
        postJsonAs(admin, base(), Map.of("question", "Eleventh?")).andExpect(status().isBadRequest());

        mvc.perform(withAuth(delete(base() + "/" + q2), admin)).andExpect(status().isOk());
        list = bodyOf(getAs(admin, base()));
        assertThat(list.path("questions")).hasSize(9);
        assertThat(list.path("questions").get(0).path("question_id").asText()).isEqualTo(q1);
        assertThat(list.path("questions").get(0).path("seq").asInt()).isZero();

        Account other = newEmployee();
        getAs(other, base()).andExpect(status().isForbidden());
        postJsonAs(other, base(), Map.of("question", "Mine?")).andExpect(status().isForbidden());
        getAs(newReviewer(), base()).andExpect(status().isForbidden());
    }

    // ── prepare: recruiter first, AI fills, expected points ─────────────

    @Test
    @DisplayName("prepare: recruiter questions first in their order, AI fills to the total with practical prompts, AI writes missing expected points")
    void prepareOrderingAndExpectedPoints() throws Exception {
        recruiterSetup();
        JsonNode prepared = prepare();
        String id = prepared.path("interview_id").asText();

        assertThat(prepared.path("questions")).hasSize(5);
        assertThat(prepared.path("questions").get(0).path("question").asText()).isEqualTo("How do you keep SQL queries fast?");
        assertThat(prepared.path("questions").get(1).path("question").asText()).startsWith("Tell me about a Spring Boot service");
        // The candidate's view never carries expected points.
        assertThat(prepared.toString()).doesNotContain("expected_points").doesNotContain("point");

        List<Map<String, Object>> turns = jdbc.queryForList("select seq, source, dimension, question, expected_points::text as points "
                + "from interview_turns where interview_id = ?::uuid order by seq", id);
        assertThat(turns).extracting(t -> t.get("source")).containsExactly("RECRUITER", "RECRUITER", "AI", "AI", "AI");
        assertThat(turns).extracting(t -> t.get("dimension")).containsExactly("THEORY", "THEORY", "THEORY", "HANDS_ON", "HANDS_ON");
        assertThat((String) turns.get(0).get("points")).contains("AI point one");
        assertThat((String) turns.get(1).get("points")).contains("names the service's purpose");

        // One Gemini call; it was told the recruiter questions and asked for R1's points only.
        assertThat(prepCalls.get()).isEqualTo(1);
        String prompt = prepPrompts.get(0);
        assertThat(prompt).contains("R1: How do you keep SQL queries fast?").contains("R2: Tell me about a Spring Boot service");
        assertThat(prompt.substring(prompt.indexOf("recruiter_expected_points"))).contains("R1").doesNotContain("R2");
        assertThat(prompt).contains("Write exactly 1 item(s) in \"questions\"").contains("Write exactly 2 item(s) in \"practical_questions\"");

        // The AI-written points are kept on the job question (source AI) for HR to edit.
        JsonNode first = bodyOf(getAs(admin, base())).path("questions").get(0);
        assertThat(first.path("expected_points_source").asText()).isEqualTo("AI");
        assertThat(first.path("expected_points").toString()).contains("AI point one");
        prepare();
        assertThat(prepPrompts.get(1)).as("points are reused, not rewritten").doesNotContain("recruiter_expected_points");
    }

    // ── grading ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("grading: evidence quoted from the answer, empty and very short answers score 0 without Gemini, Theory/Hands-on result")
    void gradingWithEvidence() throws Exception {
        recruiterSetup();
        String id = prepare().path("interview_id").asText();
        save(id, List.of(
                "I add indexes on the columns used in where clauses and check the query plan.",
                "",
                "Yes, sure.",
                "First I reproduce the bug with a failing test, then read the logs.",
                "NOEVIDENCE I would just restart the server and hope it works."));

        assertThat(gradeCalls.get()).as("two runs for each of the three gradable answers").isEqualTo(6);
        List<Map<String, Object>> turns = jdbc.queryForList("select seq, score, max_score, evidence::text as evidence, flags::text as flags "
                + "from interview_turns where interview_id = ?::uuid order by seq", id);
        assertThat(turns).extracting(t -> ((Number) t.get("score")).doubleValue()).containsExactly(4.0, 0.0, 0.0, 4.0, 0.0);
        assertThat(turns).extracting(t -> ((Number) t.get("max_score")).doubleValue()).containsOnly(4.0);
        assertThat((String) turns.get(0).get("evidence")).contains("I add indexes on the");
        assertThat((String) turns.get(1).get("evidence")).contains("Not answered");
        assertThat((String) turns.get(2).get("evidence")).contains("too short");
        assertThat((String) turns.get(4).get("evidence")).contains("Rejected");

        JsonNode row = transcriptRow(id);
        assertThat(row.path("evaluation_status").asText()).isEqualTo("EVALUATED");
        assertThat(row.path("result_status").asText()).isEqualTo("PROVISIONAL");
        assertThat(row.path("theory_score").asDouble()).isEqualTo(33.33);
        assertThat(row.path("hands_on_score").asDouble()).isEqualTo(50.0);
        assertThat(row.path("overall_score").asDouble()).isEqualTo(45.0);
        assertThat(row.path("theory_band").asText()).isEqualTo("NOT_DEMONSTRATED");
        assertThat(row.path("hands_on_band").asText()).isEqualTo("BASIC");
        assertThat(row.path("recommendation").asText()).isEqualTo("HR_REVIEW");
        JsonNode t0 = row.path("transcript").get(0);
        assertThat(t0.path("source").asText()).isEqualTo("RECRUITER");
        assertThat(t0.path("score").asDouble()).isEqualTo(4.0);
        assertThat(t0.path("evidence").get(0).path("evidence").asText()).isEqualTo("I add indexes on the");
        assertThat(row.path("transcript").get(3).path("dimension").asText()).isEqualTo("HANDS_ON");

        // HR can re-grade on demand (job access required).
        postJsonAs(admin, "/api/interview/" + id + "/evaluate", Map.of()).andExpect(status().isOk());
        assertThat(gradeCalls.get()).isEqualTo(12);
        postJsonAs(newEmployee(), "/api/interview/" + id + "/evaluate", Map.of()).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Gemini down while grading: the answers wait (RUBRIC_PENDING) and the sweep grades them later")
    void gradingRetriesWhenGeminiIsDown() throws Exception {
        String id = prepare().path("interview_id").asText();
        jdbc.update("delete from app_settings where key = 'gemini_api_key'");
        save(id, List.of("I would profile the slow endpoint first.", "", "", "", ""));
        Map<String, Object> waiting = jdbc.queryForMap("select evaluation_status, next_evaluation_at, flags::text as flags "
                + "from interviews where id = ?::uuid", id);
        assertThat(waiting.get("evaluation_status")).isEqualTo("EVALUATING");
        assertThat(waiting.get("next_evaluation_at")).isNotNull();
        assertThat((String) waiting.get("flags")).contains("RUBRIC_PENDING");

        setGeminiKey(admin, "AIza-test-key-123");
        jdbc.update("update interviews set next_evaluation_at = now() - interval '1 minute' where id = ?::uuid", id);
        sweep.sweep();
        Map<String, Object> done = jdbc.queryForMap("select evaluation_status, flags::text as flags from interviews where id = ?::uuid", id);
        assertThat(done.get("evaluation_status")).isEqualTo("EVALUATED");
        assertThat((String) done.get("flags")).doesNotContain("RUBRIC_PENDING");
        assertThat(jdbc.queryForObject("select score from interview_turns where interview_id = ?::uuid and seq = 0", Double.class, id))
                .isEqualTo(4.0);
    }

    // ── SME review of an interview ──────────────────────────────────────

    @Test
    @DisplayName("interview results go to the SME review: queue, transcript in the review, adjusted sign-off recomputes, lock, reopen")
    void smeReviewOfInterview() throws Exception {
        Account sme = newReviewer();
        putJsonAs(admin, "/api/jobs/" + job + "/reviewers", Map.of("user_ids", List.of(sme.user().getId().toString()),
                "review_policy", Map.of("question_set_approval_required", false, "sample_rate_not_recommended", 0,
                        "review_if_theory_at_least", 101))).andExpect(status().isOk());
        recruiterSetup();
        String id = prepare().path("interview_id").asText();
        save(id, List.of("I add indexes on the columns used in where clauses.", "", "Yes, sure.",
                "First I reproduce the bug with a failing test.", "NOEVIDENCE I would restart the server."));

        assertThat(transcriptRow(id).path("evaluation_status").asText()).isEqualTo("AWAITING_REVIEW");
        String review = jdbc.queryForObject("select id::text from sme_reviews where subject_type = 'INTERVIEW' and subject_id = ?::uuid",
                String.class, id);
        JsonNode queue = bodyOf(getAs(sme, "/api/review/queue?subject_type=INTERVIEW").andExpect(status().isOk()));
        assertThat(queue.path("reviews").findValuesAsText("review_id")).contains(review);
        JsonNode detail = bodyOf(getAs(sme, "/api/review/" + review).andExpect(status().isOk()));
        assertThat(detail.path("interview").path("recommendation").asText()).isEqualTo("HR_REVIEW");
        assertThat(detail.path("turns")).hasSize(5);
        String lastTurn = detail.path("turns").get(4).path("turn_id").asText();
        assertThat(detail.path("turns").get(4).path("answer").asText()).contains("restart the server");
        getAs(sme, "/api/interview/transcripts/" + job).andExpect(status().isForbidden());

        postJsonAs(sme, "/api/review/" + review + "/claim", Map.of()).andExpect(status().isOk());
        postJsonAs(sme, "/api/review/interviews/" + review, Map.of("action", "SIGN_OFF",
                "adjustments", List.of(Map.of("turn_id", lastTurn, "sme_score", 5, "reason", "x")))).andExpect(status().isBadRequest());
        postJsonAs(sme, "/api/review/interviews/" + review, Map.of("action", "SIGN_OFF",
                "adjustments", List.of(Map.of("turn_id", lastTurn, "sme_score", 4)))).andExpect(status().isBadRequest());
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF")).andExpect(status().isBadRequest());
        postJsonAs(sme, "/api/review/interviews/" + review, Map.of("action", "SIGN_OFF", "comment", "Restart is a valid first step here",
                "adjustments", List.of(Map.of("turn_id", lastTurn, "sme_score", 4, "reason", "Covers both points in context"))))
                .andExpect(status().isOk());

        JsonNode row = transcriptRow(id);
        assertThat(row.path("evaluation_status").asText()).isEqualTo("VALIDATED");
        assertThat(row.path("result_status").asText()).isEqualTo("VALIDATED");
        assertThat(row.path("hands_on_score").asDouble()).isEqualTo(100.0);
        assertThat(row.path("recommendation").asText()).as("theory 33.33 is below the gate").isEqualTo("NOT_RECOMMENDED");
        assertThat(row.path("review_status").asText()).isEqualTo("ADJUSTED");
        assertThat(row.path("reviewer_name").asText()).isEqualTo("Dr SME");
        assertThat(row.path("sme_comment").asText()).isEqualTo("Restart is a valid first step here");
        JsonNode t4 = row.path("transcript").get(4);
        assertThat(t4.path("score").asDouble()).as("the engine's score is kept").isZero();
        assertThat(t4.path("sme_score").asDouble()).isEqualTo(4.0);
        assertThat(jdbc.queryForObject("select engine_recommendation from interviews where id = ?::uuid", String.class, id))
                .isEqualTo("HR_REVIEW");

        postJsonAs(admin, "/api/interview/" + id + "/evaluate", Map.of()).andExpect(status().isConflict());
        postJsonAs(sme, "/api/review/interviews/" + review, Map.of("action", "SIGN_OFF")).andExpect(status().isConflict());
        postJsonAs(admin, "/api/review/" + review + "/reopen", Map.of("reason", "Candidate appeal")).andExpect(status().isOk());
        assertThat(transcriptRow(id).path("evaluation_status").asText()).isEqualTo("AWAITING_REVIEW");
        assertThat(jdbc.queryForList("select action from review_audit where review_id = ?::uuid order by id", String.class, review))
                .containsExactly("CREATED", "CLAIMED", "SIGNED_OFF", "REOPENED");
    }

    // ── phone ───────────────────────────────────────────────────────────

    private ResultActions webhook(String path, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Twilio-Signature", Fixtures.twilioSignature(AUTH_TOKEN, PUBLIC_URL + path, params));
        params.forEach(request::param);
        return mvc.perform(request);
    }

    private String twiml(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("phone: silence moves on to the next question, confidence is stored, the finished call is graded")
    void phoneSilenceContinues() throws Exception {
        setTwilio(admin, "ACtest0123456789", AUTH_TOKEN, "+15550001111");
        String id = prepare().path("interview_id").asText();
        String voice = twiml(webhook("/api/interview/twiml/voice/" + id, Map.of("CallSid", "CA1")));
        assertThat(voice).contains("actionOnEmptyResult=\"true\"");

        // Silence on question 0: Twilio calls the action with no SpeechResult.
        String next = twiml(webhook("/api/interview/twiml/answer/" + id + "/0", Map.of("CallSid", "CA1")));
        assertThat(next).contains("<Gather").contains("/answer/" + id + "/1").doesNotContain("<Hangup/>");
        String withSpeech = twiml(webhook("/api/interview/twiml/answer/" + id + "/1",
                Map.of("CallSid", "CA1", "SpeechResult", "I would add an index on the email column.", "Confidence", "0.873")));
        assertThat(withSpeech).contains("/answer/" + id + "/2");
        for (int seq = 2; seq < 4; seq++) twiml(webhook("/api/interview/twiml/answer/" + id + "/" + seq, Map.of("CallSid", "CA1")));
        String last = twiml(webhook("/api/interview/twiml/answer/" + id + "/4", Map.of("CallSid", "CA1", "SpeechResult", "")));
        assertThat(last).contains("<Hangup/>");

        Map<String, Object> t0 = jdbc.queryForMap("select answer, speech_confidence from interview_turns "
                + "where interview_id = ?::uuid and seq = 0", id);
        assertThat(t0.get("answer")).isEqualTo("");
        assertThat(t0.get("speech_confidence")).isNull();
        assertThat(jdbc.queryForObject("select speech_confidence from interview_turns where interview_id = ?::uuid and seq = 1",
                Double.class, id)).isEqualTo(0.873);
        assertThat(jdbc.queryForObject("select evaluation_status from interviews where id = ?::uuid", String.class, id))
                .isEqualTo("EVALUATED");
        assertThat(gradeCalls.get()).as("only the one spoken answer is sent to the grader").isEqualTo(2);
    }
}

package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self-service (invite link) and in-app browser interviews: question prep via
 *  Gemini, the single-use token lifecycle, and transcripts. */
class InterviewIntegrationTest extends IntegrationTestBase {

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Platform Engineer", "java, docker");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        stubInterviewQuestions();
    }

    private void stubInterviewQuestions() {
        STUB.on("POST", "/v1beta/models/", req -> StubResponse.json(200, Fixtures.geminiText(Fixtures.interviewJson(5))));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> transcriptBody(String answerPrefix) {
        return Map.of(
                "transcript", List.of(
                        Map.of("category", "Background", "question", "Interview question number 1?", "answer", answerPrefix + " one"),
                        Map.of("category", "Technical", "skill", "java", "question", "Interview question number 2?", "answer", answerPrefix + " two")),
                "started_at", "2026-09-24T10:00:00Z",
                "ended_at", "2026-09-24T10:05:00Z");
    }

    private JsonNode prepare(Map<String, Object> body) throws Exception {
        return bodyOf(postJsonAs(admin, "/api/interview/prepare", body).andExpect(status().isOk()));
    }

    /** Mints a self-service link and returns its raw token. */
    private String mintToken(String email) throws Exception {
        JsonNode result = bodyOf(postJsonAs(admin, "/api/interview/invites/mint",
                Map.of("session_id", job, "candidate_email", email, "candidate_name", "Jane Doe", "language", "en-IN"))
                .andExpect(status().isOk()));
        assertThat(result.path("status").asText()).as(result.toString()).isEqualTo("success");
        String url = result.path("url").asText();
        assertThat(url).startsWith("https://public.example.com/interview/");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private ResultActions byToken(String token) throws Exception {
        return mvc.perform(get("/api/interview/by_token/" + token));
    }

    private ResultActions saveByToken(String token, Object body) throws Exception {
        return mvc.perform(post("/api/interview/save_by_token/" + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    // ── prepare (HR-driven, in-app) ─────────────────────────────────────

    @Test
    @DisplayName("prepare asks Gemini for questions in the requested language and stores a pending interview with empty answers")
    void prepareSuccess() throws Exception {
        JsonNode prepared = prepare(Map.of("session_id", job, "candidate_name", "Jane Doe", "phone", "+911234567890", "language", "hi-IN"));

        assertThat(prepared.path("interview_id").asText()).isNotBlank();
        assertThat(prepared.path("role_title").asText()).isEqualTo("Platform Engineer");
        assertThat(prepared.path("candidate_name").asText()).isEqualTo("Jane Doe");
        assertThat(prepared.path("language").asText()).isEqualTo("hi-IN");
        assertThat(prepared.path("intro").asText()).contains("welcome");
        assertThat(prepared.path("outro").asText()).contains("Thank you");
        assertThat(prepared.path("questions")).hasSize(5);
        assertThat(prepared.path("questions").get(0).path("category").asText()).isEqualTo("Background");
        assertThat(prepared.path("questions").get(1).path("skill").asText()).isEqualTo("java");

        String id = prepared.path("interview_id").asText();
        assertThat(jdbc.queryForMap("select status, mode from interviews where id = ?::uuid", id))
                .containsEntry("status", "PENDING").containsEntry("mode", "BROWSER");
        assertThat(jdbc.queryForObject("select count(*) from interview_turns where interview_id = ?::uuid and answer = ''", Integer.class, id))
                .isEqualTo(5);

        String prompt = json.readTree(STUB.requests("/v1beta/models/").get(0).body())
                .path("contents").get(0).path("parts").get(0).path("text").asText();
        assertThat(prompt).contains("Platform Engineer").contains("Jane Doe").contains("locale code \"hi-IN\"");
    }

    @Test
    @DisplayName("prepare grounds the questions in the screened candidate's résumé when the file is known")
    void prepareUsesResumeContext() throws Exception {
        String resume = "Priya Nair\npriya@example.com\n7 years of experience in Java and Docker.\n";
        uploadResumes(admin, job, "priya.txt", resume).andExpect(status().isOk());
        postJsonAs(admin, "/api/run_screening", Map.of("session_id", job)).andExpect(status().isOk());

        prepare(Map.of("session_id", job, "file_name", "priya.txt"));

        String prompt = json.readTree(STUB.requests("/v1beta/models/").get(0).body())
                .path("contents").get(0).path("parts").get(0).path("text").asText();
        assertThat(prompt).contains("Priya Nair").contains("matched these skills").contains("java").contains("Years of experience: 7");
    }

    @Test
    @DisplayName("prepare falls back to general questions when the résumé file isn't recognised")
    void prepareWithoutKnownResume() throws Exception {
        prepare(Map.of("session_id", job, "candidate_name", "Someone", "file_name", "does-not-exist.txt"));

        String prompt = json.readTree(STUB.requests("/v1beta/models/").get(0).body())
                .path("contents").get(0).path("parts").get(0).path("text").asText();
        assertThat(prompt).contains("No résumé details are available");
    }

    @Test
    @DisplayName("prepare failure modes come back as HTTP errors with a readable message (InterviewRoom.jsx only has an HTTP-error path)")
    void prepareFailures() throws Exception {
        // Unknown job.
        postJsonAs(admin, "/api/interview/prepare", Map.of("session_id", "local_react_user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("upload a JD first")));

        // Gemini unreachable / rejecting.
        STUB.reset();
        STUB.respond("POST", "/v1beta/models/", 500, "{}");
        postJsonAs(admin, "/api/interview/prepare", Map.of("session_id", job))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message", containsString("Could not reach the AI service")))
                .andExpect(jsonPath("$.detail", containsString("Could not reach the AI service")));

        // Gemini answers, but not with JSON.
        STUB.reset();
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiText("I'd rather chat."));
        postJsonAs(admin, "/api/interview/prepare", Map.of("session_id", job))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message", containsString("unexpected")));

        // Valid JSON but no questions in it.
        STUB.reset();
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiText("{\"intro\":\"Hi\",\"outro\":\"Bye\",\"questions\":[]}"));
        postJsonAs(admin, "/api/interview/prepare", Map.of("session_id", job))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message", containsString("didn't return any interview questions")));

        assertThat(jdbc.queryForObject("select count(*) from interviews where job_id = ?::uuid", Integer.class, job))
                .as("a failed prepare must not leave a half-built interview behind").isZero();
    }

    @Test
    @DisplayName("prepare without a Gemini key says so, without calling Gemini")
    void prepareNeedsKey() throws Exception {
        jdbc.update("delete from app_settings where key = 'gemini_api_key'");
        STUB.reset();

        postJsonAs(admin, "/api/interview/prepare", Map.of("session_id", job))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("No Gemini API key")));
        assertThat(STUB.requests("/v1beta/")).isEmpty();
    }

    // ── HR-driven save ──────────────────────────────────────────────────

    @Test
    @DisplayName("saving a browser interview stores the answers, completes it, and lists it under the job's transcripts")
    void hrSave() throws Exception {
        String id = prepare(Map.of("session_id", job, "candidate_name", "Jane Doe", "phone", "+911234567890")).path("interview_id").asText();
        getAs(admin, "/api/interview/transcripts/" + job).andExpect(jsonPath("$.interviews.length()").value(0));

        Map<String, Object> body = new java.util.HashMap<>(transcriptBody("Answer"));
        body.put("session_id", job);
        body.put("interview_id", id);
        body.put("candidate_name", "Jane Doe");
        body.put("role_title", "Platform Engineer");
        postJsonAs(admin, "/api/interview/save", body).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

        assertThat(jdbc.queryForObject("select status from interviews where id = ?::uuid", String.class, id)).isEqualTo("COMPLETED");
        getAs(admin, "/api/interview/transcripts/" + job)
                .andExpect(jsonPath("$.interviews.length()").value(1))
                .andExpect(jsonPath("$.interviews[0].interview_id").value(id))
                .andExpect(jsonPath("$.interviews[0].candidate_name").value("Jane Doe"))
                .andExpect(jsonPath("$.interviews[0].phone").value("+911234567890"))
                .andExpect(jsonPath("$.interviews[0].role_title").value("Platform Engineer"))
                .andExpect(jsonPath("$.interviews[0].transcript.length()").value(2))
                .andExpect(jsonPath("$.interviews[0].transcript[0].answer").value("Answer one"))
                .andExpect(jsonPath("$.interviews[0].transcript[1].skill").value("java"))
                .andExpect(jsonPath("$.interviews[0].saved_at").value("2026-09-24T10:05:00Z"));
    }

    @Test
    @DisplayName("save refuses an interview that belongs to a different job, an unknown interview, and an empty transcript")
    void hrSaveValidation() throws Exception {
        String id = prepare(Map.of("session_id", job)).path("interview_id").asText();
        String otherJob = createJob(admin, "Other job", "python");

        Map<String, Object> mismatched = new java.util.HashMap<>(transcriptBody("x"));
        mismatched.put("session_id", otherJob);
        mismatched.put("interview_id", id);
        postJsonAs(admin, "/api/interview/save", mismatched)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("doesn't belong")));

        Map<String, Object> unknown = new java.util.HashMap<>(transcriptBody("x"));
        unknown.put("session_id", job);
        unknown.put("interview_id", "99999999-9999-9999-9999-999999999999");
        postJsonAs(admin, "/api/interview/save", unknown).andExpect(status().isNotFound());

        postJsonAs(admin, "/api/interview/save", Map.of("session_id", job, "interview_id", id, "transcript", List.of()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("interviews still pending aren't listed as transcripts")
    void pendingNotListed() throws Exception {
        prepare(Map.of("session_id", job));

        getAs(admin, "/api/interview/transcripts/" + job).andExpect(jsonPath("$.interviews.length()").value(0));
    }

    // ── self-service link ───────────────────────────────────────────────

    @Test
    @DisplayName("a minted link serves the interview without consuming it; saving consumes it exactly once")
    void selfServiceLifecycle() throws Exception {
        String token = mintToken("jane@example.com");

        // Public, no auth; reading it is repeatable (a page refresh mid-interview mustn't burn the link).
        for (int i = 0; i < 2; i++) {
            byToken(token).andExpect(status().isOk())
                    .andExpect(jsonPath("$.role_title").value("Platform Engineer"))
                    .andExpect(jsonPath("$.candidate_name").value("Jane Doe"))
                    .andExpect(jsonPath("$.language").value("en-IN"))
                    .andExpect(jsonPath("$.intro").isNotEmpty())
                    .andExpect(jsonPath("$.outro").isNotEmpty())
                    .andExpect(jsonPath("$.questions.length()").value(5))
                    .andExpect(jsonPath("$.questions[0].question").value("Interview question number 1?"));
        }
        assertThat(jdbc.queryForObject("select mode from interviews where candidate_name = 'Jane Doe' and job_id = ?::uuid", String.class, job)).isEqualTo("SELF");

        saveByToken(token, transcriptBody("Answer")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

        byToken(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("already been completed")));
        saveByToken(token, transcriptBody("Second try")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already been completed")));

        // The first submission is what was kept.
        getAs(admin, "/api/interview/transcripts/" + job)
                .andExpect(jsonPath("$.interviews.length()").value(1))
                .andExpect(jsonPath("$.interviews[0].transcript[0].answer").value("Answer one"));
    }

    @Test
    @DisplayName("exactly one of many simultaneous submissions of the same link wins")
    void singleUseIsAtomic() throws Exception {
        String token = mintToken("race@example.com");

        List<Integer> statuses = inParallel(12, () ->
                saveByToken(token, transcriptBody("Racer")).andReturn().getResponse().getStatus());

        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 400).hasSize(11);
        assertThat(jdbc.queryForObject(
                "select count(*) from interview_turns t join interviews i on i.id = t.interview_id where i.candidate_name = 'Jane Doe' and i.job_id = ?::uuid",
                Integer.class, job)).as("no duplicated turns from the losing requests").isEqualTo(2);
    }

    @Test
    @DisplayName("an expired link is refused for both reading and saving")
    void expiredLink() throws Exception {
        String token = mintToken("late@example.com");
        jdbc.update("update invites set expires_at = now() - interval '1 hour' where token_hash = ?", sha256(token));

        byToken(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("expired")));
        saveByToken(token, transcriptBody("Too late")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select status from interviews where candidate_name = 'Jane Doe' and job_id = ?::uuid", String.class, job))
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("unknown tokens, and tokens of the wrong kind (an assessment link), are refused")
    void invalidTokens() throws Exception {
        byToken("no-such-token").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("invalid")));
        saveByToken("no-such-token", transcriptBody("x")).andExpect(status().isBadRequest());

        JsonNode assessmentInvite = bodyOf(postJsonAs(admin, "/api/invites/mint",
                Map.of("session_id", job, "candidate_email", "a@example.com", "levels", List.of("L1"))).andExpect(status().isOk()));
        String url = assessmentInvite.path("invites").get(0).path("url").asText();
        String assessmentToken = url.substring(url.indexOf("token=") + 6);

        byToken(assessmentToken).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("invalid")));
        saveByToken(assessmentToken, transcriptBody("x")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("save_by_token needs a non-empty transcript")
    void saveByTokenValidation() throws Exception {
        String token = mintToken("v@example.com");

        saveByToken(token, Map.of("transcript", List.of())).andExpect(status().isBadRequest());
        byToken(token).andExpect(status().isOk()); // a rejected save must not have consumed the link
    }

    @Test
    @DisplayName("only a hash of the interview token is stored")
    void tokenStoredHashed() throws Exception {
        // A unique address: the database is shared with other test classes, which also mint invites.
        String email = "hash-" + shortId() + "@example.com";
        String token = mintToken(email);

        assertThat(jdbc.queryForObject("select token_hash from invites where candidate_email = ?", String.class, email))
                .isEqualTo(sha256(token)).isNotEqualTo(token);
    }

    @Test
    @DisplayName("minting reports a Gemini problem as a normal {status:'error'} response and leaves nothing behind")
    void mintFailureIsGraceful() throws Exception {
        STUB.reset();
        STUB.respond("POST", "/v1beta/models/", 500, "{}");

        postJsonAs(admin, "/api/interview/invites/mint", Map.of("session_id", job, "candidate_email", "f@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("Could not reach the AI service")))
                .andExpect(jsonPath("$.url").doesNotExist());

        assertThat(jdbc.queryForObject("select count(*) from invites where candidate_email = 'f@example.com'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from interviews where job_id = ?::uuid", Integer.class, job)).isZero();
    }

    @Test
    @DisplayName("minting without a Gemini key, or without an email, is a graceful error too")
    void mintNeedsKeyAndEmail() throws Exception {
        jdbc.update("delete from app_settings where key = 'gemini_api_key'");
        postJsonAs(admin, "/api/interview/invites/mint", Map.of("session_id", job, "candidate_email", "k@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("No Gemini API key")));

        postJsonAs(admin, "/api/interview/invites/mint", Map.of("session_id", job)).andExpect(status().isBadRequest());
    }

    // ── config ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("interview config reports whether phone calls are possible")
    void config() throws Exception {
        getAs(admin, "/api/interview/config")
                .andExpect(jsonPath("$.twilio_configured").value(false));

        setTwilio(admin, "ACtest123", "secret-token", "+15550001111");

        getAs(admin, "/api/interview/config")
                .andExpect(jsonPath("$.twilio_configured").value(true))
                .andExpect(jsonPath("$.from_number").value("+15550001111"));
    }
}

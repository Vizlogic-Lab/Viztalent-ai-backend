package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The recruiter assistant (POST /api/universal_execute): Gemini gets the caller's
 *  persona and tools; if it calls PROCESS_RESUMES the deterministic screening runs.
 *  Every AI-side failure must come back as a normal chat reply, never an HTTP error. */
class RecruiterChatIntegrationTest extends IntegrationTestBase {

    private static final String PERSONA = "Viztalent AI, an elite Senior Tech Recruiter. Keep replies short.";

    private Account owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = newEmployee();
    }

    private Map<String, Object> chat(String command, String sessionId) {
        return Map.of(
                "command", command,
                "session_id", sessionId,
                "platform_config", Map.of(
                        "persona", PERSONA,
                        "industry", "Human Resources and Hiring",
                        "available_tools", List.of(Map.of(
                                "tag_name", "PROCESS_RESUMES",
                                "description", "Trigger ONLY when the user asks to screen the resumes.",
                                "expected_params", "job_description"))));
    }

    private JsonNode say(String command, String sessionId) throws Exception {
        return bodyOf(postJsonAs(owner, "/api/universal_execute", chat(command, sessionId)).andExpect(status().isOk()));
    }

    private JsonNode lastGeminiRequest() throws Exception {
        var calls = STUB.requests("/v1beta/models/");
        assertThat(calls).isNotEmpty();
        return json.readTree(calls.get(calls.size() - 1).body());
    }

    @Test
    @DisplayName("without a Gemini key the assistant explains what's missing, and doesn't call Gemini")
    void noKey() throws Exception {
        JsonNode reply = say("hello", "local_react_user");

        assertThat(reply.path("reply").asText()).contains("Gemini API key");
        assertThat(reply.path("table_data").isNull()).isTrue();
        assertThat(STUB.requests("/v1beta/")).isEmpty();
    }

    @Test
    @DisplayName("a plain answer from Gemini is passed straight through as the reply")
    void plainReply() throws Exception {
        setGeminiKey(newAdmin(), "AIza-test-key-123");
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiText("Hi! Upload a JD and some resumes and I'll rank them."));

        JsonNode reply = say("what can you do?", "local_react_user");

        assertThat(reply.path("reply").asText()).isEqualTo("Hi! Upload a JD and some resumes and I'll rank them.");
        assertThat(reply.path("table_data").isNull()).isTrue();
    }

    @Test
    @DisplayName("Gemini is given the caller's persona, the user's words, and the declared tool as a real function declaration")
    void requestShape() throws Exception {
        setGeminiKey(newAdmin(), "AIza-test-key-123");
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiText("ok"));

        say("please screen the resumes", "local_react_user");

        JsonNode sent = lastGeminiRequest();
        assertThat(sent.path("system_instruction").path("parts").get(0).path("text").asText()).isEqualTo(PERSONA);
        assertThat(sent.path("contents").get(0).path("role").asText()).isEqualTo("user");
        assertThat(sent.path("contents").get(0).path("parts").get(0).path("text").asText()).isEqualTo("please screen the resumes");
        JsonNode declaration = sent.path("tools").get(0).path("functionDeclarations").get(0);
        assertThat(declaration.path("name").asText()).isEqualTo("PROCESS_RESUMES");
        assertThat(declaration.path("parameters").path("type").asText()).isEqualTo("OBJECT");
        assertThat(declaration.path("parameters").path("properties").path("job_description").path("type").asText()).isEqualTo("STRING");
        assertThat(declaration.path("parameters").path("required").get(0).asText()).isEqualTo("job_description");
        assertThat(STUB.requests("/v1beta/models/").get(0).query()).contains("key=AIza-test-key-123");
    }

    @Test
    @DisplayName("when Gemini decides to screen, the real screening runs and the ranked table comes back")
    void functionCallRunsScreening() throws Exception {
        setGeminiKey(newAdmin(), "AIza-test-key-123");
        String job = createJob(owner, "Java role", "java, docker");
        uploadResumes(owner, job,
                "a.txt", "Priya Nair\npriya@example.com\n7 years of experience in Java and Docker.\n",
                "b.txt", "Asha Verma\nasha@example.com\n1 year of experience in Python.\n").andExpect(status().isOk());
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiFunctionCall("PROCESS_RESUMES"));

        JsonNode reply = say("screen the resumes", job);

        assertThat(reply.path("reply").asText()).startsWith("Screened 2 candidates");
        JsonNode rows = reply.path("table_data");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("Candidate_Name").asText()).isEqualTo("Priya Nair");
        assertThat(rows.get(0).path("Fit_Score_Out_Of_100").asInt()).isGreaterThan(rows.get(1).path("Fit_Score_Out_Of_100").asInt());
        assertThat(jdbc.queryForObject("select count(*) from candidates where job_id = ?::uuid", Integer.class, job)).isEqualTo(2);
    }

    @Test
    @DisplayName("asking to screen with no job selected (or no resumes uploaded) gets a helpful reply")
    void screeningPreconditions() throws Exception {
        setGeminiKey(newAdmin(), "AIza-test-key-123");
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiFunctionCall("PROCESS_RESUMES"));

        assertThat(say("screen them", "local_react_user").path("reply").asText()).contains("upload a JD first");

        String job = createJob(owner, "Empty role", "java");
        assertThat(say("screen them", job).path("reply").asText()).contains("no resumes");
    }

    @Test
    @DisplayName("a tool the backend has no handler for falls back to Gemini's text (or a short acknowledgement)")
    void unknownTool() throws Exception {
        setGeminiKey(newAdmin(), "AIza-test-key-123");
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiFunctionCall("SEND_EMAIL"));

        assertThat(say("email everyone", "local_react_user").path("reply").asText()).isEqualTo("Got it.");
    }

    @Test
    @DisplayName("Gemini being down, rejecting the key, or returning nonsense all become a normal apologetic reply (HTTP 200)")
    void aiFailuresAreChatReplies() throws Exception {
        setGeminiKey(newAdmin(), "AIza-test-key-123");

        STUB.respond("POST", "/v1beta/models/", 500, "{}");
        assertThat(say("hello", "local_react_user").path("reply").asText()).contains("couldn't reach the AI service");

        STUB.reset();
        STUB.respond("POST", "/v1beta/models/", 400, "{\"error\":{\"message\":\"API key not valid.\"}}");
        assertThat(say("hello", "local_react_user").path("reply").asText()).contains("couldn't reach the AI service");

        STUB.reset();
        STUB.respond("POST", "/v1beta/models/", 200, "this is not json at all");
        assertThat(say("hello", "local_react_user").path("reply").asText()).contains("couldn't reach the AI service");
    }

    @Test
    @DisplayName("with no persona/tools supplied, a default persona is used and no tools are declared")
    void defaultsWithoutPlatformConfig() throws Exception {
        setGeminiKey(newAdmin(), "AIza-test-key-123");
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiText("Hello."));

        postJsonAs(owner, "/api/universal_execute", Map.of("command", "hi", "session_id", "local_react_user")).andExpect(status().isOk());

        JsonNode sent = lastGeminiRequest();
        assertThat(sent.path("system_instruction").path("parts").get(0).path("text").asText()).contains("helpful recruiting assistant");
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    @DisplayName("an empty command is rejected as a bad request")
    void blankCommand() throws Exception {
        postJsonAs(owner, "/api/universal_execute", Map.of("command", "  ", "session_id", "x")).andExpect(status().isBadRequest());
    }
}

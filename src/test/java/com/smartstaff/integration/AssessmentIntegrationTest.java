package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssessmentIntegrationTest extends IntegrationTestBase {

    // 3 questions for L1 (two MCQ, one MSQ), 2 for L2 (coding, descriptive), none for L3.
    private static final String BANK_CSV = """
            type,level,skill,difficulty,question,options,correct_index
            mcq,L1,Math,easy,What is 2+2?,3|4|5,1
            mcq,L1,REST,easy,What does REST stand for?,Representational State Transfer|Remote Execution Service Tool,0
            msq,L1,HTTP,medium,Which are valid HTTP methods?,GET|POST|FETCH|DELETE,"0,1,3"
            coding,L2,Algorithms,medium,Reverse a string.,,
            descriptive,L2,Systems,hard,Explain the CAP theorem.,,
            """;

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Backend Engineer", "java, docker");
        setPublicBaseUrl(admin, "https://public.example.com");
    }

    private void uploadBank() throws Exception {
        uploadFile(admin, "/api/questions/upload", "file", "bank.csv", BANK_CSV).andExpect(status().isOk());
    }

    private JsonNode generate(String source) throws Exception {
        return bodyOf(postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", job, "question_source", source))
                .andExpect(status().isOk()));
    }

    private JsonNode answerKey() throws Exception {
        return bodyOf(getAs(admin, "/api/assessment/answer_key/" + job).andExpect(status().isOk()));
    }

    private static List<String> types(JsonNode level) {
        List<String> types = new ArrayList<>();
        level.path("questions").forEach(q -> types.add(q.path("type").asText()));
        return types;
    }

    private void stubGeminiQuestions(int perLevel) {
        STUB.on("POST", "/v1beta/models/", req ->
                StubResponse.json(200, Fixtures.geminiText(Fixtures.assessmentQuestionsJson(perLevel))));
    }

    // ── custom (question bank) ──────────────────────────────────────────

    @Test
    @DisplayName("custom source builds each level from the bank and produces a per-level answer key")
    void customFromBank() throws Exception {
        uploadBank();

        JsonNode result = generate("custom");

        assertThat(result.path("status").asText()).isEqualTo("success");
        assertThat(result.path("counts").path("L1").asInt()).isEqualTo(3);
        assertThat(result.path("counts").path("L2").asInt()).isEqualTo(2);
        assertThat(result.path("counts").path("L3").asInt()).isZero();
        assertThat(result.path("assessment_url").asText()).matches("https://public\\.example\\.com/assessment/JD-\\d+\\?level=L1");
        assertThat(result.path("assessment_urls").path("L3").asText()).endsWith("?level=L3");
        assertThat(STUB.requests("/v1beta/")).as("no Gemini key configured, so nothing to pad with").isEmpty();

        JsonNode key = answerKey();
        assertThat(key.path("role_title").asText()).isEqualTo("Backend Engineer");
        JsonNode l1 = key.path("levels").get(0);
        JsonNode l2 = key.path("levels").get(1);
        assertThat(l1.path("level").asText()).isEqualTo("L1");
        assertThat(l1.path("count").asInt()).isEqualTo(3);
        assertThat(types(l1)).containsExactlyInAnyOrder("MCQ", "MCQ", "MSQ");
        assertThat(types(l2)).containsExactlyInAnyOrder("CODING", "DESCRIPTIVE");
        assertThat(key.path("levels").get(2).path("questions")).isEmpty();

        for (JsonNode q : l1.path("questions")) {
            if (q.path("question").asText().equals("What is 2+2?")) {
                assertThat(q.path("correct_index").asInt()).isEqualTo(1);
                assertThat(q.has("correct_indices")).isFalse();
            }
            if (q.path("type").asText().equals("MSQ")) {
                assertThat(q.path("correct_indices")).extracting(JsonNode::asInt).containsExactly(0, 1, 3);
                assertThat(q.has("correct_index")).isFalse();
            }
        }
        for (JsonNode q : l2.path("questions")) {
            assertThat(q.has("correct_index")).as("%s carries no answer index", q.path("type").asText()).isFalse();
            assertThat(q.has("correct_indices")).isFalse();
        }
    }

    @Test
    @DisplayName("status reflects the generated assessment; generating again replaces it rather than adding a second")
    void statusAndRegeneration() throws Exception {
        uploadBank();
        generate("custom");
        generate("custom");

        getAs(admin, "/api/assessment/status/" + job)
                .andExpect(jsonPath("$.has_jd").value(true))
                .andExpect(jsonPath("$.jd_title").value("Backend Engineer"))
                .andExpect(jsonPath("$.num_questions").value(5))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.generating").value(false))
                .andExpect(jsonPath("$.assessment_url", containsString("/assessment/JD-")));

        assertThat(jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid", Integer.class, job)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from assessment_questions q join assessments a on a.id = q.assessment_id where a.job_id = ?::uuid",
                Integer.class, job)).isEqualTo(5);
    }

    @Test
    @DisplayName("a job with no assessment reports zero questions, and its answer key is a 404")
    void noAssessmentYet() throws Exception {
        getAs(admin, "/api/assessment/status/" + job)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.num_questions").value(0))
                .andExpect(jsonPath("$.ready").value(false));
        getAs(admin, "/api/assessment/answer_key/" + job).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("with an empty bank and no Gemini key there is nothing to build from, and it says so")
    void nothingToBuildFrom() throws Exception {
        JsonNode result = generate("custom");

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("message").asText()).contains("No questions available");
    }

    @Test
    @DisplayName("an unknown source and an unknown job are rejected clearly")
    void badRequests() throws Exception {
        JsonNode unknownSource = generate("bogus");
        assertThat(unknownSource.path("status").asText()).isEqualTo("error");
        assertThat(unknownSource.path("message").asText()).contains("Unknown question source");

        postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", "00000000-0000-0000-0000-000000000000", "question_source", "ai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("upload a JD first")));
        postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", job))
                .andExpect(status().isBadRequest());
    }

    // ── AI (Gemini) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ai source without a Gemini key is refused up front, without calling Gemini")
    void aiNeedsKey() throws Exception {
        JsonNode result = generate("ai");

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("message").asText()).contains("No Gemini API key");
        assertThat(STUB.requests("/v1beta/")).isEmpty();
    }

    @Test
    @DisplayName("ai source: one Gemini call per level, prompted with the role and skills, parsed into MCQ/MSQ questions")
    void aiSuccessPath() throws Exception {
        setGeminiKey(admin, "AIza-test-key-123");
        stubGeminiQuestions(6);

        JsonNode result = generate("ai");

        assertThat(result.path("status").asText()).isEqualTo("success");
        assertThat(result.path("counts").path("L1").asInt()).isEqualTo(6);
        assertThat(result.path("counts").path("L2").asInt()).isEqualTo(6);
        assertThat(result.path("counts").path("L3").asInt()).isEqualTo(6);

        var calls = STUB.requests("/v1beta/models/");
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0).path()).endsWith(":generateContent");
        assertThat(calls.get(0).query()).contains("key=AIza-test-key-123");
        JsonNode sent = json.readTree(calls.get(0).body());
        assertThat(sent.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
        String prompt = sent.path("contents").get(0).path("parts").get(0).path("text").asText();
        // (skill order in the prompt follows the dictionary, so check each skill rather than one joined string)
        assertThat(prompt).contains("Backend Engineer").contains("java").contains("docker").contains("Write exactly 6 questions");

        JsonNode l1 = answerKey().path("levels").get(0);
        assertThat(l1.path("count").asInt()).isEqualTo(6);
        assertThat(types(l1)).containsExactlyInAnyOrder("MCQ", "MCQ", "MSQ", "MCQ", "MCQ", "MSQ");
        for (JsonNode q : l1.path("questions")) {
            if (q.path("type").asText().equals("MSQ")) {
                assertThat(q.path("correct_indices")).extracting(JsonNode::asInt).containsExactly(0, 2);
            } else {
                assertThat(q.path("correct_index").asInt()).isEqualTo(1);
            }
            assertThat(q.path("options")).hasSize(4);
        }
    }

    @Test
    @DisplayName("each level's prompt asks for that level's difficulty (L1 easy, L2 medium, L3 hard)")
    void aiPromptsMatchLevel() throws Exception {
        setGeminiKey(admin, "AIza-test-key-123");
        stubGeminiQuestions(6);
        generate("ai");

        List<String> prompts = new ArrayList<>();
        for (var call : STUB.requests("/v1beta/models/")) {
            prompts.add(json.readTree(call.body()).path("contents").get(0).path("parts").get(0).path("text").asText());
        }
        assertThat(prompts.get(0)).contains("easy difficulty").contains("level L1");
        assertThat(prompts.get(1)).contains("medium difficulty").contains("level L2");
        assertThat(prompts.get(2)).contains("hard difficulty").contains("level L3");
    }

    @Test
    @DisplayName("mix takes half from the bank and tops each level up with AI")
    void mixCombinesBankAndAi() throws Exception {
        uploadBank();
        setGeminiKey(admin, "AIza-test-key-123");
        stubGeminiQuestions(6);

        JsonNode result = generate("mix");

        assertThat(result.path("counts").path("L1").asInt()).isEqualTo(6);
        assertThat(result.path("counts").path("L2").asInt()).isEqualTo(6);
        assertThat(result.path("counts").path("L3").asInt()).isEqualTo(6);
        List<String> l1Prompts = new ArrayList<>();
        answerKey().path("levels").get(0).path("questions").forEach(q -> l1Prompts.add(q.path("question").asText()));
        assertThat(l1Prompts).contains("What is 2+2?", "What does REST stand for?", "Which are valid HTTP methods?", "Generated question 1?");
    }

    @Test
    @DisplayName("custom pads a short bank with AI, and only asks for as many as it is short")
    void customPadsShortfall() throws Exception {
        uploadBank();
        setGeminiKey(admin, "AIza-test-key-123");
        stubGeminiQuestions(6);

        JsonNode result = generate("custom");

        assertThat(result.path("counts").path("L1").asInt()).isEqualTo(6); // 3 from the bank + 3 from AI
        var firstCall = json.readTree(STUB.requests("/v1beta/models/").get(0).body());
        assertThat(firstCall.path("contents").get(0).path("parts").get(0).path("text").asText()).contains("Write exactly 3 questions");
    }

    @Test
    @DisplayName("when Gemini rejects the key, mix quietly falls back to bank-only questions")
    void geminiFailureFallsBackToBank() throws Exception {
        uploadBank();
        setGeminiKey(admin, "AIza-bad-key");
        STUB.respond("POST", "/v1beta/models/", 400, "{\"error\":{\"message\":\"API key not valid.\"}}");

        JsonNode result = generate("mix");

        assertThat(result.path("status").asText()).isEqualTo("success");
        assertThat(result.path("counts").path("L1").asInt()).isEqualTo(3);
        assertThat(result.path("counts").path("L2").asInt()).isEqualTo(2);
        assertThat(result.path("counts").path("L3").asInt()).isZero();
    }

    @Test
    @DisplayName("when Gemini fails and there's no bank, ai generation reports an error instead of an empty assessment")
    void geminiFailureWithoutBank() throws Exception {
        setGeminiKey(admin, "AIza-bad-key");
        STUB.respond("POST", "/v1beta/models/", 500, "{}");

        JsonNode result = generate("ai");

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("message").asText()).contains("No questions available");
        assertThat(jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid", Integer.class, job))
                .as("no half-built assessment is left behind").isZero();
    }

    @Test
    @DisplayName("malformed AI output (not JSON, wrong shape, unusable questions) is skipped rather than crashing")
    void malformedAiOutput() throws Exception {
        setGeminiKey(admin, "AIza-test-key-123");
        // Two unusable entries (no question text; no correct answer) and one good one.
        String mixed = "[{\"type\":\"MCQ\",\"options\":[\"a\",\"b\"],\"correct_indices\":[0]},"
                + "{\"type\":\"MCQ\",\"question\":\"No answer given\",\"options\":[\"a\",\"b\"]},"
                + "{\"type\":\"MCQ\",\"question\":\"Usable?\",\"options\":[\"yes\",\"no\"],\"correct_indices\":[0]}]";
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiText(mixed));

        JsonNode result = generate("ai");
        assertThat(result.path("counts").path("L1").asInt()).isEqualTo(1);

        STUB.reset();
        STUB.respond("POST", "/v1beta/models/", 200, Fixtures.geminiText("Sorry, I can't help with that."));
        assertThat(generate("ai").path("status").asText()).isEqualTo("error");
    }

    // ── invites ─────────────────────────────────────────────────────────

    private JsonNode mint(Object body) throws Exception {
        return bodyOf(postJsonAs(admin, "/api/invites/mint", body).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("minting per level returns one single-use link per level, valid for the configured TTL")
    void mintPerLevel() throws Exception {
        JsonNode result = mint(Map.of("session_id", job, "candidate_email", "jane@example.com", "candidate_name", "Jane",
                "levels", List.of("L1", "L2", "L3")));

        assertThat(result.path("ok").asBoolean()).isTrue();
        JsonNode invites = result.path("invites");
        assertThat(invites).hasSize(3);
        for (int i = 0; i < 3; i++) {
            String level = "L" + (i + 1);
            assertThat(invites.get(i).path("level").asText()).isEqualTo(level);
            assertThat(invites.get(i).path("url").asText())
                    .matches("https://public\\.example\\.com/assessment/JD-\\d+\\?level=" + level + "&token=[A-Za-z0-9_-]{40,}");
            assertThat(invites.get(i).path("remaining_seconds").asLong()).isEqualTo(48 * 3600);
        }
    }

    @Test
    @DisplayName("the invite TTL comes from Settings")
    void mintHonoursConfiguredTtl() throws Exception {
        postJsonAs(admin, "/api/config/invite_ttl", Map.of("ttl_seconds", 3600)).andExpect(status().isOk());

        JsonNode result = mint(Map.of("session_id", job, "candidate_email", "jane@example.com", "levels", List.of("L1")));

        assertThat(result.path("invites").get(0).path("remaining_seconds").asLong()).isEqualTo(3600);
    }

    @Test
    @DisplayName("combined minting returns ONE link covering every listed level")
    void mintCombined() throws Exception {
        JsonNode result = mint(Map.of("session_id", job, "candidate_email", "jane@example.com",
                "levels", List.of("L1", "L2"), "combined", true));

        assertThat(result.path("invites")).hasSize(1);
        assertThat(result.path("invites").get(0).path("url").asText()).contains("levels=L1,L2&token=");
        assertThat(jdbc.queryForObject("select combined from invites where candidate_email = 'jane@example.com' and job_id = ?::uuid",
                Boolean.class, job)).isTrue();
    }

    @Test
    @DisplayName("only a SHA-256 hash of each token is stored — never the token itself")
    void tokensStoredHashed() throws Exception {
        String email = "hash-" + shortId() + "@example.com"; // unique: the database is shared across test classes
        JsonNode result = mint(Map.of("session_id", job, "candidate_email", email, "levels", List.of("L1")));
        String url = result.path("invites").get(0).path("url").asText();
        String rawToken = url.substring(url.indexOf("token=") + "token=".length());

        String stored = jdbc.queryForObject("select token_hash from invites where candidate_email = ?", String.class, email);

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        assertThat(stored).isEqualTo(expected).isNotEqualTo(rawToken).hasSize(64);
        assertThat(jdbc.queryForObject("select count(*) from invites where token_hash = ?", Integer.class, rawToken)).isZero();
    }

    @Test
    @DisplayName("every mint yields a fresh token (tokens can't be re-served, since only their hash is kept)")
    void freshTokenEachTime() throws Exception {
        Map<String, Object> body = Map.of("session_id", job, "candidate_email", "jane@example.com", "levels", List.of("L1"));

        String first = mint(body).path("invites").get(0).path("url").asText();
        String second = mint(body).path("invites").get(0).path("url").asText();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("mint validates its input")
    void mintValidation() throws Exception {
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "levels", List.of("L1"))).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "candidate_email", "a@b.test", "levels", List.of())).andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", "not-a-uuid", "candidate_email", "a@b.test", "levels", List.of("L1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("upload a JD first")));
    }
}

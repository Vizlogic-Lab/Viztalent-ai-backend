package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.service.impl.AssessmentGenerationJob;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import com.smartstaff.support.PracticalStubs.Variant;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F4 — question generation and execute-to-validate, with a fake Gemini and
 *  a fake Piston (see PracticalStubs). Generation runs inline in tests. */
class AssessmentGenerationIntegrationTest extends IntegrationTestBase {

    private static final String BANK_CSV = """
            type,level,skill,difficulty,question,options,correct_index
            mcq,L1,java,easy,Bank Q1 about java?,A|B|C,0
            mcq,L1,java,easy,Bank Q2 about java?,A|B|C,1
            mcq,L1,spring,easy,Bank Q3 about spring?,A|B|C,2
            mcq,L1,sql,easy,Bank Q4 about sql?,A|B|C,0
            mcq,L1,cooking,easy,Unrelated bank question?,A|B|C,0
            msq,L1,java,medium,Bank MSQ 1 about java?,A|B|C|D,"0,1"
            msq,L1,sql,medium,Bank MSQ 2 about sql?,A|B|C|D,"1,2"
            """;

    @Autowired AssessmentGenerationJob generationJob;

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
    }

    private JsonNode generateL1(String source) throws Exception {
        return bodyOf(postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", source, "levels", List.of("L1"))).andExpect(status().isOk()));
    }

    private JsonNode statusOf() throws Exception {
        return bodyOf(getAs(admin, "/api/assessment/status/" + job).andExpect(status().isOk()));
    }

    private List<Map<String, Object>> questions(String assessmentId) {
        return jdbc.queryForList("select id::text as id, seq, type, skill, origin, validated, model_answer, "
                + "starter_code::text as starter, buggy_code::text as buggy, rubric::text as rubric, validation_log "
                + "from assessment_questions where assessment_id = ?::uuid order by seq", assessmentId);
    }

    private void uploadBank() throws Exception {
        uploadFile(admin, "/api/questions/upload", "file", "bank.csv", BANK_CSV).andExpect(status().isOk());
    }

    @Test
    @DisplayName("happy path: every slot drafted once, proven in the sandbox, saved as READY and made current")
    void happyPath() throws Exception {
        PracticalStubs.fakeGemini(STUB, Map.of());

        JsonNode queued = generateL1("ai");
        assertThat(queued.path("status").asText()).isEqualTo("queued");
        assertThat(queued.path("version").asInt()).isEqualTo(1);
        String id = queued.path("assessment_id").asText();

        JsonNode s = statusOf();
        assertThat(s.path("status").asText()).isEqualTo("READY");
        assertThat(s.path("ready").asBoolean()).isTrue();
        assertThat(s.path("generating").asBoolean()).isFalse();
        assertThat(s.path("current_version").asInt()).isEqualTo(1);
        assertThat(s.path("counts").path("L1").asInt()).isEqualTo(12);
        assertThat(s.path("slots_total").asInt()).isEqualTo(12);
        assertThat(s.path("slots_done").asInt()).isEqualTo(12);
        assertThat(s.path("unfilled")).isEmpty();

        List<Map<String, Object>> qs = questions(id);
        assertThat(qs).hasSize(12).allSatisfy(q -> {
            assertThat(q.get("validated")).isEqualTo(true);
            assertThat(q.get("origin")).isEqualTo("AI");
            assertThat((String) q.get("skill")).isIn("java", "spring", "sql");
        });
        Map<String, Long> types = qs.stream().collect(Collectors.groupingBy(q -> (String) q.get("type"), Collectors.counting()));
        assertThat(types).containsEntry("MCQ", 4L).containsEntry("MSQ", 2L).containsEntry("CODE_WRITE", 2L)
                .containsEntry("CODE_DEBUG", 1L).containsEntry("CODE_OUTPUT", 2L);
        assertThat(STUB.requests("/v1beta/models/")).as("one draft per slot, no retries").hasSize(12);

        Map<String, Object> write = qs.stream().filter(q -> q.get("type").equals("CODE_WRITE")).findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("select count(*) from question_test_cases where question_id = ?::uuid",
                Integer.class, write.get("id"))).isEqualTo(11);
        assertThat(jdbc.queryForObject("select count(*) from question_test_cases where question_id = ?::uuid and visible",
                Integer.class, write.get("id"))).isEqualTo(3);
        assertThat(json.readTree((String) write.get("rubric"))).extracting(r -> r.path("weight").asInt()).containsExactly(10, 8, 6, 6);
        assertThat((String) write.get("validation_log")).contains("java reference passed 11/11").contains("Naive solution failed 6 hidden");

        Map<String, Object> debug = qs.stream().filter(q -> q.get("type").equals("CODE_DEBUG")).findFirst().orElseThrow();
        assertThat(json.readTree((String) debug.get("starter"))).isEqualTo(json.readTree((String) debug.get("buggy")));

        getAs(admin, "/api/assessment/answer_key/" + job).andExpect(jsonPath("$.levels[0].count").value(12));
    }

    @Test
    @DisplayName("CODE_OUTPUT's expected answer is the real output of running the snippet, not the AI's claim")
    void codeOutputUsesRealOutput() throws Exception {
        PracticalStubs.fakeGemini(STUB, Map.of());
        String id = generateL1("ai").path("assessment_id").asText();

        List<Object> answers = questions(id).stream().filter(q -> q.get("type").equals("CODE_OUTPUT"))
                .map(q -> q.get("model_answer")).toList();
        assertThat(answers).hasSize(2).containsOnly("42");
    }

    @Test
    @DisplayName("a failing reference is retried twice with the reason, then the slot is left unfilled; a bad MCQ falls back to the bank")
    void retriesThenBankFallback() throws Exception {
        uploadBank();
        PracticalStubs.fakeGemini(STUB, Map.of("CODE_WRITE", Variant.BAD_REFERENCE, "MCQ", Variant.TWO_ANSWER_MCQ));

        String id = generateL1("ai").path("assessment_id").asText();

        List<String> writePrompts = STUB.requests("/v1beta/models/").stream()
                .filter(r -> PracticalStubs.typeOf(r).equals("CODE_WRITE")).map(PracticalStubs::promptOf).toList();
        assertThat(writePrompts).as("2 slots x 3 attempts").hasSize(6);
        assertThat(writePrompts).filteredOn(p -> p.contains("previous attempt was rejected")).hasSize(4)
                .allSatisfy(p -> assertThat(p).contains("reference solution fails"));

        JsonNode s = statusOf();
        assertThat(s.path("status").asText()).isEqualTo("READY");
        assertThat(s.path("unfilled")).hasSize(2);
        s.path("unfilled").forEach(u -> {
            assertThat(u.path("type").asText()).isEqualTo("CODE_WRITE");
            assertThat(u.path("reason").asText()).contains("reference solution fails");
        });

        List<Map<String, Object>> mcqs = questions(id).stream().filter(q -> q.get("type").equals("MCQ")).toList();
        assertThat(mcqs).hasSize(4).allSatisfy(q -> assertThat(q.get("origin")).isEqualTo("BANK"));
        assertThat(mcqs).extracting(q -> (String) q.get("validation_log"))
                .allSatisfy(log -> assertThat(log).contains("exactly one correct option").contains("Question bank"));
        assertThat(jdbc.queryForObject("select count(*) from assessment_questions where assessment_id = ?::uuid "
                + "and bank_item_id is not null", Integer.class, id)).isEqualTo(4);
        assertThat(jdbc.queryForList("select prompt from assessment_questions where assessment_id = ?::uuid", String.class, id))
                .as("bank questions outside the role profile are never used").doesNotContain("Unrelated bank question?");
    }

    @Test
    @DisplayName("custom: the bank fills what it can first; Gemini writes only what the bank lacks")
    void customIsBankFirst() throws Exception {
        uploadBank();
        PracticalStubs.fakeGemini(STUB, Map.of());

        String id = generateL1("custom").path("assessment_id").asText();

        Map<String, String> originByType = new HashMap<>();
        questions(id).forEach(q -> originByType.merge((String) q.get("type"), (String) q.get("origin"),
                (a, b) -> a.equals(b) ? a : "MIXED"));
        assertThat(originByType).containsEntry("MCQ", "BANK").containsEntry("MSQ", "BANK")
                .containsEntry("CODE_WRITE", "AI").containsEntry("LOGIC", "AI");
        assertThat(STUB.requests("/v1beta/models/")).as("only the 6 non-bank slots").hasSize(6);
    }

    @Test
    @DisplayName("mix: even slots try the bank first, odd slots try Gemini first")
    void mixAlternates() throws Exception {
        uploadBank();
        PracticalStubs.fakeGemini(STUB, Map.of());

        String id = generateL1("mix").path("assessment_id").asText();

        List<Map<String, Object>> qs = questions(id);
        for (Map<String, Object> q : qs.subList(0, 6)) {
            int seq = (Integer) q.get("seq");
            assertThat(q.get("origin")).as("slot %d", seq).isEqualTo(seq % 2 == 0 ? "BANK" : "AI");
        }
    }

    @Test
    @DisplayName("a draft aimed at a skill outside the role profile is rejected")
    void wrongSkillRejected() throws Exception {
        PracticalStubs.fakeGemini(STUB, Map.of("LOGIC", Variant.WRONG_SKILL));

        generateL1("ai");

        JsonNode unfilled = statusOf().path("unfilled");
        assertThat(unfilled).hasSize(1);
        assertThat(unfilled.get(0).path("type").asText()).isEqualTo("LOGIC");
        assertThat(unfilled.get(0).path("reason").asText()).contains("'cobol', which isn't a skill in this role profile");
    }

    @Test
    @DisplayName("Gemini down: one failed call opens the circuit and the rest comes from the bank only")
    void geminiDownUsesBankOnly() throws Exception {
        uploadBank();
        STUB.respond("POST", "/v1beta/models/", 503, "{\"error\":{\"message\":\"overloaded\"}}");

        String id = generateL1("ai").path("assessment_id").asText();

        JsonNode s = statusOf();
        assertThat(s.path("status").asText()).isEqualTo("READY");
        assertThat(s.path("counts").path("L1").asInt()).isEqualTo(6);
        assertThat(s.path("unfilled")).hasSize(6);
        assertThat(s.path("errors").get(0).asText()).contains("Gemini was unavailable");
        assertThat(questions(id)).allSatisfy(q -> assertThat(q.get("origin")).isEqualTo("BANK"));
        assertThat(STUB.requests("/v1beta/models/")).as("one draft attempt (the client retries it 3 times), then no more")
                .hasSize(3);
    }

    @Test
    @DisplayName("status moves QUEUED -> GENERATING -> VALIDATING -> READY")
    void statusSequence() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        String latest = "select status from assessments where job_id = ?::uuid order by version desc limit 1";
        var drafts = PracticalStubs.draftResponder(Map.of());
        STUB.on("POST", "/v1beta/models/", req -> {
            seen.add("gemini:" + jdbc.queryForObject(latest, String.class, job));
            return drafts.apply(req);
        });
        STUB.on("POST", "/api/v2/execute", req -> {
            seen.add("piston:" + jdbc.queryForObject(latest, String.class, job));
            return StubResponse.json(200, Fixtures.pistonRun(PracticalStubs.runFake(req)));
        });

        assertThat(generateL1("ai").path("status").asText()).isEqualTo("queued");

        assertThat(seen).contains("gemini:GENERATING", "piston:VALIDATING");
        assertThat(seen.indexOf("piston:VALIDATING")).isGreaterThan(seen.lastIndexOf("gemini:GENERATING"));
        assertThat(statusOf().path("status").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("without a working code runner, coding slots are left unfilled and no Gemini call is spent on them")
    void noCodeRunner() throws Exception {
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", "")).andExpect(status().isOk());

        generateL1("ai");

        JsonNode s = statusOf();
        assertThat(s.path("unfilled")).hasSize(5);
        s.path("unfilled").forEach(u -> assertThat(u.path("reason").asText()).contains("code runner isn't available"));
        assertThat(STUB.requests("/v1beta/models/")).hasSize(7);
    }

    @Test
    @DisplayName("a regeneration that fails leaves the previous READY version current; one in progress blocks another")
    void regeneration() throws Exception {
        PracticalStubs.fakeGemini(STUB, Map.of());
        generateL1("ai");

        jdbc.update("insert into assessments (job_id, source, version, status, is_current) values (?::uuid, 'AI', 2, 'GENERATING', false)", job);
        postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", job, "question_source", "ai"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("generation_in_progress"));
        generationJob.failInterruptedGenerations();
        assertThat(jdbc.queryForObject("select status from assessments where job_id = ?::uuid and version = 2", String.class, job))
                .as("in-progress work is failed at startup").isEqualTo("FAILED");

        STUB.respond("POST", "/v1beta/models/", 401, "{\"error\":{\"message\":\"API key not valid\"}}");
        generateL1("ai");

        JsonNode s = statusOf();
        assertThat(s.path("version").asInt()).isEqualTo(3);
        assertThat(s.path("status").asText()).isEqualTo("FAILED");
        assertThat(s.path("ready").asBoolean()).isFalse();
        assertThat(s.path("error").asText()).contains("No question could be generated");
        assertThat(s.path("current_version").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("invites are minted only against the current READY version, and only for levels it has")
    void invitesUseCurrentReadyVersion() throws Exception {
        Map<String, Object> mintL1 = Map.of("session_id", job, "candidate_email", "c@example.com", "levels", List.of("L1"));
        postJsonAs(admin, "/api/invites/mint", mintL1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_ready_assessment"));

        PracticalStubs.fakeGemini(STUB, Map.of());
        String id = generateL1("ai").path("assessment_id").asText();

        postJsonAs(admin, "/api/invites/mint", mintL1).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select assessment_id::text from invites where candidate_email = 'c@example.com' "
                + "and job_id = ?::uuid", String.class, job)).isEqualTo(id);
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "candidate_email", "c@example.com", "levels", List.of("L2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("level_not_in_assessment"));
    }
}

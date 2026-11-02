package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hand-authored 50-question assessments: draft, AI sections in batches, manual
 *  questions, publish rules, invite link, candidate attempt, hybrid grading
 *  (ONE_WORD with a typo, PROJECT against its rubric), lock and archive. */
class CustomAssessmentIntegrationTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern COUNT = Pattern.compile("Write (\\d+) different");
    private static final String CANDIDATE = "/api/candidate/assessment/";
    private static final AtomicInteger SERIAL = new AtomicInteger();

    private Account admin;
    private String job;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring boot, oracle sql");
        setGeminiKey(admin, "AIza-test-key-123");
        STUB.reset();
        PracticalStubs.resetGrader();
        var drafts = PracticalStubs.draftResponder(Map.of());
        STUB.on("POST", "/v1beta/models/", req -> {
            JsonNode props = read(req.body()).path("generationConfig").path("responseSchema").path("properties");
            if (props.has("questions")) return StubResponse.json(200, Fixtures.geminiText(batch(props, PracticalStubs.promptOf(req))));
            return drafts.apply(req);   // rubric grading and summaries
        });
        base = "/api/jobs/" + job + "/custom-assessments";
    }

    /** {"questions": [...]} with as many distinct items as the prompt asks for. */
    private static String batch(JsonNode props, String prompt) {
        JsonNode item = props.path("questions").path("items").path("properties");
        Matcher m = COUNT.matcher(prompt);
        int n = m.find() ? Integer.parseInt(m.group(1)) : 1;
        ObjectNode root = JSON.createObjectNode();
        ArrayNode qs = root.putArray("questions");
        for (int i = 0; i < n; i++) {
            int k = SERIAL.incrementAndGet();
            ObjectNode o = qs.addObject().put("skill", "java").put("scope", "GENERIC");
            if (item.has("accepted_answers")) {
                o.put("prompt", "Which OOP principle lets one interface have many forms? #" + k)
                        .put("answer", "polymorphism").put("explanation", "By definition.");
                o.putArray("accepted_answers").add("polymorphic behaviour");
            } else {
                o.put("question", "Which collection keeps insertion order? #" + k).put("explanation", "LinkedHashMap does.");
                o.putArray("options").add("LinkedHashMap").add("HashMap").add("TreeMap").add("IdentityHashMap");
                o.putArray("correct_indices").add(0);
            }
        }
        return root.toString();
    }

    private static JsonNode read(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> scenario(int i) {
        return Map.of("type", "SCENARIO", "prompt", "Production API latency jumped from 200 ms to 8 s (" + i + "). What do you do?",
                "model_answer", "Check recent deploys, thread dumps, DB pool, slow queries, then mitigate.",
                "key_points", List.of("recent deploys", "thread dump", "connection pool", "slow queries"));
    }

    private static Map<String, Object> project(int i) {
        return Map.of("type", "PROJECT", "prompt", "Design an idempotent payment API (" + i + ").",
                "rubric", List.of(Map.of("criterion", "idempotency", "weight", 3, "description", "Uses an idempotency key"),
                        Map.of("criterion", "persistence", "weight", 2, "description", "Unique constraint in the database")));
    }

    private JsonNode create() throws Exception {
        return bodyOf(postJsonAs(admin, base, Map.of("title", "Java Backend — client paper")).andExpect(status().isCreated()));
    }

    /** A full, publishable paper: MCQ and ONE_WORD by AI, SCENARIO and PROJECT by hand. */
    private String fullDraft() throws Exception {
        String id = create().path("id").asText();
        postJsonAs(admin, base + "/" + id + "/sections/generate", Map.of("type", "MCQ")).andExpect(status().isOk());
        postJsonAs(admin, base + "/" + id + "/sections/generate", Map.of("type", "ONE_WORD")).andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            postJsonAs(admin, base + "/" + id + "/questions", scenario(i)).andExpect(status().isCreated());
            postJsonAs(admin, base + "/" + id + "/questions", project(i)).andExpect(status().isCreated());
        }
        return id;
    }

    @Test
    @DisplayName("a new draft uses the default 25/15/5/5 scheme worth 100 and can't be published empty")
    void draftDefaults() throws Exception {
        JsonNode a = create();
        assertThat(a.path("lifecycle").asText()).isEqualTo("DRAFT");
        assertThat(a.path("target_total").asInt()).isEqualTo(100);
        assertThat(a.path("sections")).extracting(s -> s.path("required").asInt()).containsExactly(25, 15, 5, 5);
        assertThat(a.path("publishable").asBoolean()).isFalse();
        postJsonAs(admin, base + "/" + a.path("id").asText() + "/publish", Map.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("not_publishable"));
    }

    @Test
    @DisplayName("Generate by AI fills a section in batches of 10, without repeats")
    void aiSectionBatches() throws Exception {
        String id = create().path("id").asText();
        JsonNode a = bodyOf(postJsonAs(admin, base + "/" + id + "/sections/generate", Map.of("type", "MCQ"))
                .andExpect(status().isOk()));
        assertThat(a.path("sections").get(0).path("present").asInt()).isEqualTo(25);
        long calls = STUB.requests("/v1beta/models/").size();
        assertThat(calls).as("25 MCQs = batches of 10 + 10 + 5").isEqualTo(3);
        Set<String> prompts = new HashSet<>();
        a.path("questions").forEach(q -> prompts.add(q.path("prompt").asText()));
        assertThat(prompts).hasSize(25);
        postJsonAs(admin, base + "/" + id + "/sections/generate", Map.of("type", "MCQ"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("section_full"));
    }

    @Test
    @DisplayName("manual questions are validated per type, and a full section refuses more")
    void manualValidation() throws Exception {
        String id = create().path("id").asText();
        postJsonAs(admin, base + "/" + id + "/questions", Map.of("type", "MCQ", "prompt", "Pick one",
                "options", List.of("A", "B"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("correct_index")));
        postJsonAs(admin, base + "/" + id + "/questions", Map.of("type", "CODE_WRITE", "prompt", "Write code"))
                .andExpect(status().isBadRequest());
        postJsonAs(admin, base + "/" + id + "/questions", Map.of("type", "ESSAY", "prompt", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_request_body"));
        for (int i = 0; i < 5; i++) postJsonAs(admin, base + "/" + id + "/questions", project(i)).andExpect(status().isCreated());
        postJsonAs(admin, base + "/" + id + "/questions", project(9))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("section_full"));
    }

    @Test
    @DisplayName("marking scheme defaults must reach the target")
    void schemeMustBalance() throws Exception {
        String id = create().path("id").asText();
        putJsonAs(admin, base + "/" + id + "/marking-scheme", Map.of("mcq_marks", 2, "one_word_marks", 2,
                "scenario_marks", 4, "project_marks", 4, "target_total", 100)).andExpect(status().isBadRequest());
        JsonNode a = bodyOf(putJsonAs(admin, base + "/" + id + "/marking-scheme", Map.of("mcq_marks", 2, "one_word_marks", 2,
                "scenario_marks", 10, "project_marks", 10, "target_total", 180)).andExpect(status().isOk()));
        assertThat(a.path("target_total").asInt()).isEqualTo(180);
    }

    @Test
    @DisplayName("publish -> invite -> candidate -> hybrid grading -> locked -> archived")
    void endToEnd() throws Exception {
        String id = fullDraft();
        JsonNode published = bodyOf(postJsonAs(admin, base + "/" + id + "/publish", Map.of()).andExpect(status().isOk()));
        assertThat(published.path("lifecycle").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("current").asBoolean()).isTrue();
        assertThat(published.path("total_marks").asInt()).isEqualTo(100);

        JsonNode invite = bodyOf(postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job,
                "candidate_email", "asha@example.com", "candidate_name", "Asha", "levels", List.of("L1"), "combined", false))
                .andExpect(status().isOk()));
        String url = invite.path("invites").get(0).path("url").asText();
        String token = url.substring(url.indexOf("token=") + 6);

        JsonNode paper = bodyOf(mvc.perform(get(CANDIDATE + token)).andExpect(status().isOk()));
        assertThat(paper.path("questions")).hasSize(50);
        assertThat(paper.toString()).as("no answer key reaches the candidate")
                .doesNotContain("polymorphism").doesNotContain("accepted_answers").doesNotContain("idempotency key");

        boolean first = true;
        for (JsonNode q : paper.path("questions")) {
            String qid = q.path("question_id").asText();
            Map<String, Object> answer = switch (q.path("type").asText()) {
                case "MCQ" -> Map.of("selected_indices", List.of(0));
                // A typo still matches a long word; the second one uses an accepted alternative.
                case "ONE_WORD" -> Map.of("text", first ? "Polymorfism" : "  polymorphic behaviour. ");
                default -> Map.of("text", "I would use an idempotency key with a unique constraint and check deploys.");
            };
            if (q.path("type").asText().equals("ONE_WORD")) first = false;
            mvc.perform(put(CANDIDATE + token + "/answers/" + qid).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(answer))).andExpect(status().isOk());
        }

        // The paper is locked as soon as the candidate started.
        postJsonAs(admin, base + "/" + id + "/questions", scenario(7))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("assessment_locked"));

        postJson(CANDIDATE + token + "/submit", Map.of()).andExpect(status().isOk());

        Map<String, Object> attempt = jdbc.queryForMap(
                "select status, recommendation, theory_score, hands_on_score, overall_score from assessment_attempts "
                        + "where assessment_id = ?::uuid", id);
        assertThat(attempt.get("status")).isEqualTo("EVALUATED");
        assertThat(((Number) attempt.get("theory_score")).doubleValue()).as("MCQ + ONE_WORD all correct").isEqualTo(100.0);
        assertThat(((Number) attempt.get("hands_on_score")).doubleValue()).as("rubric-graded").isEqualTo(100.0);
        assertThat(attempt.get("recommendation")).isEqualTo("PROCEED");
        Integer oneWordFull = jdbc.queryForObject("select count(*) from attempt_answers an join assessment_questions q "
                + "on q.id = an.question_id where q.assessment_id = ?::uuid and q.type = 'ONE_WORD' "
                + "and an.final_score = q.points", Integer.class, id);
        assertThat(oneWordFull).isEqualTo(15);

        JsonNode archived = bodyOf(postJsonAs(admin, base + "/" + id + "/archive", Map.of()).andExpect(status().isOk()));
        assertThat(archived.path("lifecycle").asText()).isEqualTo("ARCHIVED");
        assertThat(archived.path("current").asBoolean()).isFalse();
        mvc.perform(withAuth(delete(base + "/" + id + "/questions/" + archived.path("questions").get(0).path("id").asText()), admin))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a published, unstarted paper can be edited only if it stays valid")
    void publishedEditsStayValid() throws Exception {
        String id = fullDraft();
        JsonNode published = bodyOf(postJsonAs(admin, base + "/" + id + "/publish", Map.of()).andExpect(status().isOk()));
        String projectId = published.path("questions").get(49).path("id").asText();
        mvc.perform(withAuth(delete(base + "/" + id + "/questions/" + projectId), admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("would_unpublish"));
        putJsonAs(admin, base + "/" + id + "/questions/" + projectId, project(42)).andExpect(status().isOk());
        assertThat(bodyOf(getAs(admin, base)).get(0).path("questions").asInt()).isEqualTo(50);
    }
}

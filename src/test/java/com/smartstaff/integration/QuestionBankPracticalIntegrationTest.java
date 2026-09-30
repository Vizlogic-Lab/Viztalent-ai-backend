package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F5 — the question bank holds curated practical questions of every type,
 *  each proven on upload the same way generated ones are (coding in the
 *  sandbox), and generation draws on them with role-family and language
 *  filtering. Uses the fake Piston (PracticalStubs): "// REFERENCE"/"// FIXED"
 *  print the stdin sum, "// NAIVE"/"// BUGGY" fail the non-zero tests. */
class QuestionBankPracticalIntegrationTest extends IntegrationTestBase {

    // A valid CODE_WRITE item (reference sums, naive prints 0 so hidden tests catch it),
    // a valid MCQ, and a broken CODE_WRITE whose "reference" is wrong (prints sum+1).
    private static final String JSON_BANK = """
            [
              {
                "type": "CODE_WRITE", "level": "L1", "skill": "java", "role_families": ["BACKEND"],
                "title": "Add two", "statement": "Read two ints, print their sum.",
                "languages": ["java"],
                "starter_code": {"java": "// STARTER"},
                "reference_solution": {"java": "// REFERENCE"},
                "naive_solution": {"java": "// NAIVE"},
                "tests": [
                  {"input": "1 2", "expected_output": "3", "visible": true, "category": "BASIC"},
                  {"input": "3 4", "expected_output": "7", "visible": true, "category": "BASIC"},
                  {"input": "5 6", "expected_output": "11", "visible": true, "category": "BASIC"},
                  {"input": "7 8", "expected_output": "15", "visible": false, "category": "EDGE"},
                  {"input": "9 10", "expected_output": "19", "visible": false, "category": "EDGE"},
                  {"input": "11 12", "expected_output": "23", "visible": false, "category": "EDGE"},
                  {"input": "13 14", "expected_output": "27", "visible": false, "category": "EDGE"},
                  {"input": "15 16", "expected_output": "31", "visible": false, "category": "BASIC"},
                  {"input": "17 18", "expected_output": "35", "visible": false, "category": "BASIC"},
                  {"input": "19 20", "expected_output": "39", "visible": false, "category": "LARGE"},
                  {"input": "21 22", "expected_output": "43", "visible": false, "category": "LARGE"}
                ]
              },
              {
                "type": "MCQ", "level": "L1", "skill": "java",
                "question": "Which access modifier is most restrictive?",
                "options": ["public", "protected", "private", "package-private"],
                "correct_indices": [2]
              },
              {
                "type": "CODE_WRITE", "level": "L1", "skill": "java",
                "title": "Broken", "statement": "Read two ints, print their sum.",
                "languages": ["java"],
                "starter_code": {"java": "// STARTER"},
                "reference_solution": {"java": "// BADREF"},
                "naive_solution": {"java": "// NAIVE"},
                "tests": [
                  {"input": "1 2", "expected_output": "3", "visible": true, "category": "BASIC"},
                  {"input": "3 4", "expected_output": "7", "visible": true, "category": "BASIC"},
                  {"input": "5 6", "expected_output": "11", "visible": true, "category": "BASIC"},
                  {"input": "7 8", "expected_output": "15", "visible": false, "category": "EDGE"},
                  {"input": "9 10", "expected_output": "19", "visible": false, "category": "EDGE"},
                  {"input": "11 12", "expected_output": "23", "visible": false, "category": "EDGE"},
                  {"input": "13 14", "expected_output": "27", "visible": false, "category": "EDGE"},
                  {"input": "15 16", "expected_output": "31", "visible": false, "category": "BASIC"},
                  {"input": "17 18", "expected_output": "35", "visible": false, "category": "BASIC"},
                  {"input": "19 20", "expected_output": "39", "visible": false, "category": "LARGE"},
                  {"input": "21 22", "expected_output": "43", "visible": false, "category": "LARGE"}
                ]
              }
            ]
            """;

    private Account admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        PracticalStubs.fakePiston(STUB);
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a JSON bank is proven on upload: coding items run in the sandbox, and the broken one is skipped with a reason")
    void jsonBankValidatedOnUpload() throws Exception {
        JsonNode result = bodyOf(uploadFile(admin, "/api/questions/upload", "file", "bank.json", JSON_BANK)
                .andExpect(status().isOk()));

        assertThat(result.path("added").asInt()).as("the two valid items, not the broken CODE_WRITE").isEqualTo(2);
        assertThat(result.path("warnings")).anySatisfy(w ->
                assertThat(w.asText()).contains("CODE_WRITE").contains("reference solution fails"));

        Map<String, Object> code = jdbc.queryForMap(
                "select id::text as id, validated, dimension, competency, points from question_bank_items where type = 'CODE_WRITE'");
        assertThat(code.get("validated")).isEqualTo(true);
        assertThat(code.get("dimension")).isEqualTo("HANDS_ON");
        assertThat(code.get("competency")).isEqualTo("CODING");
        assertThat(jdbc.queryForObject("select count(*) from question_bank_test_cases where item_id = ?::uuid",
                Integer.class, code.get("id"))).as("all 11 tests stored").isEqualTo(11);
        assertThat(jdbc.queryForObject("select role_families::text from question_bank_items where type = 'CODE_WRITE'", String.class))
                .contains("BACKEND");
    }

    @Test
    @DisplayName("a bank coding question is used in generation for a matching role, its tests copied to the assessment")
    void bankCodingUsedInGeneration() throws Exception {
        uploadFile(admin, "/api/questions/upload", "file", "bank.json", JSON_BANK).andExpect(status().isOk());
        String job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        // No Gemini key: generation can only use the bank.
        JsonNode gen = bodyOf(postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "custom", "levels", List.of("L1"))).andExpect(status().isOk()));
        assertThat(gen.path("status").asText()).isEqualTo("queued");

        JsonNode status = bodyOf(getAs(admin, "/api/assessment/status/" + job).andExpect(status().isOk()));
        assertThat(status.path("status").asText()).isEqualTo("READY");

        // The bank's CODE_WRITE landed in the assessment with its tests copied.
        Integer copied = jdbc.queryForObject(
                "select count(*) from question_test_cases tc join assessment_questions q on q.id = tc.question_id "
                        + "join assessments a on a.id = q.assessment_id where a.job_id = ?::uuid and q.type = 'CODE_WRITE' "
                        + "and q.origin = 'BANK'", Integer.class, job);
        assertThat(copied).isEqualTo(11);
    }

    @Test
    @DisplayName("a bank item restricted to another role family is not offered to this role")
    void roleFamilyFiltersBankItems() throws Exception {
        // Same item but restricted to DATA; a BACKEND job must not receive it.
        String dataOnly = JSON_BANK.replace("\"role_families\": [\"BACKEND\"]", "\"role_families\": [\"DATA\"]");
        uploadFile(admin, "/api/questions/upload", "file", "bank.json", dataOnly).andExpect(status().isOk());
        String job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");

        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "custom", "levels", List.of("L1"))).andExpect(status().isOk());

        Integer codeWrite = jdbc.queryForObject(
                "select count(*) from assessment_questions q join assessments a on a.id = q.assessment_id "
                        + "where a.job_id = ?::uuid and q.type = 'CODE_WRITE'", Integer.class, job);
        assertThat(codeWrite).as("the DATA-only item is filtered out; the MCQ still applies").isZero();
    }
}

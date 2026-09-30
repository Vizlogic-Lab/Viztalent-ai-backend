package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.entity.*;
import com.smartstaff.repository.AssessmentQuestionRepository;
import com.smartstaff.repository.AssessmentRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.support.IntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F2 — schema: migration over existing V1-V8 data, versioning invariants,
 *  practical question fields and test cases. */
class PracticalSchemaIntegrationTest extends IntegrationTestBase {

    private static final String BANK_CSV = """
            type,level,skill,difficulty,question,options,correct_index
            mcq,L1,Math,easy,What is 2+2?,3|4|5,1
            msq,L1,HTTP,medium,Which are valid HTTP methods?,GET|POST|FETCH|DELETE,"0,1,3"
            """;

    @Autowired DataSource dataSource;
    @Autowired AssessmentRepository assessments;
    @Autowired AssessmentQuestionRepository questions;
    @Autowired JobRepository jobs;
    @Autowired TransactionTemplate tx;

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Engineer", "java, spring, sql");
    }

    private void generateFromBank() throws Exception {
        uploadFile(admin, "/api/questions/upload", "file", "bank.csv", BANK_CSV).andExpect(status().isOk());
        postJsonAs(admin, "/api/assessment/generate", Map.of("session_id", job, "question_source", "custom"))
                .andExpect(status().isOk());
    }

    // ── migration over legacy data ──────────────────────────────────────

    @Test
    @DisplayName("V9-V11 apply on a database that already holds V1-V8 assessments")
    void migratesExistingV8Data() {
        String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("8").load().migrate();

            jdbc.update("insert into " + schema + ".jobs (id, title, owner_id) values "
                    + "('11111111-1111-1111-1111-111111111111', 'Legacy job', 'u1'),"
                    + "('22222222-2222-2222-2222-222222222222', 'Failed job', 'u1')");
            jdbc.update("insert into " + schema + ".assessments (id, job_id, source, ready, created_at) values "
                    + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'MIX', true, now() - interval '1 day'),"
                    + "('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', 'AI', false, now())");
            jdbc.update("insert into " + schema + ".assessment_questions (assessment_id, level, type, prompt, options, correct_indices, created_at) values "
                    + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'L1', 'MCQ', 'Q mcq', '[\"a\",\"b\"]', '[0]', now() - interval '3 minute'),"
                    + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'L1', 'MSQ', 'Q msq', '[\"a\",\"b\",\"c\"]', '[0,2]', now() - interval '2 minute'),"
                    + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'L2', 'CODING', 'Q coding', '[]', '[]', now() - interval '1 minute'),"
                    + "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'L2', 'DESCRIPTIVE', 'Q descriptive', '[]', '[]', now())");

            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();

            List<Map<String, Object>> a = jdbc.queryForList("select job_id::text as job, version, status, is_current, "
                    + "generated_at is not null as generated from " + schema + ".assessments order by created_at");
            assertThat(a.get(0)).containsEntry("version", 1).containsEntry("status", "READY")
                    .containsEntry("is_current", true).containsEntry("generated", true);
            assertThat(a.get(1)).containsEntry("status", "FAILED").containsEntry("generated", false);

            List<Map<String, Object>> q = jdbc.queryForList("select level, seq, type, dimension, competency, points, validated "
                    + "from " + schema + ".assessment_questions order by level, seq");
            assertThat(q).extracting(r -> r.get("type")).containsExactly("MCQ", "MSQ", "CODE_WRITE", "SCENARIO");
            assertThat(q).extracting(r -> r.get("seq")).containsExactly(0, 1, 0, 1);
            assertThat(q).extracting(r -> r.get("dimension")).containsExactly("THEORY", "THEORY", "HANDS_ON", "HANDS_ON");
            assertThat(q).extracting(r -> r.get("competency")).containsExactly("CONCEPTS", "CONCEPTS", "CODING", "APPLICATION");
            assertThat(q).extracting(r -> r.get("validated")).containsExactly(true, true, false, false);
            assertThat(q).extracting(r -> r.get("points")).containsOnly(5);

            assertThatThrownBy(() -> jdbc.update("insert into " + schema + ".assessments (job_id, source, version, status, is_current) "
                    + "values ('11111111-1111-1111-1111-111111111111', 'AI', 2, 'READY', true)"))
                    .as("a second current version for the same job").isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("insert into " + schema + ".assessments (job_id, source, version, status, is_current) "
                    + "values ('11111111-1111-1111-1111-111111111111', 'AI', 2, 'QUEUED', false)");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".assessments where job_id = '11111111-1111-1111-1111-111111111111'",
                    Integer.class)).as("UNIQUE(job_id) is gone").isEqualTo(2);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    // ── constraints ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the schema rejects unknown question types, versions clashing, and bad test categories")
    void constraints() throws Exception {
        generateFromBank();
        String assessmentId = jdbc.queryForObject("select id::text from assessments where job_id = ?::uuid", String.class, job);

        assertThatThrownBy(() -> jdbc.update("insert into assessment_questions (assessment_id, level, type, dimension, competency, prompt) "
                + "values (?::uuid, 'L1', 'ESSAY', 'THEORY', 'CONCEPTS', 'x')", assessmentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into assessments (job_id, source, version, status, is_current) "
                + "values (?::uuid, 'AI', 1, 'READY', false)", job))
                .as("(job_id, version) is unique").isInstanceOf(DataIntegrityViolationException.class);
        String questionId = jdbc.queryForObject("select id::text from assessment_questions where assessment_id = ?::uuid limit 1",
                String.class, assessmentId);
        assertThatThrownBy(() -> jdbc.update("insert into question_test_cases (question_id, seq, expected_output, category) "
                + "values (?::uuid, 0, '1', 'HUGE')", questionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── practical question round-trip ───────────────────────────────────

    @Test
    @DisplayName("a CODE_WRITE question with code maps, rubric and visible/hidden tests round-trips; deleting it cascades to tests")
    void practicalQuestionRoundTrip() throws Exception {
        generateFromBank();
        UUID jobId = UUID.fromString(job);

        UUID questionId = tx.execute(s -> {
            Assessment a = assessments.findByJobIdAndCurrentTrue(jobId).orElseThrow();
            AssessmentQuestion q = new AssessmentQuestion();
            q.setAssessment(a);
            q.setLevel("L1");
            q.setSeq(10);
            q.setType(QuestionType.CODE_WRITE);
            q.setPoints(20);
            q.setTimeEstimateSec(720);
            q.setPrompt("Read n numbers and print their sum.");
            q.setSkill("java");
            q.setDifficulty(Difficulty.MEDIUM.label());
            q.setLanguages(List.of("java", "python"));
            q.setStarterCode(Map.of("java", "class Main {}", "python", "import sys"));
            q.setReferenceSolution(Map.of("python", "print(sum(map(int, sys.stdin.read().split()[1:])))"));
            q.setRubric(List.of(new RubricCriterion("approach", 10, "Linear scan"),
                    new RubricCriterion("readability", 6, "Clear names")));
            q.setValidated(true);
            q.setValidationLog("reference passed 11/11");
            for (int i = 0; i < 3; i++) q.addTestCase(testCase("1\n" + i, String.valueOf(i), true, TestCaseCategory.BASIC, null));
            q.addTestCase(testCase("0\n", "0", false, TestCaseCategory.EDGE, null));
            q.addTestCase(testCase("2\n0.1 0.2", "0.3", false, TestCaseCategory.LARGE, new BigDecimal("0.000001")));
            return questions.save(q).getId();
        });

        tx.executeWithoutResult(s -> {
            AssessmentQuestion q = questions.findById(questionId).orElseThrow();
            assertThat(q.getDimension()).isEqualTo(Dimension.HANDS_ON);
            assertThat(q.getCompetency()).isEqualTo(Competency.CODING);
            assertThat(q.getStarterCode()).containsEntry("python", "import sys");
            assertThat(q.getRubric()).extracting(RubricCriterion::weight).containsExactly(10, 6);
            assertThat(q.getTestCases()).hasSize(5);
            assertThat(q.getTestCases()).filteredOn(t -> !t.isVisible()).hasSize(2);
            assertThat(q.getTestCases().get(4).getFloatTolerance()).isEqualByComparingTo("0.000001");
        });

        jdbc.update("delete from assessment_questions where id = ?::uuid", questionId.toString());
        assertThat(jdbc.queryForObject("select count(*) from question_test_cases where question_id = ?::uuid",
                Integer.class, questionId.toString())).isZero();
    }

    private static QuestionTestCase testCase(String input, String output, boolean visible, TestCaseCategory category,
                                             BigDecimal tolerance) {
        QuestionTestCase t = new QuestionTestCase();
        t.setInput(input);
        t.setExpectedOutput(output);
        t.setVisible(visible);
        t.setCategory(category);
        t.setWeight(category == TestCaseCategory.BASIC ? BigDecimal.ONE : new BigDecimal("1.5"));
        t.setFloatTolerance(tolerance);
        return t;
    }

    // ── blueprint and versioning through the API ────────────────────────

    @Test
    @DisplayName("generation stores the per-level blueprint built from the role profile")
    void blueprintStored() throws Exception {
        generateFromBank();

        JsonNode blueprint = json.readTree(jdbc.queryForObject(
                "select blueprint::text from assessments where job_id = ?::uuid and is_current", String.class, job));
        assertThat(blueprint.fieldNames()).toIterable().containsExactly("L1", "L2", "L3");
        JsonNode l1 = blueprint.path("L1");
        assertThat(l1.path("roleFamily").asText()).isEqualTo("BACKEND");
        assertThat(l1.path("totalPoints").asInt()).isEqualTo(100);
        assertThat(l1.path("slots")).hasSize(12);
        assertThat(l1.path("languages")).extracting(JsonNode::asText).containsExactly("java");

        tx.executeWithoutResult(s -> {
            Blueprint b = assessments.findByJobIdAndCurrentTrue(UUID.fromString(job)).orElseThrow().getBlueprint().get("L2");
            assertThat(b.points(Dimension.THEORY)).isEqualTo(20);
        });
    }

    @Test
    @DisplayName("concurrent regenerations get distinct versions and leave exactly one current")
    void concurrentRegeneration() throws Exception {
        uploadFile(admin, "/api/questions/upload", "file", "bank.csv", BANK_CSV).andExpect(status().isOk());

        List<Integer> statuses = inParallel(4, () -> postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "custom")).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(jdbc.queryForList("select version from assessments where job_id = ?::uuid order by version", Integer.class, job))
                .containsExactly(1, 2, 3, 4);
        assertThat(jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid and is_current", Integer.class, job))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from assessments where job_id = ?::uuid and is_current", Integer.class, job))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("deleting the job removes every version")
    void jobDeletionCascades() throws Exception {
        generateFromBank();
        generateFromBank();
        mvc.perform(withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/jobs/" + job), admin))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from assessments where job_id = ?::uuid", Integer.class, job)).isZero();
    }
}

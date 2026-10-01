package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.entity.Role;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F14 — scorecard PDF from the attempt result, dashboard funnel, activity
 *  events written by the services, and the .xlsx report. */
class ScorecardDashboardActivityIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/candidate/assessment/";
    private static final String GOOD = "// REFERENCE\nScanner in = new Scanner(System.in);\nSystem.out.println(in.nextLong() + in.nextLong());";

    private Account admin;
    private Account sme;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        PracticalStubs.resetGrader();
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());

        String email = "sme-" + shortId() + "@test.local";
        postJsonAs(admin, "/api/auth/signup", Map.of("role", "reviewer", "name", "Dr Scorecard", "email", email,
                "password", "Reviewer123x", "reviewer_skills", List.of("java"))).andExpect(status().isOk());
        sme = tokenFor(users.findByRoleAndEmailIgnoreCase(Role.REVIEWER, email).orElseThrow());
        putJsonAs(admin, "/api/jobs/" + job + "/reviewers", Map.of("user_ids", List.of(sme.user().getId().toString()),
                "review_policy", Map.of("question_set_approval_required", false, "sample_rate_not_recommended", 0,
                        "review_if_theory_at_least", 101))).andExpect(status().isOk());
        // Every gate threshold at 0 → PROCEED, which is always routed to the SME.
        Map<String, Object> scoring = new HashMap<>(Map.of("theory_weight", 0.3, "hands_on_weight", 0.7,
                "max_tab_switches", 5, "theory_practice_gap", 100));
        scoring.put("proceed_hands_on_min", 0);
        scoring.put("proceed_theory_min", 0);
        scoring.put("review_hands_on_min", 0);
        putJsonAs(admin, "/api/assessment/scoring_settings/" + job, scoring).andExpect(status().isOk());
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1"))).andExpect(status().isOk());
    }

    private String takeAndSubmit(String email) throws Exception {
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "candidate_email", email,
                "candidate_name", "Casey Candidate", "levels", List.of("L1"))).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        String token = url.substring(url.indexOf("token=") + 6);
        JsonNode view = bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
        String code = null;
        for (JsonNode x : view.path("questions")) if (x.path("type").asText().equals("CODE_WRITE")) code = x.path("question_id").asText();
        mvc.perform(put(BASE + token + "/answers/" + code).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("language", "java", "code", GOOD)))).andExpect(status().isOk());
        postJson(BASE + token + "/submit", Map.of()).andExpect(status().isOk());
        return jdbc.queryForObject("select id::text from assessment_attempts where job_id = ?::uuid order by created_at desc limit 1",
                String.class, job);
    }

    private JsonNode dashboard() throws Exception {
        return bodyOf(getAs(admin, "/api/dashboard?job_id=" + job).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("dashboard funnel, validated scorecard PDF (never the reference solution), activity feed and xlsx report")
    void endToEnd() throws Exception {
        // One invite never opened, one taken and submitted.
        postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job, "candidate_email", "idle@example.com",
                "levels", List.of("L1"))).andExpect(status().isOk());
        String attempt = takeAndSubmit("casey@example.com");
        assertThat(jdbc.queryForObject("select status from assessment_attempts where id = ?::uuid", String.class, attempt))
                .isEqualTo("AWAITING_REVIEW");

        JsonNode d = dashboard();
        assertThat(d.path("assessments_sent").asLong()).isEqualTo(2);
        assertThat(d.path("assessments_started").asLong()).isEqualTo(1);
        assertThat(d.path("assessments_completed").asLong()).isEqualTo(1);
        assertThat(d.path("awaiting_review").asLong()).isEqualTo(1);
        assertThat(d.path("passed").asLong()).isZero();
        double handsOn = jdbc.queryForObject("select hands_on_score from assessment_attempts where id = ?::uuid", Double.class, attempt);
        assertThat(d.path("average_hands_on_by_job").get(0).path("average_hands_on").asDouble()).isEqualTo(handsOn);
        assertThat(d.path("average_hands_on_by_job").get(0).path("job_title").asText()).isEqualTo("Java Backend Developer");

        // Provisional PDF before sign-off.
        assertThat(pdfText("/api/scorecard/" + job + "/" + attempt)).contains("PROVISIONAL");

        // SME signs off → validated PROCEED.
        String review = jdbc.queryForObject("select id::text from sme_reviews where subject_id = ?::uuid", String.class, attempt);
        postJsonAs(sme, "/api/review/" + review + "/claim", Map.of()).andExpect(status().isOk());
        postJsonAs(sme, "/api/review/attempts/" + review, Map.of("action", "SIGN_OFF", "comment", "Clean, idiomatic solution"))
                .andExpect(status().isOk());
        d = dashboard();
        assertThat(d.path("passed").asLong()).isEqualTo(1);
        assertThat(d.path("awaiting_review").asLong()).isZero();

        // PDF: by attempt id, and by the legacy index alias (0 = newest).
        String text = pdfText("/api/scorecard/" + job + "/" + attempt);
        assertThat(pdfText("/api/scorecard/" + job + "/0")).as("index alias renders the same attempt").isEqualTo(text);
        assertThat(text).contains("Casey Candidate", "casey@example.com", "Java Backend Developer", "JD number", "L1",
                "Theory", "Hands-on", "PROCEED", "VALIDATED", "Dr Scorecard", "Clean, idiomatic solution",
                "Tab switches: 0", "Evidence");
        List<String> refs = jdbc.queryForList("select reference_solution::text from assessment_questions q "
                + "join assessments a on a.id = q.assessment_id where a.job_id = ?::uuid and q.reference_solution::text <> '{}'",
                String.class, job);
        assertThat(refs).isNotEmpty();
        for (String ref : refs) {
            json.readTree(ref).fields().forEachRemaining(e -> e.getValue().asText().lines()
                    .map(String::strip).filter(l -> l.length() > 12)
                    .forEach(line -> assertThat(text).doesNotContain(line)));
        }
        getAs(admin, "/api/scorecard/" + job + "/7").andExpect(status().isNotFound());

        // Activity feed: written by the services.
        JsonNode feed = bodyOf(getAs(admin, "/api/activity/" + job + "?limit=50").andExpect(status().isOk()));
        List<String> kinds = feed.findValuesAsText("kind");
        assertThat(kinds).contains("assessment", "invite", "submission", "evaluation", "validation");
        assertThat(feed.toString()).contains("Assessment v1 generated", "Casey Candidate submitted L1",
                "result validated by Dr Scorecard: PROCEED");

        // xlsx report via the signed-download flow.
        JsonNode signed = bodyOf(postJsonAs(admin, "/api/downloads/sign", Map.of("path", "/api/download_report")).andExpect(status().isOk()));
        var resp = mvc.perform(get(signed.path("url").asText())).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(resp.getHeader("Content-Disposition")).contains("viztalent-report.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(resp.getContentAsByteArray()))) {
            assertThat(headers(wb.getSheet("Candidates"))).contains("Fit Score", "Theory", "Hands-on", "Recommendation");
            Sheet a = wb.getSheet("Assessments");
            assertThat(headers(a)).startsWith("JD Number", "Role", "Candidate", "Email", "Level")
                    .contains("Theory", "Theory Band", "Hands-on", "Hands-on Band", "Overall", "Result Status");
            boolean found = false;
            for (Row r : a) {
                if (r.getRowNum() > 0 && r.getCell(3) != null && "casey@example.com".equals(r.getCell(3).getStringCellValue())) {
                    found = true;
                    assertThat(r.getCell(10).getNumericCellValue()).isEqualTo(handsOn);
                    assertThat(r.getCell(13).getStringCellValue()).isEqualTo("PROCEED");
                    assertThat(r.getCell(14).getStringCellValue()).isEqualTo("VALIDATED");
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    @DisplayName("dashboard is scoped: an employee sees only their own jobs and can't ask for another's")
    void dashboardScoped() throws Exception {
        Account other = newEmployee();
        JsonNode mine = bodyOf(getAs(other, "/api/dashboard").andExpect(status().isOk()));
        assertThat(mine.path("assessments_sent").asLong()).isZero();
        getAs(other, "/api/dashboard?job_id=" + job).andExpect(status().isForbidden());
        getAs(sme, "/api/dashboard").andExpect(status().isForbidden());
    }

    private String pdfText(String url) throws Exception {
        var resp = getAs(admin, url).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(resp.getContentType()).isEqualTo("application/pdf");
        return new Tika().parseToString(new ByteArrayInputStream(resp.getContentAsByteArray()))
                .replaceAll("Generated by SmartStaff · [^·]+", "");
    }

    private static List<String> headers(Sheet s) {
        List<String> out = new ArrayList<>();
        s.getRow(0).forEach(c -> out.add(c.getStringCellValue()));
        return out;
    }
}

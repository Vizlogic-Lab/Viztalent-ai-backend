package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JD upload, resume upload and the deterministic (no-LLM) screening — the
 *  regressions here are real bugs found while building it (skill classification
 *  bleeding across sentences, phone numbers swallowing the next line, candidate
 *  names taken from filenames instead of the resume). */
class ScreeningIntegrationTest extends IntegrationTestBase {

    private static final String JD = """
            Senior Java Developer

            We are hiring a Senior Java Developer with 5+ years of experience.
            Required: Java, Spring Boot, PostgreSQL.
            Docker is a plus.
            """;

    // Strong match: every required skill, the nice-to-have, more than the minimum experience.
    private static final String PRIYA = """
            Priya Nair
            priya.nair@example.com
            +91 98765 43210
            Senior backend engineer with 7 years of experience in Java, Spring Boot and PostgreSQL. Also uses Docker.
            """;

    // Partial match. The phone number is immediately followed by a line starting with a digit —
    // the shape that once made the phone regex swallow a stray digit from the next line.
    private static final String RAVI = "Ravi Kumar\nravi.kumar@example.com\n9876543210\n6 years of experience as a backend developer. Skills: Java and Spring Boot.\n";

    // No required skills, and under the experience minimum.
    private static final String ASHA = """
            Asha Verma
            asha.verma@example.com
            Recent graduate with 1 year of experience in Python and React.
            """;

    private String uploadJd(Account who, String filename, String text) throws Exception {
        return bodyOf(uploadFile(who, "/api/upload_jd", "file", filename, text)).path("job_id").asText();
    }

    private JsonNode screen(Account who, String jobId) throws Exception {
        return bodyOf(postJsonAs(who, "/api/run_screening", Map.of("session_id", jobId)).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("a JD upload names the job from its filename and extracts skills and the experience requirement")
    void jdUploadExtractsStructure() throws Exception {
        Account admin = newAdmin();

        var response = bodyOf(uploadFile(admin, "/api/upload_jd", "file", "senior_java_developer.txt", JD).andExpect(status().isOk()));

        assertThat(response.path("status").asText()).isEqualTo("success");
        assertThat(response.path("jd_title").asText()).isEqualTo("Senior Java Developer");
        assertThat(response.path("jd_number_display").asText()).matches("JD-\\d{4,}");

        var detail = bodyOf(getAs(admin, "/api/jobs/" + response.path("job_id").asText()).andExpect(status().isOk()));
        List<String> critical = new ArrayList<>();
        detail.path("jd_struct").path("critical_skills").forEach(n -> critical.add(n.asText()));
        List<String> important = new ArrayList<>();
        detail.path("jd_struct").path("important_skills").forEach(n -> important.add(n.asText()));
        assertThat(critical).contains("java", "spring boot", "postgresql");
        assertThat(important).contains("docker").doesNotContain("java");
        assertThat(detail.path("jd_struct").path("experience_min_years").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("uploading the same JD again is reported as a duplicate; force=1 creates a new job")
    void duplicateJd() throws Exception {
        Account owner = newEmployee();
        String first = uploadJd(owner, "backend_role.txt", JD);

        var duplicate = bodyOf(uploadFile(owner, "/api/upload_jd", "file", "backend_role.txt", JD).andExpect(status().isOk()));
        assertThat(duplicate.path("status").asText()).isEqualTo("duplicate");
        assertThat(duplicate.path("existing_job_id").asText()).isEqualTo(first);

        var forced = bodyOf(mvc.perform(withAuth(MockMvcRequestBuilders.multipart("/api/upload_jd")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "backend_role.txt", "text/plain", JD.getBytes()))
                        .param("force", "1"), owner))
                .andExpect(status().isOk()));
        assertThat(forced.path("status").asText()).isEqualTo("success");
        assertThat(forced.path("job_id").asText()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("screening ranks candidates by fit and extracts name, email, phone and years from each resume")
    void screeningRanksAndExtracts() throws Exception {
        Account owner = newEmployee();
        String job = uploadJd(owner, "senior_java_developer.txt", JD);
        // Filenames deliberately unrelated to the people, so names must come from the resume text.
        uploadResumes(owner, job, "cv_final_v2.txt", PRIYA, "scan_0042.txt", RAVI, "document.txt", ASHA).andExpect(status().isOk());

        JsonNode result = screen(owner, job);
        JsonNode rows = result.path("table_data");

        assertThat(rows).hasSize(3);
        // Highest score first.
        List<String> names = new ArrayList<>();
        rows.forEach(r -> names.add(r.path("Candidate_Name").asText()));
        assertThat(names).containsExactly("Priya Nair", "Ravi Kumar", "Asha Verma");

        JsonNode priya = rows.get(0);
        JsonNode ravi = rows.get(1);
        JsonNode asha = rows.get(2);

        assertThat(priya.path("Email").asText()).isEqualTo("priya.nair@example.com");
        assertThat(priya.path("Phone").asText()).isEqualTo("+91 98765 43210");
        assertThat(priya.path("Years_Experience").asInt()).isEqualTo(7);
        // The skill dictionary lists both "spring" and "spring boot", so a JD that says
        // "Spring Boot" has four required skills here (java, spring, spring boot, postgresql).
        assertThat(priya.path("Matched_Count").asInt()).isEqualTo(4);
        assertThat(priya.path("Total_Required").asInt()).isEqualTo(4);
        assertThat(priya.path("Key_Strengths").asText()).contains("java", "spring boot", "postgresql");
        assertThat(priya.path("Job_Id").asText()).isEqualTo(job);

        // Regression: the phone number must not swallow the digit that starts the next line ("6 years...").
        assertThat(ravi.path("Phone").asText()).isEqualTo("9876543210");
        assertThat(ravi.path("Matched_Count").asInt()).isEqualTo(3);
        assertThat(ravi.path("Missing_Skills").asText()).contains("postgresql");

        assertThat(priya.path("Fit_Score_Out_Of_100").asInt())
                .isGreaterThan(ravi.path("Fit_Score_Out_Of_100").asInt());
        assertThat(ravi.path("Fit_Score_Out_Of_100").asInt())
                .isGreaterThan(asha.path("Fit_Score_Out_Of_100").asInt());
        assertThat(priya.path("Fit_Score_Out_Of_100").asInt()).isGreaterThanOrEqualTo(85);
        assertThat(asha.path("Fit_Score_Out_Of_100").asInt()).isLessThan(50);
        assertThat(asha.path("Matched_Count").asInt()).isZero();

        assertThat(result.path("reply").asText()).contains("Screened 3 candidates").contains("2 scored 50 or above");
    }

    @Test
    @DisplayName("regression: a qualifier in a later sentence doesn't demote an earlier required skill")
    void requiredSkillsNotDemotedByLaterQualifier() throws Exception {
        Account owner = newEmployee();
        String jd = "Platform Engineer\nRequired: Docker, Linux. Kubernetes is a plus.\n";

        var detail = bodyOf(getAs(owner, "/api/jobs/" + uploadJd(owner, "platform.txt", jd)).andExpect(status().isOk()));

        List<String> critical = new ArrayList<>();
        detail.path("jd_struct").path("critical_skills").forEach(n -> critical.add(n.asText()));
        List<String> important = new ArrayList<>();
        detail.path("jd_struct").path("important_skills").forEach(n -> important.add(n.asText()));
        assertThat(critical).contains("docker", "linux").doesNotContain("kubernetes");
        assertThat(important).contains("kubernetes").doesNotContain("linux", "docker");
    }

    @Test
    @DisplayName("re-screening recomputes from scratch: new resumes appear and rows aren't duplicated")
    void rescreeningReplacesRows() throws Exception {
        Account owner = newEmployee();
        String job = uploadJd(owner, "senior_java_developer.txt", JD);
        uploadResumes(owner, job, "a.txt", PRIYA).andExpect(status().isOk());
        assertThat(screen(owner, job).path("table_data")).hasSize(1);

        uploadResumes(owner, job, "b.txt", RAVI).andExpect(status().isOk());
        assertThat(screen(owner, job).path("table_data")).hasSize(2);
        assertThat(screen(owner, job).path("table_data")).hasSize(2);

        Integer stored = jdbc.queryForObject("select count(*) from candidates where job_id = ?::uuid", Integer.class, job);
        assertThat(stored).isEqualTo(2);
    }

    @Test
    @DisplayName("screening a job with no resumes says so instead of failing")
    void noResumes() throws Exception {
        Account owner = newEmployee();
        String job = uploadJd(owner, "senior_java_developer.txt", JD);

        postJsonAs(owner, "/api/run_screening", Map.of("session_id", job))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("no resumes")))
                .andExpect(jsonPath("$.table_data.length()").value(0));
    }

    @Test
    @DisplayName("a bad session id gets the friendly 'upload a JD first' error")
    void badSession() throws Exception {
        postJsonAs(newEmployee(), "/api/run_screening", Map.of("session_id", "local_react_user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("upload a JD first")));
    }

    @Test
    @DisplayName("screened candidates show up in the job detail, and in the CSV report (which is a plain, unauthenticated download link)")
    void detailAndReport() throws Exception {
        Account owner = newEmployee();
        String job = uploadJd(owner, "senior_java_developer.txt", JD);
        uploadResumes(owner, job, "a.txt", PRIYA).andExpect(status().isOk());
        screen(owner, job);

        getAs(owner, "/api/jobs/" + job)
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].Candidate_Name").value("Priya Nair"));

        // The frontend opens this as a plain <a href>, so no bearer token is ever sent.
        mvc.perform(MockMvcRequestBuilders.get("/api/download_report"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")));
    }

    @Test
    @DisplayName("a skills-only 'JD' (no file) still creates a screenable job")
    void skillsOnlyJob() throws Exception {
        Account owner = newEmployee();
        String job = createJob(owner, "Skills only role", "java, docker, kubernetes");
        uploadResumes(owner, job, "a.txt", PRIYA).andExpect(status().isOk());

        JsonNode rows = screen(owner, job).path("table_data");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("Total_Required").asInt()).isPositive();
    }
}

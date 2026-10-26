package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HR's full report PDF: candidate name and resume first, then each question with the
 *  answer the candidate gave and the correct one. HR-only. */
class FullReportIntegrationTest extends IntegrationTestBase {

    private static final String RESUME = "Meera Iyer\nmeera.iyer@example.com\n6 years of experience in Java and Spring Boot.\n";

    private Account admin;
    private String job;
    private String attempt;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring boot");
        seedReadyAssessment(job, "L1");
        uploadResumes(admin, job, "meera.txt", RESUME).andExpect(status().isOk());
        postJsonAs(admin, "/api/run_screening", Map.of("session_id", job)).andExpect(status().isOk());

        // No Gemini key, so the candidate gets the job's shared assessment.
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint", Map.of("session_id", job,
                "candidate_email", "meera.iyer@example.com", "candidate_name", "Meera Iyer", "levels", List.of("L1")))
                .andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        String token = url.substring(url.indexOf("token=") + 6);

        JsonNode view = bodyOf(mvc.perform(get("/api/candidate/assessment/" + token)).andExpect(status().isOk()));
        String qid = view.path("questions").get(0).path("question_id").asText();
        mvc.perform(put("/api/candidate/assessment/" + token + "/answers/" + qid)
                .contentType("application/json").content("{\"selected_indices\":[1]}")).andExpect(status().isOk());
        postJson("/api/candidate/assessment/" + token + "/submit", Map.of()).andExpect(status().isOk());
        attempt = jdbc.queryForObject("select id::text from assessment_attempts where job_id = ?::uuid", String.class, job);
    }

    @Test
    @DisplayName("the report has the candidate, their resume, and each question with their answer and the correct one")
    void reportContents() throws Exception {
        var res = getAs(admin, "/api/fullreport/" + job + "/" + attempt).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getContentType()).isEqualTo("application/pdf");
        assertThat(res.getHeader("Content-Disposition")).contains("Report_Meera_Iyer_L1.pdf");

        PdfReader reader = new PdfReader(res.getContentAsByteArray());
        StringBuilder text = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) text.append(extractor.getTextFromPage(p)).append('\n');
        String pdf = text.toString();

        assertThat(pdf).contains("Meera Iyer", "meera.iyer@example.com", "Resume", "6 years of experience in Java");
        assertThat(pdf).contains("Seeded?");                 // the question
        assertThat(pdf).contains("Correct answer", "Chosen (wrong)");   // option a is correct, the candidate picked b
        assertThat(pdf).contains("scored 0 / 5");
    }

    @Test
    @DisplayName("only HR with access to the job can download it; the attempt must belong to that job")
    void access() throws Exception {
        getAs(newEmployee(), "/api/fullreport/" + job + "/" + attempt).andExpect(status().isForbidden());
        String otherJob = createJob(admin, "Other role", "python");
        getAs(admin, "/api/fullreport/" + otherJob + "/" + attempt).andExpect(status().isNotFound());
        // It can be signed for a download link like the scorecard.
        bodyOf(postJsonAs(admin, "/api/downloads/sign", Map.of("path", "/api/fullreport/" + job + "/" + attempt))
                .andExpect(status().isOk()));
    }
}

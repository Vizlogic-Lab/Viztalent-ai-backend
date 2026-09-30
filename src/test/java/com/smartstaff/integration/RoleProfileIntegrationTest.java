package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.service.RoleProfileService;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F1 — role profile extraction (Gemini + fallback), HR edits and access. */
class RoleProfileIntegrationTest extends IntegrationTestBase {

    private static final String SALES_JD = """
            Area Sales Manager
            We need someone who runs distributor operations on our DMS and drives field teams through the SFA app.
            Strong Excel reporting. 4+ years of experience in FMCG sales.
            """;

    private static final String PROFILE_JSON = """
            {"role_family":"NON_TECHNICAL","is_technical":false,"languages":[],"frameworks":[],"seniority":"MID",
             "skill_weights":[{"skill":"DMS","weight":5},{"skill":"SFA","weight":5},{"skill":"Excel","weight":3},
                              {"skill":"Kubernetes","weight":4}]}
            """;

    @Autowired RoleProfileService roleProfileService;

    private Account owner;
    private Account admin;

    @BeforeEach
    void setUp() {
        owner = newEmployee();
        admin = newAdmin();
    }

    private String uploadJd(Account who, String name, String text) throws Exception {
        return bodyOf(uploadFile(who, "/api/upload_jd", "file", name, text).andExpect(status().isOk())).path("job_id").asText();
    }

    private JsonNode profile(Account who, String job) throws Exception {
        return bodyOf(getAs(who, "/api/jobs/" + job + "/role_profile").andExpect(status().isOk()));
    }

    @Test
    @DisplayName("after a JD upload Gemini builds the profile; domain skills from the JD text are kept and invented ones dropped")
    void aiSuccess() throws Exception {
        setGeminiKey(admin, "AIza-test-key-123");
        STUB.on("POST", "/v1beta/models/", req -> StubResponse.json(200, Fixtures.geminiText(PROFILE_JSON)));

        String job = uploadJd(owner, "area_sales_manager.txt", SALES_JD);

        JsonNode p = profile(owner, job);
        assertThat(p.path("source").asText()).isEqualTo("AI");
        assertThat(p.path("role_family").asText()).isEqualTo("NON_TECHNICAL");
        assertThat(p.path("is_technical").asBoolean()).isFalse();
        assertThat(p.path("seniority").asText()).isEqualTo("MID");
        assertThat(p.path("skill_weights").path("dms").asInt()).isEqualTo(5);
        assertThat(p.path("skill_weights").path("sfa").asInt()).isEqualTo(5);
        assertThat(p.path("skill_weights").path("excel").asInt()).isEqualTo(3);
        assertThat(p.path("skill_weights").has("kubernetes")).as("not in the JD, so dropped").isFalse();

        var calls = STUB.requests("/v1beta/models/");
        assertThat(calls).hasSize(1);
        JsonNode sent = json.readTree(calls.get(0).body());
        assertThat(sent.path("generationConfig").path("responseSchema").path("properties").has("role_family")).isTrue();
        assertThat(sent.path("generationConfig").path("temperature").asInt()).isZero();
        assertThat(sent.path("contents").path(0).path("parts").path(0).path("text").asText()).contains("distributor operations on our DMS");

        getAs(owner, "/api/jobs/" + job)
                .andExpect(jsonPath("$.role_profile.role_family").value("NON_TECHNICAL"))
                .andExpect(jsonPath("$.role_profile.skill_weights.dms").value(5));
    }

    @Test
    @DisplayName("when Gemini fails or returns junk the rule-based fallback is stored with source AI_FALLBACK")
    void aiFailureFallsBack() throws Exception {
        setGeminiKey(admin, "AIza-test-key-123");
        STUB.on("POST", "/v1beta/models/", req -> StubResponse.json(200, Fixtures.geminiText("{\"role_family\":\"WIZARD\"}")));

        String job = createJob(owner, "Java Backend Developer", "java, spring boot, docker");

        JsonNode p = profile(owner, job);
        assertThat(p.path("source").asText()).isEqualTo("AI_FALLBACK");
        assertThat(p.path("role_family").asText()).isEqualTo("BACKEND");
        assertThat(p.path("is_technical").asBoolean()).isTrue();
        assertThat(p.path("languages")).extracting(JsonNode::asText).containsExactly("java");
        assertThat(p.path("skill_weights").path("java").asInt()).isEqualTo(4);
        assertThat(STUB.requests("/v1beta/models/")).hasSize(1);
    }

    @Test
    @DisplayName("without a Gemini key the fallback runs and Gemini is never called")
    void noKeyUsesRules() throws Exception {
        String job = createJob(owner, "Frontend Developer", "react, javascript, css");

        JsonNode p = profile(owner, job);
        assertThat(p.path("source").asText()).isEqualTo("AI_FALLBACK");
        assertThat(p.path("role_family").asText()).isEqualTo("FRONTEND");
        assertThat(STUB.requests("/v1beta/")).isEmpty();
    }

    @Test
    @DisplayName("the owner can correct the profile; it becomes source HR and is not overwritten by re-extraction")
    void hrEdit() throws Exception {
        String job = createJob(owner, "Backend Developer", "java, spring");

        JsonNode edited = bodyOf(putJsonAs(owner, "/api/jobs/" + job + "/role_profile", Map.of(
                "role_family", "fullstack",
                "languages", List.of("Java", "TypeScript"),
                "skill_weights", Map.of("Java", 5, "React", 3)))
                .andExpect(status().isOk()));
        assertThat(edited.path("source").asText()).isEqualTo("HR");
        assertThat(edited.path("role_family").asText()).isEqualTo("FULLSTACK");
        assertThat(edited.path("is_technical").asBoolean()).isTrue();
        assertThat(edited.path("languages")).extracting(JsonNode::asText).containsExactly("java", "typescript");
        assertThat(edited.path("skill_weights").path("react").asInt()).isEqualTo(3);
        assertThat(edited.path("edited_by").asText()).isEqualTo(owner.user().getId().toString());

        roleProfileService.extractRoleProfile(UUID.fromString(job));
        assertThat(profile(owner, job).path("source").asText()).isEqualTo("HR");
    }

    @Test
    @DisplayName("invalid edits are rejected with 400")
    void hrEditValidation() throws Exception {
        String job = createJob(owner, "Backend Developer", "java");
        putJsonAs(owner, "/api/jobs/" + job + "/role_profile", Map.of("role_family", "WIZARD"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_role_family"));
        putJsonAs(owner, "/api/jobs/" + job + "/role_profile", Map.of("seniority", "GURU"))
                .andExpect(status().isBadRequest());
        putJsonAs(owner, "/api/jobs/" + job + "/role_profile", Map.of("skill_weights", Map.of("java", 6)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_skill_weights"));
    }

    @Test
    @DisplayName("an employee cannot read or edit another owner's profile or skills")
    void otherEmployeeForbidden() throws Exception {
        String job = createJob(owner, "Backend Developer", "java");
        Account stranger = newEmployee();

        getAs(stranger, "/api/jobs/" + job + "/role_profile").andExpect(status().isForbidden());
        putJsonAs(stranger, "/api/jobs/" + job + "/role_profile", Map.of("role_family", "DATA")).andExpect(status().isForbidden());
        putJsonAs(stranger, "/api/jobs/" + job + "/skills", Map.of("must_have_skills", List.of("x"))).andExpect(status().isForbidden());
        putJsonAs(admin, "/api/jobs/" + job + "/role_profile", Map.of("role_family", "DATA")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT skills updates skills and experience without re-screening")
    void editSkills() throws Exception {
        String job = createJob(owner, "Backend Developer", "java");

        putJsonAs(owner, "/api/jobs/" + job + "/skills", Map.of(
                "must_have_skills", List.of(" Java ", "Spring", "java"),
                "nice_to_have_skills", List.of("Docker"),
                "experience_min_years", 3, "experience_max_years", 6))
                .andExpect(status().isOk());

        getAs(owner, "/api/jobs/" + job)
                .andExpect(jsonPath("$.jd_struct.critical_skills.length()").value(2))
                .andExpect(jsonPath("$.jd_struct.critical_skills[0]").value("java"))
                .andExpect(jsonPath("$.jd_struct.important_skills[0]").value("docker"))
                .andExpect(jsonPath("$.candidates.length()").value(0));

        putJsonAs(owner, "/api/jobs/" + job + "/skills", Map.of("experience_min_years", 9))
                .andExpect(status().isBadRequest());
    }
}

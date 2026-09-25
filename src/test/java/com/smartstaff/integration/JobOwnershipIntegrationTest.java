package com.smartstaff.integration;

import com.smartstaff.entity.Interview;
import com.smartstaff.repository.InterviewRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-job authorization: an employee must not be able to read or act on a job
 *  another employee (or an admin) owns, on ANY job-scoped endpoint — not just
 *  the ones that happen to list or delete. */
class JobOwnershipIntegrationTest extends IntegrationTestBase {

    @Autowired JobRepository jobRepository;
    @Autowired InterviewRepository interviewRepository;

    /** One entry per job-scoped endpoint: a label plus how to call it for a given job and interview. */
    record Endpoint(String label, HttpMethod method, String path, Object body) {
        @Override public String toString() { return label; }
    }

    static Stream<Endpoint> jobScopedEndpoints() {
        // Placeholders {job} / {interview} are substituted per test; bodies likewise (see fill()).
        return Stream.of(
                new Endpoint("GET job detail", HttpMethod.GET, "/api/jobs/{job}", null),
                new Endpoint("GET resume list", HttpMethod.GET, "/api/jd/{job}/resumes", null),
                new Endpoint("GET activity", HttpMethod.GET, "/api/activity/{job}", null),
                new Endpoint("GET assessment status", HttpMethod.GET, "/api/assessment/status/{job}", null),
                new Endpoint("GET assessment submissions", HttpMethod.GET, "/api/assessment/submissions/{job}", null),
                new Endpoint("GET answer key", HttpMethod.GET, "/api/assessment/answer_key/{job}", null),
                new Endpoint("POST generate assessment", HttpMethod.POST, "/api/assessment/generate", Map.of("session_id", "{job}", "question_source", "custom")),
                new Endpoint("POST run screening", HttpMethod.POST, "/api/run_screening", Map.of("session_id", "{job}")),
                new Endpoint("POST assessment invite", HttpMethod.POST, "/api/invites/mint", Map.of("session_id", "{job}", "candidate_email", "x@y.test", "levels", List.of("L1"))),
                new Endpoint("POST recruiter chat", HttpMethod.POST, "/api/universal_execute", Map.of("command", "screen them", "session_id", "{job}")),
                new Endpoint("POST interview prepare", HttpMethod.POST, "/api/interview/prepare", Map.of("session_id", "{job}")),
                new Endpoint("POST interview invite", HttpMethod.POST, "/api/interview/invites/mint", Map.of("session_id", "{job}", "candidate_email", "x@y.test")),
                new Endpoint("POST interview save", HttpMethod.POST, "/api/interview/save",
                        Map.of("session_id", "{job}", "interview_id", "{interview}", "transcript", List.of(Map.of("question", "q", "answer", "a")))),
                new Endpoint("GET interview transcripts", HttpMethod.GET, "/api/interview/transcripts/{job}", null),
                new Endpoint("POST place call", HttpMethod.POST, "/api/interview/place_call",
                        Map.of("session_id", "{job}", "interview_id", "{interview}", "phone", "+911234567890")),
                new Endpoint("GET call status", HttpMethod.GET, "/api/interview/call_status/{interview}", null)
        );
    }

    private static String fill(String s, String job, String interview) {
        return s.replace("{job}", job).replace("{interview}", interview);
    }

    private Object fillBody(Object body, String job, String interview) throws Exception {
        if (body == null) return null;
        return json.readValue(fill(json.writeValueAsString(body), job, interview), Object.class);
    }

    private org.springframework.test.web.servlet.ResultActions call(Endpoint e, Account who, String job, String interview) throws Exception {
        String path = fill(e.path(), job, interview);
        if (e.method() == HttpMethod.GET) return getAs(who, path);
        return postJsonAs(who, path, fillBody(e.body(), job, interview));
    }

    private String interviewFor(String jobId) {
        Interview interview = new Interview();
        interview.setJob(jobRepository.findById(UUID.fromString(jobId)).orElseThrow());
        interview.setMode("BROWSER");
        interview.setCandidateName("Someone");
        return interviewRepository.save(interview).getId().toString();
    }

    @ParameterizedTest(name = "{0}: another employee is refused")
    @MethodSource("jobScopedEndpoints")
    void otherEmployeeIsRefused(Endpoint endpoint) throws Exception {
        Account owner = newEmployee();
        Account intruder = newEmployee();
        String job = createJob(owner, "Owned job", "java, spring boot");
        String interview = interviewFor(job);

        call(endpoint, intruder, job, interview).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0}: the owner is not refused")
    @MethodSource("jobScopedEndpoints")
    void ownerIsNotRefused(Endpoint endpoint) throws Exception {
        Account owner = newEmployee();
        String job = createJob(owner, "Owned job", "java, spring boot");
        String interview = interviewFor(job);

        int status = call(endpoint, owner, job, interview).andReturn().getResponse().getStatus();

        assertThat(status).as("owner must never see 401/403 on their own job").isNotIn(401, 403);
    }

    @ParameterizedTest(name = "{0}: an admin is not refused on an employee's job")
    @MethodSource("jobScopedEndpoints")
    void adminIsNotRefused(Endpoint endpoint) throws Exception {
        Account owner = newEmployee();
        Account admin = newAdmin();
        String job = createJob(owner, "Owned job", "java, spring boot");
        String interview = interviewFor(job);

        int status = call(endpoint, admin, job, interview).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @DisplayName("uploading resumes into someone else's job is refused, and nothing is stored")
    void uploadResumesIntoForeignJobRefused() throws Exception {
        Account owner = newEmployee();
        Account intruder = newEmployee();
        String job = createJob(owner, "Owned job", "java");

        uploadResumes(intruder, job, "planted.txt", "Malicious Person\nplanted@evil.test").andExpect(status().isForbidden());

        Integer stored = jdbc.queryForObject("select count(*) from resumes where job_id = ?::uuid", Integer.class, job);
        assertThat(stored).isZero();
    }

    @Test
    @DisplayName("the owner can upload resumes into their own job")
    void ownerCanUploadResumes() throws Exception {
        Account owner = newEmployee();
        String job = createJob(owner, "Owned job", "java");

        uploadResumes(owner, job, "a.txt", "Alice Example\nalice@example.test\nJava developer").andExpect(status().isOk());
    }

    @Test
    @DisplayName("an employee cannot delete someone else's job; the owner and an admin can")
    void deleteRules() throws Exception {
        Account owner = newEmployee();
        Account intruder = newEmployee();
        Account admin = newAdmin();
        String mine = createJob(owner, "Mine", "java");
        String adminDeletes = createJob(owner, "Admin will delete", "python");

        mvc.perform(withAuth(MockMvcRequestBuilders.delete("/api/jobs/" + mine), intruder)).andExpect(status().isForbidden());
        mvc.perform(withAuth(MockMvcRequestBuilders.delete("/api/jobs/" + mine), owner)).andExpect(status().isOk());
        mvc.perform(withAuth(MockMvcRequestBuilders.delete("/api/jobs/" + adminDeletes), admin)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the job list is scoped: employees see only their own jobs, admins see everyone's")
    void listIsScoped() throws Exception {
        Account alice = newEmployee();
        Account bob = newEmployee();
        Account admin = newAdmin();
        String aliceJob = createJob(alice, "Alice job", "java");
        String bobJob = createJob(bob, "Bob job", "python");

        getAs(alice, "/api/jobs").andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[*].job_id", hasItem(aliceJob)))
                .andExpect(jsonPath("$.jobs[*].job_id", not(hasItem(bobJob))));
        getAs(admin, "/api/jobs").andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[*].job_id", hasItem(aliceJob)))
                .andExpect(jsonPath("$.jobs[*].job_id", hasItem(bobJob)));
    }

    @Test
    @DisplayName("ids that don't exist, and the frontend's legacy non-UUID session id, are not turned into 403s")
    void nonexistentIdsAreNotForbidden() throws Exception {
        Account emp = newEmployee();
        String missing = UUID.randomUUID().toString();

        int detail = getAs(emp, "/api/jobs/" + missing).andReturn().getResponse().getStatus();
        int status = getAs(emp, "/api/assessment/status/" + missing).andReturn().getResponse().getStatus();
        int chat = postJsonAs(emp, "/api/universal_execute", Map.of("command", "hi", "session_id", "local_react_user"))
                .andReturn().getResponse().getStatus();

        assertThat(detail).isNotIn(401, 403);
        assertThat(status).isNotIn(401, 403);
        assertThat(chat).isNotIn(401, 403);
    }
}

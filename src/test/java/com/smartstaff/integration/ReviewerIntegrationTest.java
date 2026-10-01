package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.entity.Role;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F11 — technical reviewer accounts, job assignment, suggestions and the deny-by-default access rule. */
class ReviewerIntegrationTest extends IntegrationTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private Map<String, Object> reviewerBody(String email, List<String> skills) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "reviewer");
        m.put("name", "SME " + email);
        m.put("email", email);
        m.put("password", "Reviewer123x");
        m.put("reviewer_skills", skills);
        return m;
    }

    /** Creates a reviewer through the admin-only signup and returns it as an Account. */
    private Account newReviewer(Account admin, List<String> skills) throws Exception {
        String email = "sme-" + shortId() + "@test.local";
        postJsonAs(admin, "/api/auth/signup", reviewerBody(email, skills)).andExpect(status().isOk());
        var user = users.findByRoleAndEmailIgnoreCase(Role.REVIEWER, email).orElseThrow();
        return tokenFor(user);
    }

    @Test
    @DisplayName("reviewer accounts: only an admin can create one; they log in by email; accounts lists them separately")
    void creationAndLogin() throws Exception {
        Account admin = newAdmin();
        Account employee = newEmployee();
        String email = "sme-" + shortId() + "@test.local";

        postJson("/api/auth/signup", reviewerBody(email, List.of("java"))).andExpect(status().isForbidden());
        postJsonAs(employee, "/api/auth/signup", reviewerBody(email, List.of("java"))).andExpect(status().isForbidden());
        JsonNode created = bodyOf(postJsonAs(admin, "/api/auth/signup", reviewerBody(email, List.of("java", " spring ")))
                .andExpect(status().isOk()));
        assertThat(created.has("token")).isFalse();
        assertThat(created.path("account").path("role").asText()).isEqualTo("reviewer");
        assertThat(created.path("account").path("reviewer_skills").toString()).contains("spring");
        postJsonAs(admin, "/api/auth/signup", reviewerBody(email, List.of())).andExpect(status().isBadRequest());

        JsonNode login = bodyOf(postJson("/api/auth/login",
                Map.of("role", "reviewer", "identifier", email.toUpperCase(), "password", "Reviewer123x")).andExpect(status().isOk()));
        assertThat(login.path("token").asText()).isNotBlank();
        postJson("/api/auth/login", Map.of("role", "reviewer", "identifier", email, "password", "wrong-pass-1"))
                .andExpect(status().isUnauthorized());

        JsonNode accounts = bodyOf(getAs(admin, "/api/auth/accounts").andExpect(status().isOk()));
        assertThat(accounts.path("reviewers").findValuesAsText("email")).contains(email);
        assertThat(accounts.path("admins").findValuesAsText("email")).doesNotContain(email);
    }

    @Test
    @DisplayName("assignment: owner or admin sets reviewers + policy; others 403; non-reviewers rejected")
    void assignment() throws Exception {
        Account admin = newAdmin();
        Account owner = newEmployee();
        Account stranger = newEmployee();
        Account sme = newReviewer(admin, List.of("java"));
        String job = createJob(owner, "Java Dev", "java, spring");

        Map<String, Object> body = Map.of("user_ids", List.of(sme.user().getEmail()),
                "review_policy", Map.of("question_set_approval_required", true, "sample_rate_not_recommended", 0.2,
                        "review_if_theory_at_least", 75));
        putJsonAs(stranger, "/api/jobs/" + job + "/reviewers", body).andExpect(status().isForbidden());
        putJsonAs(sme, "/api/jobs/" + job + "/reviewers", body).andExpect(status().isForbidden());
        JsonNode out = bodyOf(putJsonAs(owner, "/api/jobs/" + job + "/reviewers", body).andExpect(status().isOk()));
        assertThat(out.path("reviewers").get(0).path("user_id").asText()).isEqualTo(sme.user().getId().toString());
        assertThat(out.path("review_policy").path("sample_rate_not_recommended").asDouble()).isEqualTo(0.2);

        putJsonAs(owner, "/api/jobs/" + job + "/reviewers", Map.of("user_ids", List.of(owner.user().getId().toString())))
                .andExpect(status().isBadRequest());
        putJsonAs(owner, "/api/jobs/" + job + "/reviewers", Map.of("review_policy",
                Map.of("question_set_approval_required", false, "sample_rate_not_recommended", 2, "review_if_theory_at_least", 70)))
                .andExpect(status().isBadRequest());

        JsonNode cleared = bodyOf(putJsonAs(admin, "/api/jobs/" + job + "/reviewers", Map.of("user_ids", List.of()))
                .andExpect(status().isOk()));
        assertThat(cleared.path("reviewers")).isEmpty();
        assertThat(cleared.path("review_policy").path("question_set_approval_required").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("suggest ranks reviewers by overlap with the role profile's skill weights")
    void suggest() throws Exception {
        Account admin = newAdmin();
        String job = createJob(admin, "Java Dev", "java, spring, sql");
        Account strong = newReviewer(admin, List.of("Java", "Spring"));
        Account weak = newReviewer(admin, List.of("SQL"));
        Account none = newReviewer(admin, List.of("cobol"));

        JsonNode s = bodyOf(getAs(admin, "/api/jobs/" + job + "/reviewers/suggest").andExpect(status().isOk())).path("suggestions");
        List<String> order = new ArrayList<>();
        s.forEach(x -> order.add(x.path("user_id").asText()));
        List<String> ours = order.stream().filter(id -> id.equals(strong.user().getId().toString())
                || id.equals(weak.user().getId().toString()) || id.equals(none.user().getId().toString())).toList();
        assertThat(ours).containsExactly(strong.user().getId().toString(), weak.user().getId().toString(), none.user().getId().toString());
        for (JsonNode x : s) {
            if (x.path("user_id").asText().equals(strong.user().getId().toString())) {
                assertThat(x.path("score").asInt()).isPositive();
                assertThat(x.path("matched_skills").toString()).contains("Java").contains("Spring");
            }
            if (x.path("user_id").asText().equals(none.user().getId().toString())) assertThat(x.path("score").asInt()).isZero();
        }
    }

    @Test
    @DisplayName("a reviewer gets 403 on unassigned jobs' results, and on HR-only endpoints even for assigned jobs")
    void reviewerScope() throws Exception {
        Account admin = newAdmin();
        Account sme = newReviewer(admin, List.of("java"));
        String assigned = createJob(admin, "Assigned", "java");
        String other = createJob(admin, "Other", "java");
        putJsonAs(admin, "/api/jobs/" + assigned + "/reviewers", Map.of("user_ids", List.of(sme.user().getId().toString())))
                .andExpect(status().isOk());

        getAs(sme, "/api/assessment/submissions/" + assigned).andExpect(status().isOk());
        getAs(sme, "/api/assessment/submissions/" + other).andExpect(status().isForbidden());
        getAs(sme, "/api/auth/me").andExpect(status().isOk());
        getAs(sme, "/api/review/queue").andExpect(status().isOk());
        for (String url : List.of("/api/jobs", "/api/jobs/" + assigned, "/api/jd/" + assigned + "/resumes",
                "/api/jobs/" + assigned + "/role_profile", "/api/assessment/status/" + assigned,
                "/api/assessment/submissions_all", "/api/config", "/api/auth/accounts", "/api/review/overdue")) {
            getAs(sme, url).andExpect(status().isForbidden());
        }
        postJsonAs(sme, "/api/invites/mint", Map.of("session_id", assigned, "candidate_email", "x@example.com",
                "levels", List.of("L1"))).andExpect(status().isForbidden());
        // The HR owner doesn't get the SME queue.
        getAs(newEmployee(), "/api/review/queue").andExpect(status().isForbidden());
    }

    /** Paths a reviewer may reach (public ones, or explicitly granted in SecurityConfig). */
    private static boolean granted(String method, String pattern) {
        if (pattern.startsWith("/api/review/")) return true;
        if (pattern.equals("/error")) return true;
        if (List.of("/api/auth/login", "/api/auth/signup", "/api/auth/me", "/api/auth/logout", "/api/auth/change_password")
                .contains(pattern)) return true;
        if (method.equals("GET") && (pattern.equals("/api/assessment/submissions/{jobId}")
                || pattern.equals("/api/assessment/attempts/{attemptId}"))) return true;
        for (String pub : List.of("/api/candidate/assessment/", "/api/assessment/by_token/", "/api/assessment/save_by_token/",
                "/api/assessment/submit_by_token/", "/api/assessment/run_by_token/", "/api/interview/by_token/",
                "/api/interview/save_by_token/", "/api/interview/twiml/")) {
            if (pattern.startsWith(pub)) return true;
        }
        return false;
    }

    @Test
    @DisplayName("deny by default: every controller mapping not granted to REVIEWER answers 403")
    void walkAllMappings() throws Exception {
        Account admin = newAdmin();
        Account sme = newReviewer(admin, List.of("java"));
        int checked = 0;
        List<String> leaks = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty()) methods = Set.of(RequestMethod.GET);
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod m : methods) {
                    if (granted(m.name(), pattern)) continue;
                    String url = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString()).replace("/**", "/x");
                    int code = mvc.perform(withAuth(request(HttpMethod.valueOf(m.name()), url), sme)
                            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
                    checked++;
                    if (code != 403) leaks.add(m + " " + pattern + " -> " + code);
                }
            }
        }
        assertThat(checked).isGreaterThan(40);
        assertThat(leaks).isEmpty();
    }
}

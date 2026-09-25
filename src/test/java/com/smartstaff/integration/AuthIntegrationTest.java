package com.smartstaff.integration;

import com.smartstaff.entity.AccountStatus;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("the health endpoint is public and reports UP")
    void healthIsPublic() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("an approved employee can log in with their employee id")
    void employeeLogin() throws Exception {
        Account emp = newEmployee();
        postJson("/api/auth/login", Map.of("role", "employee", "identifier", emp.user().getEmployeeId(), "password", "Passw0rd!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.token", not(emptyOrNullString())))
                .andExpect(jsonPath("$.account.id").value(emp.user().getEmployeeId()));
    }

    @Test
    @DisplayName("an admin logs in with their email")
    void adminLogin() throws Exception {
        Account admin = newAdmin();
        postJson("/api/auth/login", Map.of("role", "admin", "identifier", admin.user().getEmail(), "password", "Passw0rd!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyOrNullString())))
                .andExpect(jsonPath("$.account.role").value("admin"));
    }

    @Test
    @DisplayName("a wrong password is rejected")
    void wrongPassword() throws Exception {
        Account emp = newEmployee();
        postJson("/api/auth/login", Map.of("role", "employee", "identifier", emp.user().getEmployeeId(), "password", "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @DisplayName("an employee awaiting approval cannot log in, and is told why via a machine-readable code")
    void pendingEmployeeBlocked() throws Exception {
        Account emp = newEmployee(AccountStatus.PENDING);
        postJson("/api/auth/login", Map.of("role", "employee", "identifier", emp.user().getEmployeeId(), "password", "Passw0rd!"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("pending_approval"));
    }

    @Test
    @DisplayName("protected endpoints answer 401 with the standard JSON error when no token is sent")
    void tokenRequired() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value("Authentication required."));
    }

    @Test
    @DisplayName("error bodies carry `detail` (the FastAPI-style field some frontend call sites read) equal to `message`")
    void errorBodiesIncludeDetail() throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/jobs"))
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        org.assertj.core.api.Assertions.assertThat(node.path("detail").asText())
                .isNotBlank()
                .isEqualTo(node.path("message").asText());
    }

    @Test
    @DisplayName("un-approving an employee cuts off the token they already hold, immediately")
    void revokedEmployeeTokenStopsWorking() throws Exception {
        Account admin = newAdmin();
        Account emp = newEmployee();

        getAs(emp, "/api/jobs").andExpect(status().isOk());

        postJsonAs(admin, "/api/auth/approve", Map.of("employee_id", emp.user().getEmployeeId(), "approved", false))
                .andExpect(status().isOk());

        getAs(emp, "/api/jobs").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admin-only endpoints are closed to employees and open to admins")
    void adminOnlyEndpoints() throws Exception {
        getAs(newEmployee(), "/api/config").andExpect(status().isForbidden());
        getAs(newAdmin(), "/api/config").andExpect(status().isOk());
    }

    @Test
    @DisplayName("every response carries an X-Request-Id, and a sane caller-supplied one is echoed back")
    void requestIdHeader() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(header().exists("X-Request-Id"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health")
                        .header("X-Request-Id", "trace-abc-123"))
                .andExpect(header().string("X-Request-Id", equalTo("trace-abc-123")));
    }
}

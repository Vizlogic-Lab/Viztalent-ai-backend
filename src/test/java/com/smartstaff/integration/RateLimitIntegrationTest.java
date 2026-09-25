package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Per-client limits on the endpoints an anonymous caller can hammer. Low limits and
 *  X-Forwarded-For trust are switched on here (as they would be behind a proxy you
 *  control); each test uses its own client address so budgets don't leak between tests. */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.trust-forwarded-for=true",
        "app.rate-limit.auth-per-minute=3",
        "app.rate-limit.token-per-minute=2",
})
class RateLimitIntegrationTest extends IntegrationTestBase {

    private ResultActions login(String clientIp, String employeeId, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", clientIp)
                .header("Origin", "http://localhost:5173")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "employee", "identifier", employeeId, "password", password))));
    }

    @Test
    @DisplayName("after the allowed number of attempts a client gets 429 with a JSON message, a Retry-After, and the CORS headers a browser needs")
    void loginIsLimited() throws Exception {
        String ip = "203.0.113.10";
        for (int i = 1; i <= 3; i++) login(ip, "nobody", "wrong").andExpect(status().isUnauthorized());

        MockHttpServletResponse blocked = login(ip, "nobody", "wrong").andExpect(status().isTooManyRequests()).andReturn().getResponse();

        JsonNode body = json.readTree(blocked.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("ok").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("rate_limited");
        assertThat(body.path("message").asText()).isEqualTo("Too many requests — please wait a minute and try again.");
        assertThat(body.path("detail").asText()).isEqualTo(body.path("message").asText());
        assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(blocked.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(blocked.getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
        assertThat(blocked.getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    @DisplayName("successful logins count against the budget as well (it limits attempts, not failures)")
    void successfulLoginsCountToo() throws Exception {
        Account emp = newEmployee();
        String ip = "203.0.113.11";
        for (int i = 1; i <= 3; i++) login(ip, emp.user().getEmployeeId(), "Passw0rd!").andExpect(status().isOk());

        login(ip, emp.user().getEmployeeId(), "Passw0rd!").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("each client has its own budget")
    void clientsAreIndependent() throws Exception {
        for (int i = 1; i <= 4; i++) login("203.0.113.12", "nobody", "wrong");

        login("203.0.113.12", "nobody", "wrong").andExpect(status().isTooManyRequests());
        login("203.0.113.13", "nobody", "wrong").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sign-up shares the login budget")
    void signupSharesBudget() throws Exception {
        String ip = "203.0.113.14";
        login(ip, "nobody", "wrong");
        login(ip, "nobody", "wrong");
        mvc.perform(post("/api/auth/signup").header("X-Forwarded-For", ip).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "user", "name", "Sign Up", "employee_id", "S" + shortId(), "password", "Passw0rd!"))));

        login(ip, "nobody", "wrong").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("interview-link endpoints have their own, separate budget")
    void tokenEndpointsHaveOwnBudget() throws Exception {
        String ip = "203.0.113.15";
        mvc.perform(get("/api/interview/by_token/guess-1").header("X-Forwarded-For", ip)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/interview/by_token/guess-2").header("X-Forwarded-For", ip)).andExpect(status().isBadRequest());

        mvc.perform(get("/api/interview/by_token/guess-3").header("X-Forwarded-For", ip)).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/interview/save_by_token/guess-4").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content("{\"transcript\":[{\"question\":\"q\"}]}"))
                .andExpect(status().isTooManyRequests());
        login(ip, "nobody", "wrong").andExpect(status().isUnauthorized()); // the login budget is untouched
    }

    @Test
    @DisplayName("ordinary endpoints are never limited")
    void otherEndpointsUnlimited() throws Exception {
        for (int i = 0; i < 25; i++) {
            mvc.perform(get("/actuator/health").header("X-Forwarded-For", "203.0.113.16")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("behind a trusted proxy the client is the FIRST X-Forwarded-For entry, whatever proxies were appended")
    void firstForwardedEntryIsTheClient() throws Exception {
        login("198.51.100.7, 10.0.0.1", "nobody", "wrong");
        login("198.51.100.7, 10.0.0.2", "nobody", "wrong");
        login("198.51.100.7", "nobody", "wrong");

        login("198.51.100.7, 10.0.0.3", "nobody", "wrong").andExpect(status().isTooManyRequests());
    }
}

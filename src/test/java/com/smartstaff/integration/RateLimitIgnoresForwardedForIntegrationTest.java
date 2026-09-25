package com.smartstaff.integration;

import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default configuration (X-Forwarded-For NOT trusted): a caller must not be able to
 *  dodge the limit by inventing a new address in a header on every request. */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.trust-forwarded-for=false",
        "app.rate-limit.auth-per-minute=3",
})
class RateLimitIgnoresForwardedForIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("a forged X-Forwarded-For does not reset the budget when the header isn't trusted")
    void forgedHeaderIsIgnored() throws Exception {
        for (int i = 1; i <= 3; i++) {
            attempt("198.51.100." + i).andExpect(status().isUnauthorized());
        }

        attempt("198.51.100.99").andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions attempt(String forgedAddress) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", forgedAddress)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "employee", "identifier", "nobody", "password", "wrong"))));
    }
}

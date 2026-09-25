package com.smartstaff.integration;

import com.smartstaff.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Monitoring endpoints: health is public (load balancers, uptime checks) but reveals nothing
 *  about internals; everything else is closed. */
class ActuatorIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("health is public and reports UP without exposing component details")
    void healthIsPublicAndTerse() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$", not(hasKey("components"))))
                .andExpect(jsonPath("$", not(hasKey("details"))));
    }

    @Test
    @DisplayName("liveness and readiness probes are public and UP")
    void probes() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("nothing else under /actuator is reachable without a login")
    void restIsClosed() throws Exception {
        for (String path : new String[]{"/actuator", "/actuator/info", "/actuator/env", "/actuator/beans", "/actuator/metrics"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("even for an admin, only health and info are exposed — env, beans and metrics don't exist")
    void onlyHealthAndInfoExposed() throws Exception {
        Account admin = newAdmin();

        getAs(admin, "/actuator/info").andExpect(status().isOk());
        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/heapdump"}) {
            getAs(admin, path).andExpect(status().isNotFound());
        }
    }
}

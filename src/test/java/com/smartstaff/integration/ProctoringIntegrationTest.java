package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.PracticalStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** L3: server-authoritative proctoring warnings, event de-duplication and rate
 *  limiting, the no-clock proctoring endpoint and the admin feature flags. */
class ProctoringIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/candidate/assessment/";

    private Account admin;
    private String job;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://public.example.com");
        setGeminiKey(admin, "AIza-test-key-123");
        PracticalStubs.fakePiston(STUB);
        PracticalStubs.fakeGemini(STUB, Map.of());
        postJsonAs(admin, "/api/config/piston_url", Map.of("piston_url", STUB.baseUrl() + "/api/v2")).andExpect(status().isOk());
        postJsonAs(admin, "/api/assessment/generate",
                Map.of("session_id", job, "question_source", "ai", "levels", List.of("L1"))).andExpect(status().isOk());
        JsonNode mint = bodyOf(postJsonAs(admin, "/api/invites/mint",
                Map.of("session_id", job, "candidate_email", "p@example.com", "levels", List.of("L1"))).andExpect(status().isOk()));
        String url = mint.path("invites").get(0).path("url").asText();
        token = url.substring(url.indexOf("token=") + 6);
    }

    private int attempts() {
        return jdbc.queryForObject("select count(*) from assessment_attempts where job_id = ?::uuid", Integer.class, job);
    }

    private JsonNode send(Map<String, Object>... events) throws Exception {
        return bodyOf(postJson(BASE + token + "/events", Map.of("events", List.of(events))).andExpect(status().isOk()));
    }

    private static Map<String, Object> ev(String type, Instant at) {
        return Map.of("type", type, "payload", Map.of(), "at", at.toString());
    }

    @Test
    @DisplayName("the consent screen's proctoring view never starts the clock and follows the admin's flags")
    void proctoringViewAndFlags() throws Exception {
        JsonNode view = bodyOf(mvc.perform(get(BASE + token + "/proctoring")).andExpect(status().isOk()));
        assertThat(view.path("settings").path("eye_tracking").asBoolean()).isTrue();
        assertThat(view.path("settings").path("phone_detection").asBoolean()).isFalse();
        assertThat(attempts()).as("opening the consent screen must not start the attempt").isZero();

        putJsonAs(admin, "/api/config/proctoring", Map.of("eye_tracking", false, "phone_detection", true,
                "no_face_seconds", 4, "multiple_faces_seconds", 2, "looking_away_seconds", 5, "phone_seconds", 3,
                "mic_silent_seconds", 90)).andExpect(status().isOk());
        JsonNode after = bodyOf(mvc.perform(get(BASE + token + "/proctoring")).andExpect(status().isOk()));
        assertThat(after.path("settings").path("eye_tracking").asBoolean()).isFalse();
        assertThat(after.path("settings").path("phone_detection").asBoolean()).isTrue();
        assertThat(after.path("settings").path("phone_seconds").asInt()).isEqualTo(3);

        putJsonAs(admin, "/api/config/proctoring", Map.of("eye_tracking", true, "phone_detection", false,
                "no_face_seconds", 0, "multiple_faces_seconds", 2, "looking_away_seconds", 3, "phone_seconds", 2,
                "mic_silent_seconds", 60)).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "not-a-real-token/proctoring")).andExpect(status().isBadRequest());
        getAs(newEmployee(), "/api/config/proctoring").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the server counts warnings from stored events; one action within 4 s is one warning; the view shows the count")
    void serverCountsWarnings() throws Exception {
        bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
        Instant t = Instant.now();
        JsonNode r = send(ev("TAB_SWITCH", t), ev("FULLSCREEN_EXIT", t.plusMillis(800)), ev("FOCUS_LOST", t.plusSeconds(1)),
                ev("CAMERA_OFF", t.plusSeconds(10)), ev("MIC_MUTED", t.plusSeconds(20)), ev("NO_FACE", t.plusSeconds(30)));
        assertThat(r.path("warnings").asInt()).isEqualTo(3);   // tab+fullscreen coalesced; camera; mic. Focus/no-face aren't warnings.

        JsonNode resumed = bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
        assertThat(resumed.path("warnings").asInt()).as("a reload gets the server's count").isEqualTo(3);
    }

    @Test
    @DisplayName("the client can't forge WARNING/TERMINATED rows, and resending a batch changes nothing")
    void noForgingOrReplay() throws Exception {
        bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
        Instant t = Instant.now();
        JsonNode forged = send(ev("WARNING", t), ev("TERMINATED", t.plusSeconds(1)), ev("TAB_SWITCH", t.plusSeconds(2)));
        assertThat(forged.path("stored").asInt()).isEqualTo(1);
        assertThat(forged.path("dropped").asInt()).isEqualTo(2);

        JsonNode replay = send(ev("TAB_SWITCH", t.plusSeconds(2)));
        assertThat(replay.path("stored").asInt()).isZero();
        assertThat(replay.path("warnings").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from attempt_events where type in ('WARNING','TERMINATED')", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("a flood of events is rate-limited per attempt (60 a minute)")
    void floodLimited() throws Exception {
        bodyOf(mvc.perform(get(BASE + token)).andExpect(status().isOk()));
        Instant t = Instant.now();
        List<Map<String, Object>> many = new ArrayList<>();
        // 100 distinct events (two types, 0.6 s apart, all after the start): none are repeats.
        for (int i = 0; i < 100; i++) many.add(ev(i % 2 == 0 ? "FOCUS_LOST" : "PASTE", t.plusMillis(600L * i)));
        JsonNode r = bodyOf(postJson(BASE + token + "/events", Map.of("events", many)).andExpect(status().isOk()));
        assertThat(r.path("stored").asInt()).isEqualTo(60);
        assertThat(r.path("dropped").asInt()).isEqualTo(40);
    }
}

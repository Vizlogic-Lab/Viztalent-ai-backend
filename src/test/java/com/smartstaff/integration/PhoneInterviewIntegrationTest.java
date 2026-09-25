package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.StubServer.StubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Outbound Twilio phone interviews. Twilio itself is a local stub; the webhooks
 *  Twilio would call are driven here with requests signed exactly the way Twilio
 *  signs them (see Fixtures.twilioSignature). */
class PhoneInterviewIntegrationTest extends IntegrationTestBase {

    private static final String SID = "ACtest0123456789";
    private static final String AUTH_TOKEN = "twilio-secret-token";
    private static final String PUBLIC_URL = "https://tunnel.example.com";

    private Account admin;
    private String job;
    private String interviewId;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Platform Engineer", "java, docker");
        setPublicBaseUrl(admin, PUBLIC_URL);
        setGeminiKey(admin, "AIza-test-key-123");
        setTwilio(admin, SID, AUTH_TOKEN, "+15550001111");
        STUB.on("POST", "/v1beta/models/", req -> StubResponse.json(200, Fixtures.geminiText(Fixtures.interviewJson(3))));
        interviewId = bodyOf(postJsonAs(admin, "/api/interview/prepare",
                Map.of("session_id", job, "candidate_name", "Priya Nair", "phone", "+919876500001")).andExpect(status().isOk()))
                .path("interview_id").asText();
    }

    private JsonNode placeCall() throws Exception {
        return bodyOf(postJsonAs(admin, "/api/interview/place_call",
                Map.of("session_id", job, "interview_id", interviewId, "phone", "+919876500001")).andExpect(status().isOk()));
    }

    // ── placing the call ────────────────────────────────────────────────

    @Test
    @DisplayName("place_call dials through Twilio's REST API with the right credentials, numbers and webhook URLs, and records the call")
    void placeCallSuccess() throws Exception {
        STUB.respond("POST", "/2010-04-01/Accounts/" + SID + "/Calls.json", 201, "{\"sid\":\"CA123abc\",\"status\":\"queued\"}");

        JsonNode result = placeCall();

        assertThat(result.path("status").asText()).isEqualTo("ok");
        assertThat(result.path("call_sid").asText()).isEqualTo("CA123abc");

        var calls = STUB.requests("/2010-04-01/Accounts/" + SID + "/Calls.json");
        assertThat(calls).hasSize(1);
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString((SID + ":" + AUTH_TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThat(calls.get(0).authorization()).isEqualTo(expectedAuth);
        Map<String, java.util.List<String>> form = parseForm(calls.get(0).body());
        assertThat(form.get("To")).containsExactly("+919876500001");
        assertThat(form.get("From")).containsExactly("+15550001111");
        assertThat(form.get("Url")).containsExactly(PUBLIC_URL + "/api/interview/twiml/voice/" + interviewId);
        assertThat(form.get("StatusCallback")).containsExactly(PUBLIC_URL + "/api/interview/twiml/status");
        assertThat(form.get("StatusCallbackEvent")).contains("ringing", "answered", "completed");

        assertThat(jdbc.queryForMap("select mode, twilio_call_sid, twilio_call_status from interviews where id = ?::uuid", interviewId))
                .containsEntry("mode", "PHONE").containsEntry("twilio_call_sid", "CA123abc").containsEntry("twilio_call_status", "queued");
    }

    @Test
    @DisplayName("Twilio rejecting the call is reported as a normal {status:'error', detail} response and leaves the interview alone")
    void placeCallRejectedByTwilio() throws Exception {
        STUB.respond("POST", "/2010-04-01/Accounts/", 401, "{\"code\":20003,\"message\":\"Authentication Error\"}");

        JsonNode result = placeCall();

        assertThat(result.path("status").asText()).isEqualTo("error");
        assertThat(result.path("detail").asText()).contains("Twilio rejected this call");
        assertThat(result.has("call_sid")).isFalse();
        assertThat(jdbc.queryForMap("select mode, twilio_call_sid from interviews where id = ?::uuid", interviewId))
                .containsEntry("mode", "BROWSER").containsEntry("twilio_call_sid", null);
    }

    @Test
    @DisplayName("place_call explains itself when Twilio isn't configured or the interview doesn't exist")
    void placeCallPreconditions() throws Exception {
        jdbc.update("delete from app_settings where key in ('twilio_account_sid','twilio_auth_token','twilio_from_number')");
        JsonNode unconfigured = placeCall();
        assertThat(unconfigured.path("status").asText()).isEqualTo("error");
        assertThat(unconfigured.path("detail").asText()).contains("isn't fully configured");

        setTwilio(admin, SID, AUTH_TOKEN, "+15550001111");
        JsonNode unknown = bodyOf(postJsonAs(admin, "/api/interview/place_call",
                Map.of("session_id", job, "interview_id", "99999999-9999-9999-9999-999999999999", "phone", "+919876500001"))
                .andExpect(status().isOk()));
        assertThat(unknown.path("status").asText()).isEqualTo("error");
        assertThat(unknown.path("detail").asText()).contains("Interview not found");
        assertThat(STUB.requests("/2010-04-01/")).as("Twilio is never called for either").isEmpty();
    }

    @Test
    @DisplayName("place_call requires a phone number")
    void placeCallValidation() throws Exception {
        postJsonAs(admin, "/api/interview/place_call", Map.of("session_id", job, "interview_id", interviewId))
                .andExpect(status().isBadRequest());
    }

    private static Map<String, java.util.List<String>> parseForm(String body) {
        Map<String, java.util.List<String>> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            out.computeIfAbsent(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), k -> new java.util.ArrayList<>())
                    .add(URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }

    // ── the call itself: Twilio → our webhooks ──────────────────────────

    private ResultActions webhook(String path, Map<String, String> params) throws Exception {
        return webhook(path, params, Fixtures.twilioSignature(AUTH_TOKEN, PUBLIC_URL + path, params));
    }

    private ResultActions webhook(String path, Map<String, String> params, String signature) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (signature != null) request.header("X-Twilio-Signature", signature);
        params.forEach(request::param);
        return mvc.perform(request);
    }

    private String twiml(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_XML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a full call: intro + question 1, each spoken answer saved as it arrives, outro and hang-up after the last")
    void fullCallFlow() throws Exception {
        String voice = "/api/interview/twiml/voice/" + interviewId;
        String answer = "/api/interview/twiml/answer/" + interviewId + "/";

        String opening = twiml(webhook(voice, Map.of("CallSid", "CAx")));
        assertThat(opening).startsWith("<Response>")
                .contains("welcome to your short AI interview")
                .contains("Interview question number 1?")
                .contains("<Gather input=\"speech\"")
                .contains("action=\"" + PUBLIC_URL + answer + "0\"");

        String q2 = twiml(webhook(answer + "0", Map.of("SpeechResult", "I have seven years of Java experience.")));
        assertThat(q2).contains("Interview question number 2?").contains("action=\"" + PUBLIC_URL + answer + "1\"").doesNotContain("Hangup");

        String q3 = twiml(webhook(answer + "1", Map.of("SpeechResult", "I start with clear resource names.")));
        assertThat(q3).contains("Interview question number 3?").contains("action=\"" + PUBLIC_URL + answer + "2\"");

        String closing = twiml(webhook(answer + "2", Map.of("SpeechResult", "I once fixed a nasty race condition.")));
        assertThat(closing).isEqualTo("<Response><Say language=\"en-IN\">Thank you — our team will be in touch.</Say><Hangup/></Response>");

        assertThat(jdbc.queryForList("select answer from interview_turns where interview_id = ?::uuid order by seq", String.class, interviewId))
                .containsExactly("I have seven years of Java experience.", "I start with clear resource names.", "I once fixed a nasty race condition.");
        assertThat(jdbc.queryForObject("select status from interviews where id = ?::uuid", String.class, interviewId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select started_at is not null and ended_at is not null from interviews where id = ?::uuid", Boolean.class, interviewId)).isTrue();

        getAs(admin, "/api/interview/transcripts/" + job)
                .andExpect(jsonPath("$.interviews[0].transcript[2].answer").value("I once fixed a nasty race condition."));
    }

    @Test
    @DisplayName("silence (no SpeechResult) is recorded as an unanswered question and the call moves on")
    void silenceMovesOn() throws Exception {
        String answer = "/api/interview/twiml/answer/" + interviewId + "/";

        String next = twiml(webhook(answer + "0", Map.of("CallSid", "CAx")));

        assertThat(next).contains("Interview question number 2?");
        assertThat(jdbc.queryForObject("select answer from interview_turns where interview_id = ?::uuid and seq = 0", String.class, interviewId)).isEmpty();
    }

    @Test
    @DisplayName("text spoken back to the caller is XML-escaped, so a question with & or < can't break the TwiML")
    void twimlIsEscaped() throws Exception {
        jdbc.update("update interview_turns set question = 'Fish & <chips> \"quoted\"?' where interview_id = ?::uuid and seq = 0", interviewId);

        String opening = twiml(webhook("/api/interview/twiml/voice/" + interviewId, Map.of("CallSid", "CAx")));

        assertThat(opening).contains("Fish &amp; &lt;chips&gt; &quot;quoted&quot;?").doesNotContain("<chips>");
    }

    @Test
    @DisplayName("an answer for a question number that doesn't exist just ends the call politely, changing nothing")
    void outOfRangeAnswer() throws Exception {
        String closing = twiml(webhook("/api/interview/twiml/answer/" + interviewId + "/9", Map.of("SpeechResult", "stray")));

        assertThat(closing).contains("<Hangup/>");
        assertThat(jdbc.queryForObject("select count(*) from interview_turns where interview_id = ?::uuid and answer = 'stray'", Integer.class, interviewId)).isZero();
    }

    @Test
    @DisplayName("webhooks for an unknown interview say sorry and hang up rather than erroring")
    void unknownInterviewWebhook() throws Exception {
        String unknown = "99999999-9999-9999-9999-999999999999";

        assertThat(twiml(webhook("/api/interview/twiml/voice/" + unknown, Map.of()))).contains("Sorry").contains("<Hangup/>");
        assertThat(twiml(webhook("/api/interview/twiml/answer/" + unknown + "/0", Map.of()))).contains("Sorry").contains("<Hangup/>");
    }

    @Test
    @DisplayName("TwiML declares UTF-8, and non-Latin text (a Hindi interview) reaches Twilio intact")
    void twimlIsUtf8() throws Exception {
        String hindi = "आप अपने बारे में बताइए?";
        jdbc.update("update interview_turns set question = ? where interview_id = ?::uuid and seq = 0", hindi, interviewId);

        var response = webhook("/api/interview/twiml/voice/" + interviewId, Map.of("CallSid", "CAx"))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getContentType()).containsIgnoringCase("charset=UTF-8");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains(hindi);
    }

    // ── webhook authenticity ────────────────────────────────────────────

    @Test
    @DisplayName("webhooks with a wrong, tampered, missing or wrong-URL signature are refused and do nothing")
    void signatureEnforced() throws Exception {
        String path = "/api/interview/twiml/answer/" + interviewId + "/0";
        Map<String, String> params = Map.of("SpeechResult", "forged answer");

        webhook(path, params, "AAAAAAAAAAAAAAAAAAAAAAAAAAA=").andExpect(status().isForbidden());
        webhook(path, params, null).andExpect(status().isForbidden());
        // Signed for different parameters (a tampered body).
        webhook(path, params, Fixtures.twilioSignature(AUTH_TOKEN, PUBLIC_URL + path, Map.of("SpeechResult", "something else")))
                .andExpect(status().isForbidden());
        // Signed for the request as our own server sees it (localhost) instead of the public URL Twilio used.
        webhook(path, params, Fixtures.twilioSignature(AUTH_TOKEN, "http://localhost" + path, params)).andExpect(status().isForbidden());
        // Signed with the wrong secret.
        webhook(path, params, Fixtures.twilioSignature("not-the-token", PUBLIC_URL + path, params)).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select answer from interview_turns where interview_id = ?::uuid and seq = 0", String.class, interviewId))
                .as("a forged webhook must not write anything").isEmpty();
    }

    @Test
    @DisplayName("a refusal is TwiML (<Reject/>), so Twilio sees a well-formed response")
    void refusalIsTwiml() throws Exception {
        webhook("/api/interview/twiml/voice/" + interviewId, Map.of(), "bad")
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("<Reject/>")));
    }

    @Test
    @DisplayName("with Twilio unconfigured no webhook can be authentic, so all are refused")
    void unconfiguredRefuses() throws Exception {
        jdbc.update("delete from app_settings where key = 'twilio_auth_token'");
        String path = "/api/interview/twiml/voice/" + interviewId;

        webhook(path, Map.of(), Fixtures.twilioSignature(AUTH_TOKEN, PUBLIC_URL + path, Map.of())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the signature is checked against the configured public URL, so changing it in Settings takes effect")
    void signatureFollowsPublicUrlSetting() throws Exception {
        setPublicBaseUrl(admin, "https://new-tunnel.example.com");
        String path = "/api/interview/twiml/voice/" + interviewId;

        webhook(path, Map.of(), Fixtures.twilioSignature(AUTH_TOKEN, PUBLIC_URL + path, Map.of())).andExpect(status().isForbidden());
        webhook(path, Map.of(), Fixtures.twilioSignature(AUTH_TOKEN, "https://new-tunnel.example.com" + path, Map.of())).andExpect(status().isOk());
    }

    // ── status callbacks and polling ────────────────────────────────────

    private void statusCallback(String callSid, String callStatus) throws Exception {
        webhook("/api/interview/twiml/status", Map.of("CallSid", callSid, "CallStatus", callStatus)).andExpect(status().isOk());
    }

    private String callStatusOf() throws Exception {
        return getAs(admin, "/api/interview/call_status/" + interviewId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("status callbacks move the call through ringing → in-progress → completed, and call_status reports it and when it has ended")
    void statusCallbacksAndPolling() throws Exception {
        STUB.respond("POST", "/2010-04-01/Accounts/" + SID + "/Calls.json", 201, "{\"sid\":\"CA777\",\"status\":\"queued\"}");
        placeCall();

        JsonNode queued = json.readTree(callStatusOf());
        assertThat(queued.path("status").asText()).isEqualTo("queued");
        assertThat(queued.path("ended").asBoolean()).isFalse();
        assertThat(queued.path("total").asInt()).isEqualTo(3);
        assertThat(queued.path("answered").asInt()).isZero();

        statusCallback("CA777", "ringing");
        assertThat(json.readTree(callStatusOf()).path("status").asText()).isEqualTo("ringing");
        statusCallback("CA777", "in-progress");
        assertThat(json.readTree(callStatusOf()).path("ended").asBoolean()).isFalse();

        String answer = "/api/interview/twiml/answer/" + interviewId + "/";
        twiml(webhook(answer + "0", Map.of("SpeechResult", "First answer")));
        JsonNode mid = json.readTree(callStatusOf());
        assertThat(mid.path("answered").asInt()).isEqualTo(1);
        assertThat(mid.path("transcript").get(0).path("answer").asText()).isEqualTo("First answer");

        statusCallback("CA777", "completed");
        JsonNode done = json.readTree(callStatusOf());
        assertThat(done.path("status").asText()).isEqualTo("completed");
        assertThat(done.path("ended").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select status from interviews where id = ?::uuid", String.class, interviewId))
                .as("caller hung up early, but the call completed, so what was said is the transcript").isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a call nobody answers is marked failed, with the reason Twilio gave")
    void noAnswerFails() throws Exception {
        STUB.respond("POST", "/2010-04-01/Accounts/" + SID + "/Calls.json", 201, "{\"sid\":\"CA888\",\"status\":\"queued\"}");
        placeCall();

        statusCallback("CA888", "no-answer");

        JsonNode status = json.readTree(callStatusOf());
        assertThat(status.path("status").asText()).isEqualTo("no-answer");
        assertThat(status.path("ended").asBoolean()).isTrue();
        assertThat(jdbc.queryForMap("select status, ended_at is not null as ended from interviews where id = ?::uuid", interviewId))
                .containsEntry("status", "FAILED").containsEntry("ended", true);
        getAs(admin, "/api/interview/transcripts/" + job).andExpect(jsonPath("$.interviews.length()").value(0));
    }

    @Test
    @DisplayName("busy, failed and canceled calls all end the poll")
    void otherTerminalStatuses() throws Exception {
        for (String terminal : new String[]{"busy", "failed", "canceled"}) {
            jdbc.update("update interviews set twilio_call_sid = ?, twilio_call_status = 'queued', status = 'PENDING' where id = ?::uuid",
                    "CA-" + terminal, interviewId);
            statusCallback("CA-" + terminal, terminal);

            assertThat(json.readTree(callStatusOf()).path("ended").asBoolean()).as(terminal).isTrue();
            assertThat(jdbc.queryForObject("select status from interviews where id = ?::uuid", String.class, interviewId)).isEqualTo("FAILED");
        }
    }

    @Test
    @DisplayName("a late 'failed' callback never downgrades an interview that already completed")
    void lateFailureDoesNotDowngrade() throws Exception {
        jdbc.update("update interviews set twilio_call_sid = 'CA-late', twilio_call_status = 'in-progress' where id = ?::uuid", interviewId);
        String answer = "/api/interview/twiml/answer/" + interviewId + "/";
        for (int seq = 0; seq < 3; seq++) twiml(webhook(answer + seq, Map.of("SpeechResult", "answer " + seq)));
        assertThat(jdbc.queryForObject("select status from interviews where id = ?::uuid", String.class, interviewId)).isEqualTo("COMPLETED");

        statusCallback("CA-late", "failed");

        assertThat(jdbc.queryForObject("select status from interviews where id = ?::uuid", String.class, interviewId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a status callback for a call we don't know is acknowledged and ignored")
    void unknownCallSid() throws Exception {
        webhook("/api/interview/twiml/status", Map.of("CallSid", "CAunknown", "CallStatus", "completed")).andExpect(status().isOk());
        webhook("/api/interview/twiml/status", Map.of("CallStatus", "completed")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a forged status callback is refused and can't mark a call finished")
    void forgedStatusCallback() throws Exception {
        STUB.respond("POST", "/2010-04-01/Accounts/" + SID + "/Calls.json", 201, "{\"sid\":\"CA999\",\"status\":\"queued\"}");
        placeCall();

        webhook("/api/interview/twiml/status", Map.of("CallSid", "CA999", "CallStatus", "completed"), "forged").andExpect(status().isForbidden());

        assertThat(json.readTree(callStatusOf()).path("status").asText()).isEqualTo("queued");
    }

    @Test
    @DisplayName("call_status for an unknown interview is a 404")
    void callStatusUnknown() throws Exception {
        getAs(admin, "/api/interview/call_status/99999999-9999-9999-9999-999999999999").andExpect(status().isNotFound());
    }
}

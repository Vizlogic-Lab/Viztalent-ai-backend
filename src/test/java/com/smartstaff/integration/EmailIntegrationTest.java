package com.smartstaff.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.smartstaff.service.impl.EmailService;
import com.smartstaff.support.Fixtures;
import com.smartstaff.support.IntegrationTestBase;
import com.smartstaff.support.StubServer.StubResponse;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F17 — invite, reminder and rejection emails sent from the backend, against
 *  GreenMail (in-memory SMTP): sending after mint, the mailto fallback without
 *  SMTP, failures logged without breaking minting, retry (re-issued token),
 *  reminders once, editable templates, and no token in logs or the database. */
@ExtendWith(OutputCaptureExtension.class)
class EmailIntegrationTest extends IntegrationTestBase {

    private static final String SMTP_USER = "hr-mailer@test.local";
    private static final String SMTP_PASS = "smtp-pass-123";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(SMTP_USER, SMTP_PASS))
            .withPerMethodLifecycle(true);

    @Autowired EmailService emailService;

    private Account admin;
    private String job;

    @BeforeEach
    void setUp() throws Exception {
        admin = newAdmin();
        job = createJob(admin, "Java Backend Developer", "java, spring, sql");
        setPublicBaseUrl(admin, "https://hire.example.com");
        seedReadyAssessment(job, "L1");
    }

    private void smtp(int port) throws Exception {
        postJsonAs(admin, "/api/config/smtp", Map.of("host", "127.0.0.1", "port", port, "username", SMTP_USER,
                "password", SMTP_PASS, "from_address", "talent@hire.example.com", "tls", false)).andExpect(status().isOk());
    }

    private JsonNode mint(String email, boolean send) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("session_id", job, "candidate_email", email,
                "candidate_name", "Asha Rao", "levels", List.of("L1")));
        if (send) body.put("send_email", true);
        return bodyOf(postJsonAs(admin, "/api/invites/mint", body).andExpect(status().isOk())).path("invites").get(0);
    }

    private static String token(String url) {
        return url.substring(url.indexOf("token=") + 6);
    }

    private Map<String, Object> logRow(String emailId) {
        return jdbc.queryForMap("select * from email_log where id = ?::uuid", emailId);
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    @DisplayName("SMTP settings: admin only, validated, password masked; the test button sends; clearing goes back to mailto")
    void smtpSettings() throws Exception {
        assertThat(bodyOf(getAs(admin, "/api/config/smtp")).path("configured").asBoolean()).isFalse();
        getAs(newEmployee(), "/api/config/smtp").andExpect(status().isForbidden());
        postJsonAs(admin, "/api/config/smtp", Map.of("host", "127.0.0.1", "port", 25, "from_address", "not-an-email"))
                .andExpect(status().isBadRequest());
        postJsonAs(admin, "/api/config/smtp", Map.of("host", "smtp example", "from_address", "a@b.co")).andExpect(status().isBadRequest());

        smtp(greenMail.getSmtp().getPort());
        JsonNode view = bodyOf(getAs(admin, "/api/config/smtp"));
        assertThat(view.path("configured").asBoolean()).isTrue();
        assertThat(view.path("password").asText()).isNotEqualTo(SMTP_PASS).doesNotContain("pass-123");
        assertThat(jdbc.queryForObject("select value from app_settings where key = 'smtp_password'", String.class))
                .as("stored encrypted").doesNotContain(SMTP_PASS);

        JsonNode test = bodyOf(postJsonAs(admin, "/api/config/smtp/test", Map.of("to", "me@test.local")).andExpect(status().isOk()));
        assertThat(test.path("ok").asBoolean()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()[0].getAllRecipients()[0].toString()).isEqualTo("me@test.local");

        mvc.perform(withAuth(delete("/api/config/smtp"), admin)).andExpect(status().isOk());
        assertThat(bodyOf(getAs(admin, "/api/config/smtp")).path("configured").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("assessment invite with send_email: sent after mint with the link, logged SENT without the body or link")
    void assessmentInviteSent() throws Exception {
        smtp(greenMail.getSmtp().getPort());
        JsonNode inv = mint("asha@example.com", true);
        assertThat(inv.path("email").path("status").asText()).isEqualTo("QUEUED");
        String url = inv.path("url").asText();

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("asha@example.com");
        assertThat(m.getFrom()[0].toString()).isEqualTo("talent@hire.example.com");
        assertThat(m.getSubject()).isEqualTo("Your assessment for Java Backend Developer");
        String body = GreenMailUtil.getBody(m).replace("=\r\n", "").replace("=3D", "=");
        assertThat(body).contains("Hi Asha Rao").contains(url).contains("expires on");

        Map<String, Object> row = logRow(inv.path("email").path("email_id").asText());
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("template")).isEqualTo("ASSESSMENT_INVITE");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.values().stream().filter(Objects::nonNull).map(Object::toString)).noneMatch(v -> v.contains(token(url)));

        assertThat(mint("other@example.com", false).has("email")).as("no email unless asked").isFalse();
        JsonNode list = bodyOf(getAs(admin, "/api/jobs/" + job + "/emails").andExpect(status().isOk()));
        assertThat(list.path("emails")).hasSize(1);
        getAs(newEmployee(), "/api/jobs/" + job + "/emails").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("without SMTP: minting still works and reports NOT_CONFIGURED (the mailto flow stays); rejection needs SMTP")
    void notConfigured() throws Exception {
        JsonNode inv = mint("asha@example.com", true);
        assertThat(inv.path("url").asText()).contains("token=");
        assertThat(inv.path("email").path("status").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(jdbc.queryForObject("select count(*) from email_log where job_id = ?::uuid", Integer.class, job)).isZero();
        postJsonAs(admin, "/api/jobs/" + job + "/emails/rejection", Map.of("candidate_email", "asha@example.com"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a failed send is logged without breaking the mint; retry re-issues the token; no token ever reaches the logs")
    void failureAndRetry(CapturedOutput output) throws Exception {
        smtp(closedPort());
        JsonNode inv = mint("asha@example.com", true);
        String firstUrl = inv.path("url").asText(), firstToken = token(firstUrl);
        String emailId = inv.path("email").path("email_id").asText();
        Map<String, Object> failed = logRow(emailId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat((String) failed.get("error")).isNotBlank().doesNotContain(firstToken);
        JsonNode row = bodyOf(getAs(admin, "/api/jobs/" + job + "/emails")).path("emails").get(0);
        assertThat(row.path("retryable").asBoolean()).isTrue();

        smtp(greenMail.getSmtp().getPort());
        JsonNode retried = bodyOf(postJsonAs(admin, "/api/jobs/" + job + "/emails/" + emailId + "/retry", Map.of())
                .andExpect(status().isOk()));
        assertThat(retried.path("email_id").asText()).isEqualTo(emailId);
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        String body = GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]).replace("=\r\n", "").replace("=3D", "=");
        String newToken = body.substring(body.indexOf("token=") + 6).split("\\s")[0];
        assertThat(newToken).isNotEqualTo(firstToken);
        Map<String, Object> sent = logRow(emailId);
        assertThat(sent.get("status")).isEqualTo("SENT");
        assertThat(sent.get("attempts")).isEqualTo(2);
        assertThat(sent.get("error")).isNull();

        // The re-issued link works; the original no longer does.
        mvc.perform(get("/api/candidate/assessment/" + firstToken)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/candidate/assessment/" + newToken)).andExpect(status().isOk());
        postJsonAs(admin, "/api/jobs/" + job + "/emails/" + emailId + "/retry", Map.of()).andExpect(status().isConflict());

        // A candidate who opened a link (e.g. one HR shared by hand) blocks a re-issue.
        smtp(closedPort());
        JsonNode second = mint("ravi@example.com", true);
        mvc.perform(get("/api/candidate/assessment/" + token(second.path("url").asText()))).andExpect(status().isOk());
        smtp(greenMail.getSmtp().getPort());
        JsonNode blocked = bodyOf(postJsonAs(admin, "/api/jobs/" + job + "/emails/" + second.path("email").path("email_id").asText()
                + "/retry", Map.of()).andExpect(status().isConflict()));
        assertThat(blocked.toString()).contains("already opened");

        String logs = output.getAll();
        assertThat(logs).contains(emailId);
        for (String t : List.of(firstToken, newToken, token(second.path("url").asText()))) {
            assertThat(logs).as("token in logs").doesNotContain(t);
        }
        assertThat(jdbc.queryForList("select coalesce(error, '') from email_log where job_id = ?::uuid", String.class, job))
                .noneMatch(e -> e.contains(firstToken) || e.contains(newToken));
    }

    @Test
    @DisplayName("reminder: once, 24 hours before an emailed invite expires, without a link")
    void reminderOnce() throws Exception {
        smtp(greenMail.getSmtp().getPort());
        JsonNode inv = mint("asha@example.com", true);
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        String inviteId = jdbc.queryForObject("select invite_id::text from email_log where id = ?::uuid", String.class,
                inv.path("email").path("email_id").asText());

        emailService.sendReminders(Instant.now());
        assertThat(greenMail.getReceivedMessages()).as("not due yet (48 h TTL)").hasSize(1);

        jdbc.update("update invites set expires_at = now() + interval '2 hours' where id = ?::uuid", inviteId);
        emailService.sendReminders(Instant.now());
        assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
        MimeMessage reminder = greenMail.getReceivedMessages()[1];
        assertThat(reminder.getSubject()).isEqualTo("Reminder: your Java Backend Developer link expires soon");
        assertThat(GreenMailUtil.getBody(reminder)).doesNotContain("token=").contains("earlier email");
        assertThat(jdbc.queryForObject("select reminder_sent_at is not null from invites where id = ?::uuid", Boolean.class, inviteId)).isTrue();

        emailService.sendReminders(Instant.now());
        assertThat(greenMail.getReceivedMessages()).as("only once").hasSize(2);
    }

    @Test
    @DisplayName("templates are editable and validated; rejection uses the saved template; reset restores the default")
    void templatesAndRejection() throws Exception {
        smtp(greenMail.getSmtp().getPort());
        putJsonAs(admin, "/api/config/email_templates/REMINDER", Map.of("subject", "Hi", "body", "Use {link}"))
                .andExpect(status().isBadRequest());
        putJsonAs(admin, "/api/config/email_templates/ASSESSMENT_INVITE", Map.of("subject", "Hi", "body", "No link"))
                .andExpect(status().isBadRequest());
        putJsonAs(admin, "/api/config/email_templates/NOPE", Map.of("subject", "Hi", "body", "x")).andExpect(status().isNotFound());
        putJsonAs(newEmployee(), "/api/config/email_templates/REJECTION", Map.of("subject", "Hi", "body", "x"))
                .andExpect(status().isForbidden());
        JsonNode saved = bodyOf(putJsonAs(admin, "/api/config/email_templates/REJECTION",
                Map.of("subject", "Update on {role}", "body", "Dear {candidate_name},\nWe went another way for {role}."))
                .andExpect(status().isOk()));
        JsonNode rejection = null;
        for (JsonNode t : saved.path("templates")) if (t.path("kind").asText().equals("REJECTION")) rejection = t;
        assertThat(rejection.path("customised").asBoolean()).isTrue();

        postJsonAs(admin, "/api/jobs/" + job + "/emails/rejection", Map.of("candidate_email", "bad")).andExpect(status().isBadRequest());
        JsonNode queued = bodyOf(postJsonAs(admin, "/api/jobs/" + job + "/emails/rejection",
                Map.of("candidate_email", "ravi@example.com", "candidate_name", "Ravi")).andExpect(status().isOk()));
        assertThat(queued.path("status").asText()).isEqualTo("QUEUED");
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getSubject()).isEqualTo("Update on Java Backend Developer");
        assertThat(GreenMailUtil.getBody(m)).contains("Dear Ravi").contains("for Java Backend Developer.");

        mvc.perform(withAuth(delete("/api/config/email_templates/REJECTION"), admin)).andExpect(status().isOk());
        JsonNode reset = null;
        for (JsonNode t : bodyOf(getAs(admin, "/api/config/email_templates")).path("templates")) {
            if (t.path("kind").asText().equals("REJECTION")) reset = t;
        }
        assertThat(reset.path("customised").asBoolean()).isFalse();
        assertThat(reset.path("subject").asText()).isEqualTo("Your application for {role}");
    }

    @Test
    @DisplayName("emailing an already-minted link: same link, to the invite's own candidate; bad, foreign or used links refused")
    void sendExistingInviteLink() throws Exception {
        smtp(greenMail.getSmtp().getPort());
        JsonNode inv = mint("asha@example.com", false);
        String url = inv.path("url").asText();
        String base = "/api/jobs/" + job + "/emails/invite";

        postJsonAs(admin, base, Map.of("link", "https://evil.example.com/nothing")).andExpect(status().isBadRequest());
        String otherJob = createJob(admin, "Data Engineer", "python, sql");
        postJsonAs(admin, "/api/jobs/" + otherJob + "/emails/invite", Map.of("link", url)).andExpect(status().isNotFound());
        postJsonAs(newEmployee(), base, Map.of("link", url)).andExpect(status().isForbidden());

        JsonNode queued = bodyOf(postJsonAs(admin, base, Map.of("link", url)).andExpect(status().isOk()));
        assertThat(queued.path("status").asText()).isEqualTo("QUEUED");
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("asha@example.com");
        assertThat(GreenMailUtil.getBody(m).replace("=\r\n", "").replace("=3D", "=")).contains(url);

        jdbc.update("update invites set used_at = now() where token_hash = encode(sha256(?::bytea), 'hex')", token(url));
        postJsonAs(admin, base, Map.of("link", url)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("interview invite with send_email: the self-service link is emailed")
    void interviewInviteSent() throws Exception {
        setGeminiKey(admin, "AIza-test-key-123");
        STUB.on("POST", "/v1beta/models/", req -> StubResponse.json(200, Fixtures.geminiText(Fixtures.interviewJson(3))));
        smtp(greenMail.getSmtp().getPort());
        JsonNode res = bodyOf(postJsonAs(admin, "/api/interview/invites/mint", Map.of("session_id", job,
                "candidate_email", "asha@example.com", "candidate_name", "Asha Rao", "send_email", true)).andExpect(status().isOk()));
        assertThat(res.path("status").asText()).isEqualTo("success");
        assertThat(res.path("email").path("status").asText()).isEqualTo("QUEUED");
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage m = greenMail.getReceivedMessages()[0];
        assertThat(m.getSubject()).isEqualTo("Your first-round interview for Java Backend Developer");
        String body = GreenMailUtil.getBody(m).replace("=\r\n", "").replace("=3D", "=");
        assertThat(body).contains(res.path("url").asText());
        assertThat(jdbc.queryForObject("select template from email_log where id = ?::uuid", String.class,
                res.path("email").path("email_id").asText())).isEqualTo("INTERVIEW_INVITE");
    }
}

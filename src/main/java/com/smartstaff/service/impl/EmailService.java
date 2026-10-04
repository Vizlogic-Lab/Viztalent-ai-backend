package com.smartstaff.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartstaff.entity.EmailLog;
import com.smartstaff.entity.Invite;
import com.smartstaff.entity.Job;
import com.smartstaff.entity.User;
import com.smartstaff.exception.ApiException;
import com.smartstaff.repository.AssessmentAttemptRepository;
import com.smartstaff.repository.EmailLogRepository;
import com.smartstaff.repository.InviteRepository;
import com.smartstaff.repository.JobRepository;
import com.smartstaff.service.EmailRequestedEvent;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.EmailTemplates;
import com.smartstaff.util.EmailTemplates.Kind;
import com.smartstaff.util.EmailTemplates.Template;
import com.smartstaff.util.InviteLinks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** F17: assessment / interview invites, 24-hours-before-expiry reminders and
 *  rejections, sent from the backend through the SMTP server in Settings.
 *
 *  Queuing happens inside the caller's transaction (an email_log row + an
 *  in-memory event); EmailDispatcher sends after commit, asynchronously, so a
 *  failed send never breaks minting. Without SMTP nothing is queued and the
 *  caller gets NOT_CONFIGURED — the frontend keeps its mailto flow. The link
 *  is never stored or logged; a retry re-issues the invite's token, which is
 *  refused once the candidate has started. */
@Service
public class EmailService {

    /** status: QUEUED (email_id set) or NOT_CONFIGURED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Queued(String email_id, String status) {
        static final Queued NOT_CONFIGURED = new Queued(null, "NOT_CONFIGURED");
    }

    public record RejectionRequest(String candidate_email, String candidate_name) {}

    public record InviteLinkRequest(String link) {}

    public record TestRequest(String to) {}

    private static final Duration REMIND_BEFORE = Duration.ofHours(24);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(10);

    private final EmailSettingsService settings;
    private final EmailSender sender;
    private final EmailLogRepository logs;
    private final InviteRepository invites;
    private final JobRepository jobs;
    private final AssessmentAttemptRepository attempts;
    private final SettingsService appSettings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final DateTimeFormatter expiresFormat;

    public EmailService(EmailSettingsService settings, EmailSender sender, EmailLogRepository logs, InviteRepository invites,
                        JobRepository jobs, AssessmentAttemptRepository attempts, SettingsService appSettings,
                        ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                        @Value("${app.email.zone:Asia/Kolkata}") String zone) {
        this.settings = settings;
        this.sender = sender;
        this.logs = logs;
        this.invites = invites;
        this.jobs = jobs;
        this.attempts = attempts;
        this.appSettings = appSettings;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.expiresFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a z", Locale.ENGLISH).withZone(ZoneId.of(zone));
    }

    // ── queuing (inside the caller's transaction) ───────────────────────

    /** The invite email for a just-minted invite; link carries rawToken. */
    public Queued queueInvite(Invite invite, String role, String link, String rawToken, User actor) {
        if (!settings.configured()) return Queued.NOT_CONFIGURED;
        Kind kind = "INTERVIEW".equals(invite.getKind()) ? Kind.INTERVIEW_INVITE : Kind.ASSESSMENT_INVITE;
        EmailLog row = row(invite.getJob().getId(), invite.getId(), invite.getCandidateEmail(), invite.getCandidateName(), kind, actor);
        publish(row, kind, values(invite.getCandidateName(), role, link, invite.getExpiresAt()), rawToken);
        return new Queued(row.getId().toString(), EmailLog.QUEUED);
    }

    /** Emails an already-minted invite (HR holds its link) to the invite's own candidate. The token in
     *  the link must belong to an open invite of this job; the link is rebuilt server-side. */
    public Queued sendInviteLink(UUID jobId, InviteLinkRequest req, User actor) {
        String token = tokenFrom(req == null ? null : req.link());
        if (token == null) throw new ApiException(HttpStatus.BAD_REQUEST, "That isn't an invite link.");
        requireConfigured();
        return tx.execute(s -> {
            Invite inv = invites.findByTokenHash(InviteLinks.hash(token)).filter(i -> jobId.equals(i.getJob().getId()))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Invite not found for this job."));
            if (inv.getUsedAt() != null || inv.isExpired()) {
                throw new ApiException(HttpStatus.CONFLICT, "This invite has been used or has expired.", "invite_closed");
            }
            Job job = jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
            String base = appSettings.getPublicBaseUrl();
            String link = "INTERVIEW".equals(inv.getKind()) ? InviteLinks.interview(base, token)
                    : InviteLinks.assessment(base, job.jdNumberDisplay(), inv.getLevels(), inv.isCombined(), token);
            return queueInvite(inv, job.getTitle(), link, token, actor);
        });
    }

    /** The raw token of an assessment (?token=) or interview (/interview/{token}) link, or null. */
    static String tokenFrom(String link) {
        if (link == null) return null;
        String l = link.trim(), t = null;
        int q = l.indexOf("token=");
        if (q >= 0) t = l.substring(q + 6).split("[&#]")[0];
        else if (l.contains("/interview/")) t = l.substring(l.lastIndexOf("/interview/") + 11).split("[?#/]")[0];
        return t != null && t.matches("[A-Za-z0-9_-]{20,128}") ? t : null;
    }

    public Queued rejection(UUID jobId, RejectionRequest req, User actor) {
        if (req == null || !EmailSettingsService.looksLikeEmail(req.candidate_email())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "candidate_email must be an email address.");
        }
        requireConfigured();
        return tx.execute(s -> {
            Job job = jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
            String name = req.candidate_name() == null || req.candidate_name().isBlank() ? null : req.candidate_name().trim();
            EmailLog row = row(jobId, null, req.candidate_email().trim(), name, Kind.REJECTION, actor);
            publish(row, Kind.REJECTION, values(name, job.getTitle(), null, null), null);
            return new Queued(row.getId().toString(), EmailLog.QUEUED);
        });
    }

    /** Reminders for emailed, unused invites expiring within 24 hours (once each). */
    public int sendReminders(Instant now) {
        if (!settings.configured()) return 0;
        Integer n = tx.execute(s -> {
            int count = 0;
            for (Invite inv : invites.findDueReminders(now, now.plus(REMIND_BEFORE))) {
                EmailLog row = row(inv.getJob().getId(), inv.getId(), inv.getCandidateEmail(), inv.getCandidateName(), Kind.REMINDER, null);
                publish(row, Kind.REMINDER, values(inv.getCandidateName(), inv.getJob().getTitle(), null, inv.getExpiresAt()), null);
                inv.setReminderSentAt(now);
                invites.save(inv);
                count++;
            }
            return count;
        });
        return n == null ? 0 : n;
    }

    // ── retry ───────────────────────────────────────────────────────────

    /** Re-sends a failed email. For an invite this re-issues its token (the old link can't be rebuilt),
     *  so the old link stops working — refused once the candidate has used or started it. */
    public Map<String, Object> retry(UUID jobId, UUID emailId, User actor) {
        requireConfigured();
        return tx.execute(s -> {
            EmailLog row = logs.findById(emailId).filter(r -> jobId.equals(r.getJobId()))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Email not found."));
            if (EmailLog.SENT.equals(row.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "This email was already delivered.", "already_sent");
            }
            if (!retryable(row)) {
                throw new ApiException(HttpStatus.CONFLICT, "This email is still being sent.", "still_sending");
            }
            Kind kind = Kind.parse(row.getTemplate());
            Job job = jobs.findById(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found."));
            Map<String, String> values;
            String secret = null;
            if (kind.invite() || kind == Kind.REMINDER) {
                Invite inv = row.getInviteId() == null ? null : invites.findById(row.getInviteId()).orElse(null);
                if (inv == null || inv.getUsedAt() != null || inv.isExpired()) {
                    throw new ApiException(HttpStatus.CONFLICT, "This invite has been used or has expired.", "invite_closed");
                }
                String link = null;
                if (kind.invite()) {
                    if ("ASSESSMENT".equals(inv.getKind()) && !attempts.findByInviteIdOrderByStartedAtAsc(inv.getId()).isEmpty()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "The candidate has already opened this link, so it can't be re-issued.", "invite_started");
                    }
                    secret = InviteLinks.newToken();
                    inv.setTokenHash(InviteLinks.hash(secret));
                    invites.save(inv);
                    String base = appSettings.getPublicBaseUrl();
                    link = "INTERVIEW".equals(inv.getKind()) ? InviteLinks.interview(base, secret)
                            : InviteLinks.assessment(base, job.jdNumberDisplay(), inv.getLevels(), inv.isCombined(), secret);
                }
                values = values(inv.getCandidateName(), job.getTitle(), link, inv.getExpiresAt());
            } else {
                values = values(row.getCandidateName(), job.getTitle(), null, null);
            }
            row.setStatus(EmailLog.QUEUED);
            row.setError(null);
            logs.save(row);
            publish(row, kind, values, secret);
            return view(row);
        });
    }

    // ── reading / testing ───────────────────────────────────────────────

    public Map<String, Object> list(UUID jobId) {
        return Map.of("ok", true, "smtp_configured", settings.configured(),
                "emails", logs.findTop200ByJobIdOrderByCreatedAtDesc(jobId).stream().map(EmailService::view).toList());
    }

    /** Synchronous test send from Settings. */
    public Map<String, Object> test(TestRequest req) {
        if (req == null || !EmailSettingsService.looksLikeEmail(req.to())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "to must be an email address.");
        }
        EmailSettingsService.Smtp smtp = settings.smtpOrNull();
        if (smtp == null) return Map.of("ok", false, "message", "SMTP isn't configured yet.");
        try {
            sender.send(smtp, req.to().trim(), "Viztalent test email",
                    "This is a test email from Viztalent. Your SMTP settings work.");
            return Map.of("ok", true, "message", "Test email sent to " + req.to().trim() + ".");
        } catch (Exception e) {
            return Map.of("ok", false, "message", "Could not send: "
                    + EmailDispatcher.scrub(e, new EmailRequestedEvent(null, null, null, null, null)));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private EmailLog row(UUID jobId, UUID inviteId, String to, String name, Kind kind, User actor) {
        EmailLog row = new EmailLog();
        row.setJobId(jobId);
        row.setInviteId(inviteId);
        row.setToAddress(to);
        row.setCandidateName(name);
        row.setTemplate(kind.name());
        row.setCreatedBy(actor == null ? null : actor.publicId());
        return logs.save(row);
    }

    private void publish(EmailLog row, Kind kind, Map<String, String> values, String secret) {
        Template t = settings.template(kind);
        events.publishEvent(new EmailRequestedEvent(row.getId(), row.getToAddress(),
                EmailTemplates.render(t.subject(), values, true), EmailTemplates.render(t.body(), values, false), secret));
    }

    private Map<String, String> values(String name, String role, String link, Instant expiresAt) {
        Map<String, String> v = new HashMap<>();
        v.put("candidate_name", name == null || name.isBlank() ? "there" : name.trim());
        v.put("role", role == null || role.isBlank() ? "the role" : role.trim());
        if (link != null) v.put("link", link);
        if (expiresAt != null) v.put("expires_at", expiresFormat.format(expiresAt));
        return v;
    }

    private void requireConfigured() {
        if (!settings.configured()) {
            throw new ApiException(HttpStatus.CONFLICT, "Email isn't set up — add an SMTP server in Settings.", "smtp_not_configured");
        }
    }

    private static Map<String, Object> view(EmailLog r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email_id", r.getId().toString());
        m.put("template", r.getTemplate());
        m.put("to", r.getToAddress());
        m.put("candidate_name", r.getCandidateName());
        m.put("status", r.getStatus());
        m.put("error", r.getError());
        m.put("attempts", r.getAttempts());
        m.put("invite_id", r.getInviteId() == null ? null : r.getInviteId().toString());
        m.put("created_at", r.getCreatedAt());
        m.put("sent_at", r.getSentAt());
        m.put("retryable", retryable(r));
        return m;
    }

    /** FAILED, or stuck in QUEUED (e.g. the server stopped mid-send). */
    private static boolean retryable(EmailLog r) {
        return EmailLog.FAILED.equals(r.getStatus())
                || (EmailLog.QUEUED.equals(r.getStatus()) && r.getCreatedAt().isBefore(Instant.now().minus(STUCK_AFTER)));
    }
}

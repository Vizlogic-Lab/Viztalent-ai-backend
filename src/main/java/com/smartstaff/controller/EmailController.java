package com.smartstaff.controller;

import com.smartstaff.entity.User;
import com.smartstaff.service.impl.EmailService;
import com.smartstaff.service.impl.EmailSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** F17: SMTP settings, editable templates and a test send (admin; /api/config/**
 *  is also admin-only in SecurityConfig), and per job the email log, retry and
 *  rejection emails (job owner or admin). */
@RestController
public class EmailController {

    private final EmailSettingsService settings;
    private final EmailService emails;

    public EmailController(EmailSettingsService settings, EmailService emails) {
        this.settings = settings;
        this.emails = emails;
    }

    // ── Settings (admin) ────────────────────────────────────────────────

    @GetMapping("/api/config/smtp")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> smtp() {
        return ResponseEntity.ok(settings.smtpView());
    }

    @PostMapping("/api/config/smtp")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> saveSmtp(@RequestBody EmailSettingsService.SmtpRequest req, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settings.saveSmtp(req, admin));
    }

    @DeleteMapping("/api/config/smtp")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> clearSmtp() {
        return ResponseEntity.ok(settings.clearSmtp());
    }

    @PostMapping("/api/config/smtp/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> testSmtp(@RequestBody EmailService.TestRequest req) {
        return ResponseEntity.ok(emails.test(req));
    }

    @GetMapping("/api/config/email_templates")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> templates() {
        return ResponseEntity.ok(settings.templatesView());
    }

    @PutMapping("/api/config/email_templates/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> saveTemplate(@PathVariable String kind, @RequestBody EmailSettingsService.TemplateRequest req,
                                                            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(settings.saveTemplate(kind, req, admin));
    }

    @DeleteMapping("/api/config/email_templates/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> resetTemplate(@PathVariable String kind) {
        return ResponseEntity.ok(settings.resetTemplate(kind));
    }

    // ── per job (owner or admin) ────────────────────────────────────────

    @GetMapping("/api/jobs/{jobId}/emails")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<Map<String, Object>> list(@PathVariable UUID jobId) {
        return ResponseEntity.ok(emails.list(jobId));
    }

    @PostMapping("/api/jobs/{jobId}/emails/{emailId}/retry")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable UUID jobId, @PathVariable UUID emailId, @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(emails.retry(jobId, emailId, actor));
    }

    @PostMapping("/api/jobs/{jobId}/emails/invite")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<EmailService.Queued> sendInvite(@PathVariable UUID jobId, @RequestBody EmailService.InviteLinkRequest req,
                                                          @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(emails.sendInviteLink(jobId, req, actor));
    }

    @PostMapping("/api/jobs/{jobId}/emails/rejection")
    @PreAuthorize("@jobAccess.canAccessJob(#jobId, authentication)")
    public ResponseEntity<EmailService.Queued> rejection(@PathVariable UUID jobId, @RequestBody EmailService.RejectionRequest req,
                                                         @AuthenticationPrincipal User actor) {
        return ResponseEntity.ok(emails.rejection(jobId, req, actor));
    }
}

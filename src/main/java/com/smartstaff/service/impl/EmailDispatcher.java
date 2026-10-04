package com.smartstaff.service.impl;

import com.smartstaff.entity.EmailLog;
import com.smartstaff.repository.EmailLogRepository;
import com.smartstaff.service.EmailRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/** F17: sends queued emails after the queuing transaction commits, on the
 *  email executor, and records SENT / FAILED in email_log. A failure never
 *  reaches the request that queued it. Only the email id is ever logged. */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final int MAX_ERROR = 500;

    private final EmailSettingsService settings;
    private final EmailSender sender;
    private final EmailLogRepository logs;
    private final TransactionTemplate tx;

    public EmailDispatcher(EmailSettingsService settings, EmailSender sender, EmailLogRepository logs,
                           PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.sender = sender;
        this.logs = logs;
        this.tx = new TransactionTemplate(transactionManager);
        // Runs after the queuing transaction committed; when run inline that finished
        // transaction is still bound to the thread and would swallow a REQUIRED write.
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Async("emailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(EmailRequestedEvent e) {
        String error = null;
        EmailSettingsService.Smtp smtp = settings.smtpOrNull();
        if (smtp == null) {
            error = "SMTP is not configured.";
        } else {
            try {
                sender.send(smtp, e.to(), e.subject(), e.body());
            } catch (Exception ex) {
                error = scrub(ex, e);
                log.warn("Email {} could not be sent: {}", e.emailId(), error);
            }
        }
        String result = error;
        tx.executeWithoutResult(s -> logs.findById(e.emailId()).ifPresent(row -> {
            row.setAttempts(row.getAttempts() + 1);
            row.setStatus(result == null ? EmailLog.SENT : EmailLog.FAILED);
            row.setError(result);
            if (result == null) row.setSentAt(Instant.now());
            logs.save(row);
        }));
    }

    /** Error text without the invite token, link or body, cut short. */
    static String scrub(Exception ex, EmailRequestedEvent e) {
        StringBuilder sb = new StringBuilder(ex.getClass().getSimpleName());
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
                break;
            }
        }
        String text = sb.toString();
        if (e.secret() != null && !e.secret().isEmpty()) text = text.replace(e.secret(), "[token]");
        if (e.body() != null && !e.body().isEmpty()) text = text.replace(e.body(), "[body]");
        text = text.replaceAll("[\\r\\n]+", " ");
        return text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
    }
}

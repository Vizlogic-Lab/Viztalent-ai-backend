package com.smartstaff.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** F17: every 15 minutes, reminds candidates whose emailed invite expires
 *  within 24 hours and is still unused. Off in tests, which call the service. */
@Component
public class EmailReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailReminderScheduler.class);

    private final EmailService emails;
    private final boolean enabled;

    public EmailReminderScheduler(EmailService emails, @Value("${app.email.reminders-enabled:true}") boolean enabled) {
        this.emails = emails;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.email.reminder-interval-ms:900000}",
               initialDelayString = "${app.email.reminder-interval-ms:900000}")
    public void remind() {
        if (!enabled) return;
        try {
            int n = emails.sendReminders(Instant.now());
            if (n > 0) log.info("Queued {} invite reminder email(s)", n);
        } catch (Exception e) {
            log.error("Invite reminder sweep failed", e);
        }
    }
}

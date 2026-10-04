package com.smartstaff.service;

import java.util.UUID;

/** F17: an email to send after the current transaction commits. The body (and
 *  secret, the raw invite token, kept only to scrub it from error text) live in
 *  memory only — never in the database or the log. */
public record EmailRequestedEvent(UUID emailId, String to, String subject, String body, String secret) {

    @Override
    public String toString() {
        return "EmailRequestedEvent[" + emailId + "]";
    }
}

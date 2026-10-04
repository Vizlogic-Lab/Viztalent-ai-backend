package com.smartstaff.service.impl;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/** F17: one plain-text email over the SMTP server saved in Settings. Built per
 *  send so a Settings change applies at once; bounded timeouts so a dead server
 *  can't hang the email thread. Throws MailException on failure. */
@Component
public class EmailSender {

    private static final String TIMEOUT_MS = "10000";

    public void send(EmailSettingsService.Smtp smtp, String to, String subject, String body) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setDefaultEncoding("UTF-8");
        boolean auth = smtp.username() != null && !smtp.username().isBlank();
        if (auth) {
            sender.setUsername(smtp.username());
            sender.setPassword(smtp.password());
        }
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(auth));
        p.put("mail.smtp.starttls.enable", String.valueOf(smtp.tls()));
        p.put("mail.smtp.starttls.required", String.valueOf(smtp.tls()));
        p.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        p.put("mail.smtp.timeout", TIMEOUT_MS);
        p.put("mail.smtp.writetimeout", TIMEOUT_MS);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(smtp.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}

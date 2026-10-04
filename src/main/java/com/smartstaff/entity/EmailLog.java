package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** F17: one email the backend sent or tried to send. Never holds the body or
 *  the invite link — only who, which template and how it went. */
@Entity
@Table(name = "email_log")
@Getter
@Setter
@NoArgsConstructor
public class EmailLog {

    public static final String QUEUED = "QUEUED", SENT = "SENT", FAILED = "FAILED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "invite_id")
    private UUID inviteId;

    @Column(name = "to_address", nullable = false, length = 320)
    private String toAddress;

    @Column(name = "candidate_name")
    private String candidateName;

    /** An EmailTemplates.Kind name. */
    @Column(nullable = false, length = 32)
    private String template;

    @Column(nullable = false, length = 16)
    private String status = QUEUED;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;
}

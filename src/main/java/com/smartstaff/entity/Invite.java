package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Single-use, expiring candidate-facing link. Only the SHA-256 hash of the
 *  raw token is persisted (tokenHash) — see V6__assessments.sql. */
@Entity
@Table(name = "invites")
@Getter
@Setter
@NoArgsConstructor
public class Invite {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false, length = 16)
    private String kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    /** Only set for kind=INTERVIEW — the pre-generated interview /api/interview/by_token
     *  should serve. Null for kind=ASSESSMENT invites (those carry levels instead). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_id")
    private Interview interview;

    @Column(name = "candidate_email", nullable = false)
    private String candidateEmail;

    @Column(name = "candidate_name")
    private String candidateName;

    /** Assessment levels this link covers. The column is NOT NULL (default '[]'), and
     *  Hibernate writes an explicit NULL for an unset field rather than letting the
     *  default apply — so this must never be null. Interview invites have no levels
     *  and simply keep the empty list (their first save used to fail with a 500). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> levels = new ArrayList<>();

    @Column(nullable = false)
    private boolean combined;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

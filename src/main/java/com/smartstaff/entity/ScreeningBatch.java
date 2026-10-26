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

/** One shared assessment link for a screened group of candidates of a job. The
 *  token only opens the email-and-code entry page; each candidate then gets a
 *  normal per-candidate Invite against the job's single shared assessment. */
@Entity
@Table(name = "screening_batches")
@Getter
@Setter
@NoArgsConstructor
public class ScreeningBatch {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    @Column(nullable = false, unique = true)
    private String token;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> levels = new ArrayList<>();

    @Column(name = "min_score", nullable = false)
    private int minScore;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BatchCandidate> candidates = new ArrayList<>();

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

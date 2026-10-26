package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A candidate on a batch's roster, with the score they had when the batch was
 *  made, and the invite they got once they entered. */
@Entity
@Table(name = "batch_candidates")
@Getter
@Setter
@NoArgsConstructor
public class BatchCandidate {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private ScreeningBatch batch;

    @Column(name = "candidate_name")
    private String candidateName;

    @Column(nullable = false)
    private String email;

    @Column(name = "resume_id")
    private UUID resumeId;

    @Column(name = "fit_score", nullable = false)
    private int fitScore;

    /** Scored at or above the batch's minimum; only these may enter. */
    @Column(nullable = false)
    private boolean included;

    /** The per-candidate invite minted the first time they entered. */
    @Column(name = "invite_id")
    private UUID inviteId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** F11: a reviewer assigned to a job. */
@Entity
@Table(name = "job_reviewers")
@IdClass(JobReviewer.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class JobReviewer {

    public record Key(UUID jobId, UUID userId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    public JobReviewer(UUID jobId, UUID userId, String assignedBy) {
        this.jobId = jobId;
        this.userId = userId;
        this.assignedBy = assignedBy;
    }
}

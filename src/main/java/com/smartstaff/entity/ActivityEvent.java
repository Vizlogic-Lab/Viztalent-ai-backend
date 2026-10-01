package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** F14: one entry in a job's activity feed (append-only). */
@Entity
@Table(name = "activity_events")
@Getter
@Setter
@NoArgsConstructor
public class ActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    /** The assessment, invite or attempt the event is about. */
    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(nullable = false)
    private Instant at = Instant.now();

    public ActivityEvent(UUID jobId, String kind, String message, UUID subjectId) {
        this.jobId = jobId;
        this.kind = kind;
        this.message = message;
        this.subjectId = subjectId;
    }
}

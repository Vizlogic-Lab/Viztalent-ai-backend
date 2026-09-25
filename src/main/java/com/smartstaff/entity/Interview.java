package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One AI-conducted L1 interview — self-service link, in-app browser
 *  session, or (Phase 8) an outbound Twilio call. Created PENDING by
 *  InterviewServiceImpl.prepare(...) with its questions already generated;
 *  moves to COMPLETED once a transcript is saved (save / save_by_token). */
@Entity
@Table(name = "interviews")
@Getter
@Setter
@NoArgsConstructor
public class Interview {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id")
    private Candidate candidate;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(nullable = false, length = 16)
    private String language = "en-IN";

    @Column(name = "role_title")
    private String roleTitle;

    @Column(name = "candidate_name")
    private String candidateName;

    private String phone;

    @Column(columnDefinition = "TEXT")
    private String intro;

    @Column(columnDefinition = "TEXT")
    private String outro;

    @Column(name = "twilio_call_sid")
    private String twilioCallSid;

    /** Twilio's own call status string (queued/ringing/in-progress/completed/
     *  busy/no-answer/failed/canceled) — finer-grained than `status` above,
     *  see V8__interview_calls.sql. */
    @Column(name = "twilio_call_status")
    private String twilioCallStatus;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

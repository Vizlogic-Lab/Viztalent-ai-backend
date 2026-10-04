package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    // ── F16: grading (separate from the call's own status) ──────────────

    /** EVALUATING / EVALUATED / AWAITING_REVIEW / VALIDATED; null = not graded yet. */
    @Column(name = "evaluation_status", length = 16)
    private String evaluationStatus;

    @Column(name = "theory_score", precision = 5, scale = 2)
    private BigDecimal theoryScore;

    @Column(name = "hands_on_score", precision = 5, scale = 2)
    private BigDecimal handsOnScore;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "theory_band", length = 20)
    private String theoryBand;

    @Column(name = "hands_on_band", length = 20)
    private String handsOnBand;

    @Column(length = 24)
    private String recommendation;

    @Column(name = "engine_recommendation", length = 24)
    private String engineRecommendation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> flags = new ArrayList<>();

    @Column(name = "validation_reason", length = 32)
    private String validationReason;

    @Column(name = "evaluation_started_at")
    private Instant evaluationStartedAt;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @Column(name = "rubric_pending_since")
    private Instant rubricPendingSince;

    @Column(name = "next_evaluation_at")
    private Instant nextEvaluationAt;

    @Column(name = "evaluation_tries", nullable = false)
    private int evaluationTries;
}

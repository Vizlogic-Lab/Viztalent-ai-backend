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

/** One candidate's run at ONE level of an assessment version. A combined
 *  invite (levels L1,L2) gets one attempt per level, served in order; the
 *  invite is consumed only when its last level is submitted, so the candidate
 *  can close and reopen the page until then. Email/name are snapshotted from
 *  the invite; resumeId links the screened candidate when the email matched. */
@Entity
@Table(name = "assessment_attempts")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentAttempt {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invite_id", nullable = false)
    private Invite invite;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    /** The assessment version this attempt is graded against. */
    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 8)
    private String level;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "resume_id")
    private UUID resumeId;

    @Column(name = "candidate_email", nullable = false, length = 320)
    private String candidateEmail;

    @Column(name = "candidate_name")
    private String candidateName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** The deadline passed before the candidate pressed submit. */
    @Column(name = "auto_submitted", nullable = false)
    private boolean autoSubmitted;

    @Column(name = "theory_score", precision = 5, scale = 2)
    private BigDecimal theoryScore;

    @Column(name = "hands_on_score", precision = 5, scale = 2)
    private BigDecimal handsOnScore;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(length = 24)
    private String recommendation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> flags = new ArrayList<>();

    @Column(name = "evaluation_started_at")
    private Instant evaluationStartedAt;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    /** When a rubric first couldn't be graded (Gemini down); retries stop an hour later. */
    @Column(name = "rubric_pending_since")
    private Instant rubricPendingSince;

    @Column(name = "next_evaluation_at")
    private Instant nextEvaluationAt;

    @Column(name = "evaluation_tries", nullable = false)
    private int evaluationTries;

    @OneToMany(mappedBy = "attempt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AssessmentAnswer> answers = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

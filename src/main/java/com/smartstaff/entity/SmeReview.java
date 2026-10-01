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
import java.util.Map;
import java.util.UUID;

/** F12: one expert review — of a question set (an assessment version) or of one attempt's result. */
@Entity
@Table(name = "sme_reviews")
@Getter
@Setter
@NoArgsConstructor
public class SmeReview {

    public enum Subject { QUESTION_SET, ATTEMPT }

    public enum Status {
        PENDING, IN_REVIEW, APPROVED, ADJUSTED, RETURNED;

        public boolean isClosed() {
            return this == APPROVED || this == ADJUSTED || this == RETURNED;
        }
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private Subject subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    /** [{question_id, auto_score, sme_score, reason}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> adjustments = new ArrayList<>();

    @Column(name = "final_theory_band", length = 20)
    private String finalTheoryBand;

    @Column(name = "final_hands_on_band", length = 20)
    private String finalHandsOnBand;

    @Column(name = "final_recommendation", length = 24)
    private String finalRecommendation;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "signed_off_at")
    private Instant signedOffAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public SmeReview(Subject subjectType, UUID subjectId, UUID jobId, Instant dueAt) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.jobId = jobId;
        this.dueAt = dueAt;
    }
}

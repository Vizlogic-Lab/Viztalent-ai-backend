package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The automatic grade of one attempt. One per attempt; produced in the
 *  background after submit and re-creatable on demand. `needsReview` is true
 *  when some AI-graded part (CODE_WRITE rubric, SCENARIO) couldn't be graded
 *  (Gemini down) — those `reviewPoints` await a human, and `passed` is
 *  provisional until then. */
@Entity
@Table(name = "assessment_scorecards")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentScorecard {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false, unique = true)
    private AssessmentAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ScorecardStatus status = ScorecardStatus.PENDING;

    @Column(name = "total_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal totalScore = BigDecimal.ZERO;

    @Column(name = "max_score", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxScore = BigDecimal.ZERO;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percent = BigDecimal.ZERO;

    @Column(name = "pass_threshold", nullable = false)
    private int passThreshold = 50;

    @Column(nullable = false)
    private boolean passed;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "review_points", nullable = false, precision = 8, scale = 2)
    private BigDecimal reviewPoints = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String error;

    @OneToMany(mappedBy = "scorecard", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("level ASC, seq ASC")
    private List<AssessmentQuestionScore> questionScores = new ArrayList<>();

    @Column(name = "scored_at")
    private Instant scoredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

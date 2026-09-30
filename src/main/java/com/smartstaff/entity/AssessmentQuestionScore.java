package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The grade of one question within a scorecard. `score` is the points awarded
 *  out of `maxPoints`. For coding questions `testsPassed`/`testsTotal` cover all
 *  tests (visible and hidden); `breakdown` holds the AI rubric or key-point
 *  detail. `needsReview` marks a part a human still has to grade. */
@Entity
@Table(name = "assessment_question_scores")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentQuestionScore {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scorecard_id", nullable = false)
    private AssessmentScorecard scorecard;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private AssessmentQuestion question;

    @Column(nullable = false, length = 8)
    private String level;

    private int seq;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "max_points", nullable = false)
    private int maxPoints;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal score = BigDecimal.ZERO;

    @Column(name = "auto_graded", nullable = false)
    private boolean autoGraded = true;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(nullable = false)
    private boolean answered;

    @Column(name = "tests_passed")
    private Integer testsPassed;

    @Column(name = "tests_total")
    private Integer testsTotal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<QuestionScoreBreakdown> breakdown = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String detail;
}

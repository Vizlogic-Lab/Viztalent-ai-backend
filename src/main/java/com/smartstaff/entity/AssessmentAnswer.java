package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** A candidate's answer to one question of an attempt (table attempt_answers).
 *  Only the fields for the question's type are set: selectedIndices for
 *  MCQ/MSQ, code+language for the coding types, textAnswer for
 *  SCENARIO/LOGIC/CODE_OUTPUT. questionSnapshot is the question exactly as the
 *  candidate was shown it. The score fields are written by evaluation. */
@Entity
@Table(name = "attempt_answers")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentAnswer {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    private AssessmentAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private AssessmentQuestion question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "question_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> questionSnapshot = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selected_indices", nullable = false, columnDefinition = "jsonb")
    private List<Integer> selectedIndices = new ArrayList<>();

    @Column(length = 32)
    private String language;

    @Column(columnDefinition = "TEXT")
    private String code;

    @Column(name = "text_answer", columnDefinition = "TEXT")
    private String textAnswer;

    @Column(name = "auto_score", precision = 8, scale = 2)
    private BigDecimal autoScore;

    /** Every rubric run (evaluation double-runs the grader). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rubric_score", columnDefinition = "jsonb")
    private List<Map<String, Object>> rubricScore;

    @Column(name = "final_score", precision = 8, scale = 2)
    private BigDecimal finalScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> evidence = new ArrayList<>();

    /** Per-question evaluation flags: HARDCODED, GRADER_DISAGREEMENT, RUBRIC_PENDING, RUBRIC_UNAVAILABLE. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> flags = new ArrayList<>();

    /** F12: the SME's adjusted score (null = not adjusted) and why. */
    @Column(name = "sme_score", precision = 6, scale = 2)
    private BigDecimal smeScore;

    @Column(name = "sme_reason", columnDefinition = "TEXT")
    private String smeReason;

    /** F15: the engine's own final score; never changed by an SME. */
    @Column(name = "engine_score", precision = 8, scale = 2)
    private BigDecimal engineScore;

    /** F15: optional per-criterion SME scores given with an adjustment (criterion → score). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sme_criteria", columnDefinition = "jsonb")
    private Map<String, BigDecimal> smeCriteria;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}

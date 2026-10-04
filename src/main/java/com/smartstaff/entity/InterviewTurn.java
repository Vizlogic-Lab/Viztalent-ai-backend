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
import java.util.Map;
import java.util.UUID;

/** One question (+ eventual answer) within an Interview, in `seq` order.
 *  Created empty-answered at prepare time; save/saveByToken replace every
 *  turn for the interview wholesale rather than patching by index — same
 *  "delete and recreate" pattern ScreeningServiceImpl and
 *  AssessmentServiceImpl already use elsewhere in this codebase. */
@Entity
@Table(name = "interview_turns")
@Getter
@Setter
@NoArgsConstructor
public class InterviewTurn {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_id", nullable = false)
    private Interview interview;

    private int seq;

    private String category;

    private String skill;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer = "";

    /** F16: RECRUITER (the job's own question) or AI. */
    @Column(nullable = false, length = 16)
    private String source = "AI";

    /** F16: practical prompts are HANDS_ON. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Dimension dimension = Dimension.THEORY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_points", nullable = false, columnDefinition = "jsonb")
    private List<String> expectedPoints = new ArrayList<>();

    /** The engine's score (0/1/2 per expected point) and its maximum. */
    @Column(precision = 6, scale = 2)
    private BigDecimal score;

    @Column(name = "max_score", precision = 6, scale = 2)
    private BigDecimal maxScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> evidence = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rubric_runs", columnDefinition = "jsonb")
    private List<Map<String, Object>> rubricRuns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> flags = new ArrayList<>();

    /** The SME's adjusted score (wins over score) and why. */
    @Column(name = "sme_score", precision = 6, scale = 2)
    private BigDecimal smeScore;

    @Column(name = "sme_reason", columnDefinition = "TEXT")
    private String smeReason;

    /** Twilio's speech-recognition confidence for a phone answer (0..1). */
    @Column(name = "speech_confidence", precision = 5, scale = 4)
    private BigDecimal speechConfidence;

    public BigDecimal finalScore() {
        return smeScore != null ? smeScore : score;
    }
}

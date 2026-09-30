package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

/** One question of one assessment version, of any type. Answers, reference
 *  solutions, hidden tests and the rubric never go to candidates; see
 *  dto/response/CandidateQuestionView for what does. */
@Entity
@Table(name = "assessment_questions")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentQuestion {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    @Column(nullable = false, length = 8)
    private String level;

    /** Order within the level. */
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuestionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Dimension dimension;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Competency competency;

    private int points;

    @Column(name = "time_estimate_sec")
    private Integer timeEstimateSec;

    /** Statement shown to the candidate. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> options = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correct_indices", columnDefinition = "jsonb")
    private List<Integer> correctIndices = new ArrayList<>();

    private String skill;

    @Column(length = 16)
    private String difficulty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> languages = new ArrayList<>();

    /** language -> code shown in the editor (for CODE_DEBUG, the buggy code). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "starter_code", columnDefinition = "jsonb")
    private Map<String, String> starterCode = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_solution", columnDefinition = "jsonb")
    private Map<String, String> referenceSolution = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "buggy_code", columnDefinition = "jsonb")
    private Map<String, String> buggyCode = new LinkedHashMap<>();

    @Column(name = "model_answer", columnDefinition = "TEXT")
    private String modelAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_points", columnDefinition = "jsonb")
    private List<String> keyPoints = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<RubricCriterion> rubric = new ArrayList<>();

    private boolean validated;

    @Column(name = "validation_log", columnDefinition = "TEXT")
    private String validationLog;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<QuestionTestCase> testCases = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Also defaults dimension and competency from the type when unset. */
    public void setType(QuestionType type) {
        this.type = type;
        if (dimension == null) dimension = type.dimension();
        if (competency == null) competency = type.competency();
    }

    public void addTestCase(QuestionTestCase testCase) {
        testCase.setQuestion(this);
        testCase.setSeq(testCases.size());
        testCases.add(testCase);
    }
}

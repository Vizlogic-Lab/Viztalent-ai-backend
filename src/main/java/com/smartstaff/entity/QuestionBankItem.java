package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

/** A curated question HR uploaded, of any QuestionType, with the same
 *  practical fields as AssessmentQuestion. Only `validated` items (proven on
 *  upload, coding ones in the sandbox) are used by generation. Empty
 *  role_families means "any role family". */
@Entity
@Table(name = "question_bank_items")
@Getter
@Setter
@NoArgsConstructor
public class QuestionBankItem {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_id", nullable = false)
    private QuestionBankUpload upload;

    /** A QuestionType name. */
    @Column(nullable = false, length = 16)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Dimension dimension;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Competency competency;

    @Column(length = 8)
    private String level;

    private String skill;

    @Column(length = 16)
    private String difficulty;

    private Integer points;

    @Column(name = "time_estimate_sec")
    private Integer timeEstimateSec;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(columnDefinition = "TEXT")
    private String constraints;

    @Column(name = "input_format", columnDefinition = "TEXT")
    private String inputFormat;

    @Column(name = "output_format", columnDefinition = "TEXT")
    private String outputFormat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> options = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correct_indices", columnDefinition = "jsonb")
    private List<Integer> correctIndices = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> languages = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "starter_code", columnDefinition = "jsonb")
    private Map<String, String> starterCode = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_solution", columnDefinition = "jsonb")
    private Map<String, String> referenceSolution = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "naive_solution", columnDefinition = "jsonb")
    private Map<String, String> naiveSolution = new LinkedHashMap<>();

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

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "expected_complexity", length = 64)
    private String expectedComplexity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bug_descriptions", columnDefinition = "jsonb")
    private List<String> bugDescriptions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "role_families", columnDefinition = "jsonb")
    private List<String> roleFamilies = new ArrayList<>();

    private boolean validated;

    @Column(name = "validation_log", columnDefinition = "TEXT")
    private String validationLog;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<QuestionBankTestCase> testCases = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

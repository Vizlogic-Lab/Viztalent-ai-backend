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

/** One execution of a candidate's code: RUN (the Run button, visible tests
 *  only — capped per question) or SUBMIT (evaluation, all tests). */
@Entity
@Table(name = "code_runs")
@Getter
@Setter
@NoArgsConstructor
public class CodeRun {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id", nullable = false)
    private AssessmentAnswer answer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CodeRunMode mode;

    @Column(length = 32)
    private String language;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> results = new ArrayList<>();

    @Column(name = "compile_error", columnDefinition = "TEXT")
    private String compileError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

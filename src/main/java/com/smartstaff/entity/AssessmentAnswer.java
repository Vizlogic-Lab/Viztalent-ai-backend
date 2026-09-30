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
import java.util.UUID;

/** A candidate's answer to one question of an attempt. Only the fields for the
 *  question's type are set: selectedIndices for MCQ/MSQ, code+language for the
 *  coding types, textAnswer for SCENARIO/LOGIC/CODE_OUTPUT. */
@Entity
@Table(name = "assessment_answers")
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
    @Column(name = "selected_indices", nullable = false, columnDefinition = "jsonb")
    private List<Integer> selectedIndices = new ArrayList<>();

    @Column(name = "code_language", length = 32)
    private String codeLanguage;

    @Column(columnDefinition = "TEXT")
    private String code;

    @Column(name = "text_answer", columnDefinition = "TEXT")
    private String textAnswer;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}

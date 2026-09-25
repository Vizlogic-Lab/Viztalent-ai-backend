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

/** type: MCQ | MSQ | DESCRIPTIVE | CODING. MCQ stores exactly one index in
 *  correctIndices; MSQ can store several. DESCRIPTIVE/CODING typically have
 *  empty options/correctIndices — see QuestionBankFileParser. */
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

    @Column(nullable = false, length = 16)
    private String type;

    @Column(length = 8)
    private String level;

    private String skill;

    @Column(length = 16)
    private String difficulty;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> options = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correct_indices", columnDefinition = "jsonb")
    private List<Integer> correctIndices = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

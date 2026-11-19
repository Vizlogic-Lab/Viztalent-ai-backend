package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A question saved to the company's question book (question_book_items). The content is
 *  the question's request body (see CustomQuestionRequest), so picking it copies the
 *  question through the same validation as a hand-written one. */
@Entity
@Table(name = "question_book_items")
@Getter
@Setter
@NoArgsConstructor
public class QuestionBookItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuestionType type;

    @Column(length = 120)
    private String skill;

    @Column(length = 8)
    private String difficulty;

    @Column(nullable = false)
    private int marks;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "prompt_hash", nullable = false, unique = true, length = 64)
    private String promptHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> content = new LinkedHashMap<>();

    @Column(nullable = false, length = 8)
    private String origin;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "usage_count", nullable = false)
    private int usageCount;
}

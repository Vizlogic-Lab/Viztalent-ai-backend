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

/** F16: a recruiter's own L1 interview question for a job. Asked first, in seq
 *  order; expected_points are what a good answer covers (the AI writes them
 *  when HR leaves them empty). */
@Entity
@Table(name = "job_interview_questions")
@Getter
@Setter
@NoArgsConstructor
public class JobInterviewQuestion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    private int seq;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(length = 64)
    private String category;

    @Column(length = 128)
    private String skill;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_points", nullable = false, columnDefinition = "jsonb")
    private List<String> expectedPoints = new ArrayList<>();

    /** HR or AI. */
    @Column(name = "expected_points_source", length = 8)
    private String expectedPointsSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Dimension dimension = Dimension.THEORY;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}

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

/** One version of a job's question set. Regenerating adds a new version and
 *  moves is_current to it; older versions are kept so past attempts stay
 *  gradable against the questions the candidate actually saw. */
@Entity
@Table(name = "assessments")
@Getter
@Setter
@NoArgsConstructor
public class Assessment {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 16)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssessmentStatus status = AssessmentStatus.QUEUED;

    /** Level ("L1".."L3") -> blueprint. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Blueprint> blueprint = new LinkedHashMap<>();

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

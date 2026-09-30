package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job role profile: AI-extracted or HR-edited skill expectations for a job.
 * Captures role family, technical nature, preferred languages/frameworks, seniority, and skill weights.
 */
@Entity
@Table(name = "job_role_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JobRoleProfile {

    public enum RoleFamily {
        BACKEND, FRONTEND, FULLSTACK, DATA, DEVOPS, QA, MOBILE, NON_TECHNICAL
    }

    public enum SeniorityLevel {
        JUNIOR, MID, SENIOR, LEAD
    }

    public enum ProfileSource {
        AI, AI_FALLBACK, HR
    }

    @Id
    @Column(name = "job_id", columnDefinition = "UUID")
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_family", nullable = false)
    private RoleFamily roleFamily;

    @Column(name = "is_technical", nullable = false)
    private Boolean isTechnical;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "languages")
    private List<String> languages;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "frameworks")
    private List<String> frameworks;

    @Enumerated(EnumType.STRING)
    @Column(name = "seniority", nullable = false)
    private SeniorityLevel seniority;

    /**
     * Skill -> weight (1-5). Only skills present in the job description are included
     * unless edited by HR.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_weights", nullable = false, columnDefinition = "jsonb")
    private Map<String, Integer> skillWeights;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private ProfileSource source;

    @Column(name = "edited_by", columnDefinition = "UUID")
    private UUID editedBy;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}

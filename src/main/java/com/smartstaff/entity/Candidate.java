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

/** One row per screened resume for a job — produced by running screening
 *  (POST /api/run_screening), not by uploading a resume. Re-running
 *  screening replaces every Candidate row for that job (see
 *  ScreeningServiceImpl.runScreening). Pipeline stage/notes are NOT stored
 *  here — the frontend still keeps those in localStorage (see
 *  backend-classic open question #2 in docs/FEATURES.md). */
@Entity
@Table(name = "candidates")
@Getter
@Setter
@NoArgsConstructor
public class Candidate {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    @Column(name = "candidate_name")
    private String candidateName;

    private String email;

    private String phone;

    @Column(name = "years_experience")
    private int yearsExperience;

    @Column(name = "fit_score")
    private int fitScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_skills", columnDefinition = "jsonb")
    private List<String> matchedSkills = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_skills", columnDefinition = "jsonb")
    private List<String> missingSkills = new ArrayList<>();

    @Column(name = "matched_required_count")
    private int matchedRequiredCount;

    @Column(name = "total_required_count")
    private int totalRequiredCount;

    @Column(name = "score_breakdown", columnDefinition = "TEXT")
    private String scoreBreakdown;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}

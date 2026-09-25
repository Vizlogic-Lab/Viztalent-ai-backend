package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue
    private UUID id;

    // Populated by the DB sequence (jd_number_seq) at insert time — @Generated
    // tells Hibernate to SELECT it back immediately after the INSERT so the
    // in-memory entity has it without a manual refresh.
    @Generated(event = EventType.INSERT)
    @Column(name = "jd_number", insertable = false, updatable = false)
    private Long jdNumber;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "jd_text", columnDefinition = "TEXT")
    private String jdText;

    @Column(name = "content_hash")
    private String contentHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "must_have_skills", columnDefinition = "jsonb")
    private List<String> mustHaveSkills = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nice_to_have_skills", columnDefinition = "jsonb")
    private List<String> niceToHaveSkills = new ArrayList<>();

    @Column(name = "experience_min_years")
    private Integer experienceMinYears;

    @Column(name = "experience_max_years")
    private Integer experienceMaxYears;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "owner_email")
    private String ownerEmail;

    @Column(name = "owner_role")
    private String ownerRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Resume> resumes = new ArrayList<>();

    public String jdNumberDisplay() {
        return jdNumber == null ? null : String.format("JD-%04d", jdNumber);
    }

    public List<String> allSkills() {
        List<String> all = new ArrayList<>(mustHaveSkills);
        all.addAll(niceToHaveSkills);
        return all;
    }
}

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

/** One row per account, admin or employee — matches the frontend's single
 *  "accounts" pool (GET /api/auth/accounts returns {admins:[], users:[]},
 *  split by role from this one table). */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(nullable = false)
    private String name;

    /** Admin / reviewer sign-in identifier. */
    private String email;

    /** Employee sign-in identifier. Null for ADMIN rows. */
    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status = AccountStatus.APPROVED;

    /** REVIEWER only: skills the SME can judge (matched against role-profile skill weights). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reviewer_skills", nullable = false, columnDefinition = "jsonb")
    private List<String> reviewerSkills = new ArrayList<>();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Set for the bootstrap admin: until they change the password, only /api/auth/me,
     *  /api/auth/change_password and /api/auth/logout are allowed (JwtAuthFilter). */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    public User(Role role, String name, String email, String employeeId, String passwordHash, AccountStatus status) {
        this.role = role;
        this.name = name;
        this.email = email;
        this.employeeId = employeeId;
        this.passwordHash = passwordHash;
        this.status = status;
    }

    /** The value the frontend treats as this account's opaque "id" (and what
     *  jobs.owner_id stores — see JobMapper/Job). */
    public String publicId() {
        return role == Role.USER ? employeeId : email;
    }
}

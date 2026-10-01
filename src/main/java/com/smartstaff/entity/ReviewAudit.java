package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** F12: one action on an SME review (append-only). */
@Entity
@Table(name = "review_audit")
@Getter
@Setter
@NoArgsConstructor
public class ReviewAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 32)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> before;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> after;

    @Column(nullable = false)
    private Instant at = Instant.now();

    public ReviewAudit(UUID reviewId, UUID actorId, String action, Map<String, Object> before, Map<String, Object> after) {
        this.reviewId = reviewId;
        this.actorId = actorId;
        this.action = action;
        this.before = before;
        this.after = after;
    }
}

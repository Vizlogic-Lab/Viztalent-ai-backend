package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "question_bank_uploads")
@Getter
@Setter
@NoArgsConstructor
public class QuestionBankUpload {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String filename;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public QuestionBankUpload(String filename, String uploadedBy, int itemCount) {
        this.filename = filename;
        this.uploadedBy = uploadedBy;
        this.itemCount = itemCount;
    }
}

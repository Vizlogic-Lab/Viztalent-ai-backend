package com.smartstaff.repository;

import com.smartstaff.entity.ReviewAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewAuditRepository extends JpaRepository<ReviewAudit, Long> {

    List<ReviewAudit> findByReviewIdOrderByIdAsc(UUID reviewId);
}

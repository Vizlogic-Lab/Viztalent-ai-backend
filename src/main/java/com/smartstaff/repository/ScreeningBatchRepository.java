package com.smartstaff.repository;

import com.smartstaff.entity.ScreeningBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreeningBatchRepository extends JpaRepository<ScreeningBatch, UUID> {

    Optional<ScreeningBatch> findByToken(String token);

    List<ScreeningBatch> findByJobIdAndExpiresAtAfterOrderByCreatedAtDesc(UUID jobId, Instant now);
}

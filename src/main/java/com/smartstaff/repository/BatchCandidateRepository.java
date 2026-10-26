package com.smartstaff.repository;

import com.smartstaff.entity.BatchCandidate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BatchCandidateRepository extends JpaRepository<BatchCandidate, UUID> {

    Optional<BatchCandidate> findByBatchIdAndEmailIgnoreCase(UUID batchId, String email);

    /** Same lookup, row-locked: code sends and checks for one candidate run one at a time, so parallel
     *  requests can't mint two invites or get past the wrong-code limit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BatchCandidate c where c.batch.id = :batchId and lower(c.email) = lower(:email)")
    Optional<BatchCandidate> lockByBatchIdAndEmail(@Param("batchId") UUID batchId, @Param("email") String email);
}

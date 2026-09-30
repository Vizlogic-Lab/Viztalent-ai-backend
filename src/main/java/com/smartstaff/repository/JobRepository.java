package com.smartstaff.repository;

import com.smartstaff.entity.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /** Just the owner column — the per-request authorization check
     *  (JobAccessGuard) shouldn't load a whole Job (JD text, jsonb skills). */
    @Query("select j.ownerId from Job j where j.id = :id")
    Optional<String> findOwnerIdById(@Param("id") UUID id);

    List<Job> findAllByOrderByCreatedAtDesc();

    List<Job> findByOwnerIdIgnoreCaseOrderByCreatedAtDesc(String ownerId);

    Optional<Job> findByOwnerIdIgnoreCaseAndContentHash(String ownerId, String contentHash);

    List<Job> findAllByOrderByCreatedAtAsc();

    /** Row lock that serialises assessment version changes for one job. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.id = :id")
    Optional<Job> lockById(@Param("id") UUID id);
}

package com.smartstaff.repository;

import com.smartstaff.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    List<Candidate> findByJobIdOrderByFitScoreDesc(UUID jobId);

    /** Interview prep looks the candidate up by (job, resume filename) — the
     *  frontend only ever sends file_name, not a candidate id. */
    Optional<Candidate> findByJobIdAndResumeFilename(UUID jobId, String filename);

    /** Re-screening deletes a job's candidates and immediately re-inserts them
     *  (one per resume, unique on resume_id) inside one transaction.
     *
     *  This is a bulk DELETE on purpose. The derived `deleteBy...` form loads
     *  each row and queues `em.remove(...)`, and Hibernate flushes queued
     *  INSERTs *before* queued DELETEs — so the re-inserted rows collided with
     *  the not-yet-deleted ones on the unique resume_id and the second
     *  screening of any job failed with a 500. A bulk delete runs as SQL right
     *  away (flushAutomatically first, so nothing pending is lost). */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("delete from Candidate c where c.job.id = :jobId")
    void deleteByJobId(@Param("jobId") UUID jobId);
}

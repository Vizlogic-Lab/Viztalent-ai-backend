package com.smartstaff.repository;

import com.smartstaff.entity.Assessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {

    Optional<Assessment> findByJobIdAndCurrentTrue(UUID jobId);

    Optional<Assessment> findByJobIdAndVersion(UUID jobId, int version);

    Optional<Assessment> findTopByJobIdOrderByVersionDesc(UUID jobId);

    List<Assessment> findByJobIdOrderByVersionDesc(UUID jobId);

    @Query("select coalesce(max(a.version), 0) from Assessment a where a.job.id = :jobId")
    int maxVersion(@Param("jobId") UUID jobId);

    /** Executes immediately rather than at flush, so the one-current-per-job
     *  index isn't violated when the new version is inserted afterwards
     *  (Hibernate flushes inserts before updates). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Assessment a set a.current = false where a.job.id = :jobId and a.current = true")
    int clearCurrent(@Param("jobId") UUID jobId);

    @Modifying
    @Query("update Assessment a set a.status = com.smartstaff.entity.AssessmentStatus.FAILED, a.error = :error "
            + "where a.status in (com.smartstaff.entity.AssessmentStatus.QUEUED, "
            + "com.smartstaff.entity.AssessmentStatus.GENERATING, com.smartstaff.entity.AssessmentStatus.VALIDATING)")
    int failInProgress(@Param("error") String error);
}

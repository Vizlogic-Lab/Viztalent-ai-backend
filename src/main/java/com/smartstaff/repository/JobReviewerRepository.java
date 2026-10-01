package com.smartstaff.repository;

import com.smartstaff.entity.JobReviewer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JobReviewerRepository extends JpaRepository<JobReviewer, JobReviewer.Key> {

    List<JobReviewer> findByJobIdOrderByAssignedAtAsc(UUID jobId);

    List<JobReviewer> findByUserId(UUID userId);

    boolean existsByJobIdAndUserId(UUID jobId, UUID userId);

    boolean existsByJobId(UUID jobId);

    @Modifying
    @Query("delete from JobReviewer r where r.jobId = :jobId")
    void deleteByJobId(@Param("jobId") UUID jobId);
}

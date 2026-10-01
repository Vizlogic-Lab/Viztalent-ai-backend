package com.smartstaff.repository;

import com.smartstaff.entity.SmeReview;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmeReviewRepository extends JpaRepository<SmeReview, UUID> {

    Optional<SmeReview> findBySubjectTypeAndSubjectId(SmeReview.Subject type, UUID subjectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SmeReview r where r.id = :id")
    Optional<SmeReview> lockById(@Param("id") UUID id);

    /** Open reviews, oldest due first. */
    @Query("select r from SmeReview r where r.status in :statuses order by r.dueAt asc nulls last, r.createdAt asc")
    List<SmeReview> findOpen(@Param("statuses") Collection<SmeReview.Status> statuses);

    @Query("select r from SmeReview r where r.jobId in :jobs and r.status in :statuses "
            + "order by r.dueAt asc nulls last, r.createdAt asc")
    List<SmeReview> findOpenForJobs(@Param("jobs") Collection<UUID> jobs,
                                    @Param("statuses") Collection<SmeReview.Status> statuses);

    @Query("select r from SmeReview r where r.status in :statuses and r.dueAt < :now order by r.dueAt asc")
    List<SmeReview> findOverdue(@Param("statuses") Collection<SmeReview.Status> statuses, @Param("now") Instant now);
}

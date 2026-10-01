package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentAttempt;
import com.smartstaff.entity.AttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, UUID> {

    /** All level-attempts of one invite. */
    List<AssessmentAttempt> findByInviteIdOrderByStartedAtAsc(UUID inviteId);

    List<AssessmentAttempt> findByJobIdOrderByStartedAtDesc(UUID jobId);

    long countByJobIdAndStatus(UUID jobId, AttemptStatus status);

    /** A version that anyone has opened is frozen: its questions can't be edited or regenerated. */
    boolean existsByAssessmentId(UUID assessmentId);

    /** Atomic IN_PROGRESS → SUBMITTED. Returns 1 for the one call that wins; a
     *  concurrent second submit blocks on the row lock, then sees 0. Doesn't
     *  clear the persistence context (the caller mirrors the change in memory),
     *  so already-loaded invites/jobs stay usable in the same transaction. */
    @Modifying(flushAutomatically = true)
    @Query("update AssessmentAttempt a set a.status = com.smartstaff.entity.AttemptStatus.SUBMITTED, "
            + "a.submittedAt = :at, a.autoSubmitted = :auto "
            + "where a.id = :id and a.status = com.smartstaff.entity.AttemptStatus.IN_PROGRESS")
    int markSubmitted(@Param("id") UUID id, @Param("at") Instant at, @Param("auto") boolean auto);

    /** In-progress attempts whose deadline has passed (the auto-submit scheduler). */
    @Query("select a.id from AssessmentAttempt a "
            + "where a.status = com.smartstaff.entity.AttemptStatus.IN_PROGRESS and a.deadlineAt < :now")
    List<UUID> findOverdueIds(@Param("now") Instant now);
}

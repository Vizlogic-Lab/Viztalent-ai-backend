package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentAttempt;
import com.smartstaff.entity.AttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, UUID> {

    /** All level-attempts of one invite. */
    List<AssessmentAttempt> findByInviteIdOrderByStartedAtAsc(UUID inviteId);

    List<AssessmentAttempt> findByJobIdOrderByStartedAtDesc(UUID jobId);

    /** Every attempt across the given jobs (GET /api/assessment/submissions_all). */
    List<AssessmentAttempt> findByJobIdInOrderByStartedAtDesc(java.util.Collection<UUID> jobIds);

    /** Owner of the attempt's job (JobAccessGuard). */
    @Query("select a.job.ownerId from AssessmentAttempt a where a.id = :id")
    Optional<String> findJobOwnerIdById(@Param("id") UUID id);

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

    /** Row lock so a submit-triggered evaluation and a scheduler sweep never grade the same attempt at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AssessmentAttempt a where a.id = :id")
    Optional<AssessmentAttempt> lockById(@Param("id") UUID id);

    /** Submitted but never picked up for evaluation (e.g. the async job was lost). */
    @Query("select a.id from AssessmentAttempt a where a.status = com.smartstaff.entity.AttemptStatus.SUBMITTED "
            + "and a.submittedAt < :cutoff")
    List<UUID> findStuckSubmitted(@Param("cutoff") Instant cutoff);

    /** Evaluations waiting to retry a rubric or code run that couldn't be graded. */
    @Query("select a.id from AssessmentAttempt a where a.status = com.smartstaff.entity.AttemptStatus.EVALUATING "
            + "and a.nextEvaluationAt is not null and a.nextEvaluationAt <= :now")
    List<UUID> findDueRetries(@Param("now") Instant now);

    /** Evaluations that started long ago and never finished (crash/restart). */
    @Query("select a.id from AssessmentAttempt a where a.status = com.smartstaff.entity.AttemptStatus.EVALUATING "
            + "and a.nextEvaluationAt is null and a.evaluationStartedAt < :cutoff")
    List<UUID> findStaleEvaluations(@Param("cutoff") Instant cutoff);

    /** In-progress attempts whose deadline has passed (the auto-submit scheduler). */
    @Query("select a.id from AssessmentAttempt a "
            + "where a.status = com.smartstaff.entity.AttemptStatus.IN_PROGRESS and a.deadlineAt < :now")
    List<UUID> findOverdueIds(@Param("now") Instant now);
}

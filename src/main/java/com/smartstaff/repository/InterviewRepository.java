package com.smartstaff.repository;

import com.smartstaff.entity.Interview;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewRepository extends JpaRepository<Interview, UUID> {

    /** Only COMPLETED interviews are real "transcripts" worth listing —
     *  a PENDING one (invite minted, not yet conducted) has nothing to show. */
    List<Interview> findByJobIdAndStatusOrderByCreatedAtDesc(UUID jobId, String status);

    /** Twilio's status-callback webhook only ever carries a CallSid, not our
     *  own interview id. */
    Optional<Interview> findByTwilioCallSid(String twilioCallSid);

    /** Owner of the job an interview belongs to — for JobAccessGuard. */
    @Query("select i.job.ownerId from Interview i where i.id = :id")
    Optional<String> findJobOwnerIdById(@Param("id") UUID id);

    /** F16: row lock so the completion trigger and a sweep never grade the same interview at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Interview i where i.id = :id")
    Optional<Interview> lockById(@Param("id") UUID id);

    /** Completed in the window but never graded (the async job was lost). Bounded below so
     *  interviews from before grading existed aren't swept up. */
    @Query("select i.id from Interview i where i.status = 'COMPLETED' and i.evaluationStatus is null "
            + "and i.endedAt < :cutoff and i.endedAt > :since")
    List<UUID> findUngradedCompleted(@Param("cutoff") Instant cutoff, @Param("since") Instant since);

    /** Grading waiting to retry a rubric Gemini couldn't grade. */
    @Query("select i.id from Interview i where i.evaluationStatus = 'EVALUATING' "
            + "and i.nextEvaluationAt is not null and i.nextEvaluationAt <= :now")
    List<UUID> findDueEvaluationRetries(@Param("now") Instant now);
}

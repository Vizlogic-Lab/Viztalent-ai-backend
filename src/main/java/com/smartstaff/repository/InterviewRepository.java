package com.smartstaff.repository;

import com.smartstaff.entity.Interview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, UUID> {

    Optional<AssessmentAttempt> findByInviteId(UUID inviteId);

    /** A version that anyone has opened is frozen: its questions can't be edited or regenerated. */
    boolean existsByAssessmentId(UUID assessmentId);

    List<AssessmentAttempt> findByJobIdOrderByStartedAtDesc(UUID jobId);

    long countByJobIdAndStatus(UUID jobId, com.smartstaff.entity.AttemptStatus status);
}

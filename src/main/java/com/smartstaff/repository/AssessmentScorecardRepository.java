package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentScorecard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentScorecardRepository extends JpaRepository<AssessmentScorecard, UUID> {

    Optional<AssessmentScorecard> findByAttemptId(UUID attemptId);

    List<AssessmentScorecard> findByJobId(UUID jobId);
}

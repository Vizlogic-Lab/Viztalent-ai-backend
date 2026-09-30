package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssessmentAnswerRepository extends JpaRepository<AssessmentAnswer, UUID> {

    List<AssessmentAnswer> findByAttemptId(UUID attemptId);

    Optional<AssessmentAnswer> findByAttemptIdAndQuestionId(UUID attemptId, UUID questionId);
}

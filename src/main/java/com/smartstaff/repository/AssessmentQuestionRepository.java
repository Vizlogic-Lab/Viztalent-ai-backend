package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssessmentQuestionRepository extends JpaRepository<AssessmentQuestion, UUID> {

    List<AssessmentQuestion> findByAssessmentIdOrderByLevelAsc(UUID assessmentId);

    long countByAssessmentId(UUID assessmentId);
}

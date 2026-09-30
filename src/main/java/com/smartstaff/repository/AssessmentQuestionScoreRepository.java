package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentQuestionScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AssessmentQuestionScoreRepository extends JpaRepository<AssessmentQuestionScore, UUID> {
}

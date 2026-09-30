package com.smartstaff.repository;

import com.smartstaff.entity.QuestionTestCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuestionTestCaseRepository extends JpaRepository<QuestionTestCase, UUID> {

    List<QuestionTestCase> findByQuestionIdOrderBySeqAsc(UUID questionId);
}

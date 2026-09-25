package com.smartstaff.repository;

import com.smartstaff.entity.InterviewTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurn, UUID> {

    List<InterviewTurn> findByInterviewIdOrderBySeqAsc(UUID interviewId);

    @Transactional
    void deleteByInterviewId(UUID interviewId);
}

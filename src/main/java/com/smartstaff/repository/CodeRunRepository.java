package com.smartstaff.repository;

import com.smartstaff.entity.CodeRun;
import com.smartstaff.entity.CodeRunMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CodeRunRepository extends JpaRepository<CodeRun, UUID> {

    long countByAnswerIdAndMode(UUID answerId, CodeRunMode mode);

    /** Re-evaluation replaces an answer's SUBMIT runs. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from CodeRun r where r.answer.id = :answerId and r.mode = :mode")
    int deleteByAnswerIdAndMode(@Param("answerId") UUID answerId, @Param("mode") CodeRunMode mode);

    List<CodeRun> findByAnswerIdOrderByCreatedAtAsc(UUID answerId);

    /** Candidate runs per question for one attempt: [questionId, count]. */
    @Query("select r.answer.question.id, count(r) from CodeRun r where r.answer.attempt.id = :attemptId "
            + "and r.mode = com.smartstaff.entity.CodeRunMode.RUN group by r.answer.question.id")
    List<Object[]> countRunsByQuestion(@Param("attemptId") UUID attemptId);
}

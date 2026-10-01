package com.smartstaff.repository;

import com.smartstaff.entity.AssessmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AssessmentQuestionRepository extends JpaRepository<AssessmentQuestion, UUID> {

    List<AssessmentQuestion> findByAssessmentIdOrderByLevelAscSeqAsc(UUID assessmentId);

    long countByAssessmentId(UUID assessmentId);

    long countByAssessmentIdAndLevel(UUID assessmentId, String level);

    /** Bank items already used by this job's other assessment versions. */
    @Query("select distinct q.bankItemId from AssessmentQuestion q where q.assessment.job.id = :jobId "
            + "and q.assessment.id <> :excluding and q.bankItemId is not null")
    Set<UUID> bankItemsUsedByJob(@Param("jobId") UUID jobId, @Param("excluding") UUID excludingAssessmentId);

    @Query("select distinct q.bankItemId from AssessmentQuestion q where q.assessment.id = :assessmentId "
            + "and q.bankItemId is not null")
    Set<UUID> bankItemsUsedInAssessment(@Param("assessmentId") UUID assessmentId);

    /** Owner of the job this question belongs to (JobAccessGuard). */
    @Query("select j.ownerId from AssessmentQuestion q join q.assessment a join a.job j where q.id = :id")
    Optional<String> findJobOwnerIdByQuestionId(@Param("id") UUID id);
}

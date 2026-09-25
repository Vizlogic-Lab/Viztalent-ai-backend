package com.smartstaff.repository;

import com.smartstaff.entity.QuestionBankItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface QuestionBankItemRepository extends JpaRepository<QuestionBankItem, UUID> {

    long countByType(String type);

    long countByLevel(String level);

    /** Items tagged for this level, plus level-agnostic items (level IS NULL) —
     *  used by AssessmentServiceImpl's CUSTOM/MIX generation. */
    List<QuestionBankItem> findByLevelOrLevelIsNull(String level);

    @Transactional
    void deleteByUploadId(UUID uploadId);

    @Query("select i.type as type, count(i) as cnt from QuestionBankItem i group by i.type")
    List<TypeCount> countGroupedByType();

    @Query("select i.level as level, count(i) as cnt from QuestionBankItem i where i.level is not null group by i.level")
    List<LevelCount> countGroupedByLevel();

    interface TypeCount { String getType(); long getCnt(); }
    interface LevelCount { String getLevel(); long getCnt(); }
}

package com.smartstaff.repository;

import com.smartstaff.entity.QuestionBookItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionBookItemRepository extends JpaRepository<QuestionBookItem, UUID> {

    boolean existsByPromptHash(String promptHash);

    /** An empty string means "no filter" (null parameters can't be typed by Postgres inside lower()).
     *  Skill and text match part of the value. */
    @Query("select b from QuestionBookItem b "
            + "where (:type = '' or cast(b.type as string) = :type) "
            + "and (:difficulty = '' or b.difficulty = :difficulty) "
            + "and (:skill = '' or lower(b.skill) like lower(concat('%', :skill, '%'))) "
            + "and (:text = '' or lower(b.prompt) like lower(concat('%', :text, '%'))) "
            + "order by b.usageCount desc, b.createdAt desc")
    List<QuestionBookItem> search(@Param("type") String type, @Param("difficulty") String difficulty,
                                  @Param("skill") String skill, @Param("text") String text, Pageable page);
}

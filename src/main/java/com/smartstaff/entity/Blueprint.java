package com.smartstaff.entity;

import com.smartstaff.entity.JobRoleProfile.RoleFamily;

import java.util.List;

/** The plan for one level of an assessment: which question slots to fill,
 *  worth how much, aimed at which skill. Stored per level in
 *  assessments.blueprint. */
public record Blueprint(
        String level,
        RoleFamily roleFamily,
        boolean technical,
        List<String> languages,
        int totalPoints,
        int durationMinutes,
        List<Slot> slots
) {
    public record Slot(
            int seq,
            QuestionType type,
            Competency competency,
            Dimension dimension,
            int points,
            Difficulty difficulty,
            String targetSkill,
            int timeEstimateSec,
            boolean designScenario
    ) {}

    public int points(Dimension dimension) {
        return slots.stream().filter(s -> s.dimension() == dimension).mapToInt(Slot::points).sum();
    }
}

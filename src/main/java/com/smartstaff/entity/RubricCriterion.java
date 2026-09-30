package com.smartstaff.entity;

import java.util.List;

/** One weighted criterion of a question's grading rubric, stored as jsonb. */
public record RubricCriterion(String criterion, int weight, String description) {

    /** The quality share (30%) of a CODE_WRITE question, per the architecture doc. */
    public static final List<RubricCriterion> CODE_WRITE_DEFAULT = List.of(
            new RubricCriterion("approach", 10, "Approach and algorithm choice"),
            new RubricCriterion("complexity", 8, "Time/space complexity versus the expected one"),
            new RubricCriterion("edge_cases", 6, "Edge-case handling visible in the code"),
            new RubricCriterion("readability", 6, "Naming and structure"));
}

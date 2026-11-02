package com.smartstaff.util;

import com.smartstaff.entity.Competency;
import com.smartstaff.entity.Dimension;
import com.smartstaff.entity.LevelPassMarks;
import com.smartstaff.entity.QuestionType;
import com.smartstaff.entity.ScoringSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PassMarkRulesTest {

    private static ScoringRules.Scored theory(double earned, int points) {
        return new ScoringRules.Scored(QuestionType.MCQ, Dimension.THEORY, Competency.CONCEPTS, earned, points);
    }

    private static ScoringRules.Scored handsOn(double earned, int points) {
        return new ScoringRules.Scored(QuestionType.CODE_WRITE, Dimension.HANDS_ON, Competency.CODING, earned, points);
    }

    @Test
    @DisplayName("a job override beats the global value, which beats the defaults; unset fields fall through")
    void precedence() {
        Map<String, LevelPassMarks> global = Map.of("L1", new LevelPassMarks(55, 50, null));
        Map<String, LevelPassMarks> job = Map.of("L1", new LevelPassMarks(70, null, null));

        ScoringSettings l1 = PassMarkRules.effective(null, global, job, "L1");
        assertThat(l1.overall_pass()).as("job override").isEqualTo(70);
        assertThat(l1.proceed_hands_on_min()).as("global, job left it unset").isEqualTo(50);
        assertThat(l1.proceed_theory_min()).as("default, nobody set it").isEqualTo(50);

        ScoringSettings l2 = PassMarkRules.effective(null, global, job, "L2");
        assertThat(l2.overall_pass()).as("no L2 values: default").isZero();
    }

    @Test
    @DisplayName("interviews (no level) keep the job's own settings")
    void noLevel() {
        assertThat(PassMarkRules.effective(null, Map.of("L1", new LevelPassMarks(90, 90, 90)), null, null))
                .isEqualTo(ScoringSettings.DEFAULTS);
    }

    @Test
    @DisplayName("the overall pass mark is a gate: PROCEED needs it, even when both dimension gates pass")
    void overallGate() {
        // Theory 62 and hands-on 62: both dimension gates pass (60 / 50), overall is 62.
        var questions = List.of(theory(62, 100), handsOn(62, 100));
        ScoringSettings lowBar = PassMarkRules.effective(null, Map.of("L1", new LevelPassMarks(60, null, null)), null, "L1");
        ScoringSettings highBar = PassMarkRules.effective(null, Map.of("L1", new LevelPassMarks(70, null, null)), null, "L1");

        assertThat(ScoringRules.score(questions, lowBar, Set.of(), 0).recommendation()).isEqualTo("PROCEED");
        assertThat(ScoringRules.score(questions, highBar, Set.of(), 0).recommendation())
                .as("overall 62 is below the 70 pass mark").isEqualTo("NOT_RECOMMENDED");
    }

    @Test
    @DisplayName("pass marks must be 0-100 and only for L1, L2 or L3")
    void validation() {
        assertThat(PassMarkRules.problem(Map.of("L1", new LevelPassMarks(60, 60, 50)))).isNull();
        assertThat(PassMarkRules.problem(Map.of("L1", new LevelPassMarks(101, null, null)))).isNotNull();
        assertThat(PassMarkRules.problem(Map.of("L4", new LevelPassMarks(60, null, null)))).isNotNull();
    }
}

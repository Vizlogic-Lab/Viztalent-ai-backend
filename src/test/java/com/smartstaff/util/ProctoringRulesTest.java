package com.smartstaff.util;

import com.smartstaff.entity.AttemptEventType;
import com.smartstaff.util.ProctoringRules.Stamped;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.smartstaff.entity.AttemptEventType.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProctoringRulesTest {

    private static final Instant T = Instant.parse("2026-10-05T10:00:00Z");

    private static Stamped at(AttemptEventType type, double seconds) {
        return new Stamped(type, T.plusMillis((long) (seconds * 1000)));
    }

    @Test
    void oneActionIsOneWarning() {
        assertThat(ProctoringRules.countWarnings(List.of(at(TAB_SWITCH, 0), at(FULLSCREEN_EXIT, 0.5), at(TAB_SWITCH, 3.9))))
                .isEqualTo(1);
        assertThat(ProctoringRules.countWarnings(List.of(at(TAB_SWITCH, 0), at(CAMERA_OFF, 4), at(MIC_LOST, 8.5)))).isEqualTo(3);
    }

    @Test
    void onlyWarningTypesCountAndOrderDoesNotMatter() {
        assertThat(ProctoringRules.countWarnings(List.of(at(NO_FACE, 0), at(LOOKING_AWAY, 10), at(PHONE_DETECTED, 20),
                at(MIC_SILENT, 30), at(FOCUS_LOST, 40), at(PASTE, 50)))).isZero();
        assertThat(ProctoringRules.countWarnings(List.of(at(MIC_MUTED, 20), at(TAB_SWITCH, 0), at(MIC_OFF, 10)))).isEqualTo(3);
        assertThat(ProctoringRules.WARNING_TYPES).doesNotContain(WARNING, TERMINATED);
        assertThat(ProctoringRules.SERVER_ONLY).isEqualTo(Set.of(WARNING, TERMINATED));
    }

    @Test
    void concernGate() {
        assertThat(ProctoringRules.concern(2, 0, 0, 60)).isFalse();
        assertThat(ProctoringRules.concern(3, 0, 0, 0)).isTrue();
        assertThat(ProctoringRules.concern(0, 1, 0, 0)).isTrue();
        assertThat(ProctoringRules.concern(0, 0, 1, 0)).isTrue();
        assertThat(ProctoringRules.concern(0, 0, 0, 61)).isTrue();
    }

    @Test
    void concernForcesHrReviewInTheGate() {
        var passing = List.of(new ScoringRules.Scored(com.smartstaff.entity.QuestionType.MCQ, com.smartstaff.entity.Dimension.THEORY,
                com.smartstaff.entity.Competency.CONCEPTS, 9, 10), new ScoringRules.Scored(com.smartstaff.entity.QuestionType.CODE_WRITE,
                com.smartstaff.entity.Dimension.HANDS_ON, com.smartstaff.entity.Competency.CODING, 9, 10));
        assertThat(ScoringRules.score(passing, null, Set.of(), 0, false).recommendation()).isEqualTo("PROCEED");
        ScoringRules.Result flagged = ScoringRules.score(passing, null, Set.of(), 0, true);
        assertThat(flagged.recommendation()).isEqualTo("HR_REVIEW");
        assertThat(flagged.flags()).contains(ProctoringRules.CONCERN_FLAG);
    }
}

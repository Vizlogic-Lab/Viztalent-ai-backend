package com.smartstaff.util;

import com.smartstaff.entity.Assessment;
import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.MarkingScheme;
import com.smartstaff.entity.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssessmentTimingTest {

    private static AssessmentQuestion q(QuestionType type, Integer seconds) {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setType(type);
        q.setTimeEstimateSec(seconds);
        q.setLevel("L1");
        return q;
    }

    @Test
    void aCustomPaperUsesItsOwnDuration() {
        Assessment paper = new Assessment();
        paper.setMarkingScheme(new MarkingScheme(MarkingScheme.DEFAULTS.sections(), 2, 2, 10, null));
        List<AssessmentQuestion> qs = List.of(q(QuestionType.MCQ, 600), q(QuestionType.MCQ, 600));

        assertThat(AssessmentTiming.levelSeconds(paper, qs)).isEqualTo(600);
        AssessmentTiming.LevelInfo info = AssessmentTiming.levelInfo(paper, qs);
        assertThat(info.minutes()).isEqualTo(10);
        assertThat(info.questions()).isEqualTo(2);
        assertThat(info.types()).isEqualTo(Map.of("MCQ", 2));
    }

    @Test
    void aGeneratedTestAddsUpTheQuestionEstimates() {
        Assessment generated = new Assessment();
        List<AssessmentQuestion> qs = List.of(q(QuestionType.MCQ, 90), q(QuestionType.CODE_WRITE, 1200),
                q(QuestionType.SCENARIO, null));
        assertThat(AssessmentTiming.levelSeconds(generated, qs)).isEqualTo(1290);
        assertThat(AssessmentTiming.levelInfo(generated, qs).minutes()).isEqualTo(22);   // 21.5 rounds to 22
        assertThat(AssessmentTiming.levelInfo(generated, qs).types())
                .containsEntry("MCQ", 1).containsEntry("CODE_WRITE", 1).containsEntry("SCENARIO", 1);
    }

    @Test
    void noDurationAndNoEstimatesFallsBackTo45Minutes() {
        assertThat(AssessmentTiming.levelSeconds(new Assessment(), List.of(q(QuestionType.MCQ, null))))
                .isEqualTo(45 * 60);
        assertThat(AssessmentTiming.levelInfo(new Assessment(), List.of()).minutes()).isEqualTo(45);
    }
}

package com.smartstaff.util;

import com.smartstaff.util.CalibrationRules.Agreement;
import com.smartstaff.util.CalibrationRules.Observation;
import com.smartstaff.util.CalibrationRules.QuestionStats;
import com.smartstaff.util.CalibrationRules.SampleSuggestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CalibrationRulesTest {

    @Test
    void withinTenPercentagePointsOfTheMaximum() {
        assertThat(CalibrationRules.within(14, 12, 20)).isTrue();      // exactly 10%
        assertThat(CalibrationRules.within(14, 11.9, 20)).isFalse();   // 10.5%
        assertThat(CalibrationRules.within(1, 0, 10)).isTrue();
        assertThat(CalibrationRules.within(0, 0, 0)).isTrue();
        assertThat(CalibrationRules.within(1, 0, 0)).isFalse();
    }

    @Test
    void agreementSharesAndSignedDifference() {
        Agreement a = new Agreement();
        a.add(10, 10, 10);
        a.add(8, 4, 10);      // engine +40 points
        a.add(5, 5, 10);
        a.unattributed();
        Map<String, Object> m = a.toMap();
        assertThat(m.get("pairs")).isEqualTo(3);
        assertThat(m.get("within_tolerance")).isEqualTo(2);
        assertThat(m.get("share")).isEqualTo(0.667);
        assertThat(m.get("mean_abs_diff_pct")).isEqualTo(13.33);
        assertThat(m.get("mean_signed_diff_pct")).isEqualTo(13.33);
        assertThat(m.get("meets_target")).isEqualTo(false);
        assertThat(m.get("unattributed")).isEqualTo(1);
        assertThat(new Agreement().toMap().get("share")).isNull();
    }

    @Test
    void criterionKeysGroupScenarioKeyPoints() {
        assertThat(CalibrationRules.criterionKey("CODE_WRITE", "Approach")).isEqualTo("CODE_WRITE:approach");
        assertThat(CalibrationRules.criterionKey("SCENARIO", "KP3")).isEqualTo("SCENARIO:key_point");
    }

    @Test
    void pearson() {
        assertThat(CalibrationRules.pearson(new double[]{0, 1, 0, 1}, new double[]{0.2, 0.8, 0.2, 0.8})).isCloseTo(1.0, within(1e-9));
        assertThat(CalibrationRules.pearson(new double[]{0, 1, 0, 1}, new double[]{0.8, 0.2, 0.8, 0.2})).isCloseTo(-1.0, within(1e-9));
        assertThat(CalibrationRules.pearson(new double[]{1, 1, 1}, new double[]{0.1, 0.5, 0.9})).isEqualTo(0.0);
        assertThat(CalibrationRules.pearson(new double[]{1}, new double[]{1})).isNull();
    }

    private static List<Observation> repeat(int n, double score, double points, double rest) {
        return new ArrayList<>(Collections.nCopies(n, new Observation(score, points, rest)));
    }

    @Test
    void tooEasyAndTooHardOnlyFromTwentyAttempts() {
        QuestionStats easy = CalibrationRules.questionStats(repeat(20, 5, 5, 0.6));
        assertThat(easy.fullMarksRate()).isEqualTo(1.0);
        assertThat(easy.discrimination()).isEqualTo(0.0);
        assertThat(easy.flags()).containsExactly(CalibrationRules.TOO_EASY, CalibrationRules.LOW_DISCRIMINATION_FLAG);
        assertThat(CalibrationRules.questionStats(repeat(19, 5, 5, 0.6)).flags()).isEmpty();

        QuestionStats hard = CalibrationRules.questionStats(repeat(20, 0, 5, 0.6));
        assertThat(hard.anyCreditRate()).isEqualTo(0.0);
        assertThat(hard.flags()).containsExactly(CalibrationRules.TOO_HARD, CalibrationRules.LOW_DISCRIMINATION_FLAG);

        // 19 of 20 full marks = 95%: not "more than 95%".
        List<Observation> nineteen = repeat(19, 5, 5, 0.9);
        nineteen.add(new Observation(0, 5, 0.1));
        assertThat(CalibrationRules.questionStats(nineteen).flags()).isEmpty();
    }

    @Test
    void discriminatingQuestionIsNotFlagged() {
        List<Observation> obs = new ArrayList<>();
        for (int i = 0; i < 20; i++) obs.add(new Observation(i % 2 == 0 ? 10 : 2, 10, i % 2 == 0 ? 0.9 : 0.3));
        QuestionStats s = CalibrationRules.questionStats(obs);
        assertThat(s.attempts()).isEqualTo(20);
        assertThat(s.meanScore()).isEqualTo(6.0);
        assertThat(s.meanPercent()).isEqualTo(60.0);
        assertThat(s.passRate()).isEqualTo(0.5);
        assertThat(s.anyCreditRate()).isEqualTo(1.0);
        assertThat(s.discrimination()).isEqualTo(1.0);
        assertThat(s.flags()).isEmpty();
    }

    @Test
    void sampleRateSuggestionUsesTheLastFiftyAndNeverBelowTheMinimum() {
        List<Boolean> fortyNine = new ArrayList<>(Collections.nCopies(49, true));
        SampleSuggestion few = CalibrationRules.sampleRate(fortyNine, 0.2);
        assertThat(few.suggest()).isFalse();
        assertThat(few.reviews()).isEqualTo(49);
        assertThat(few.reason()).contains("Needs 50");

        // Newest 50: 45 agree (90%); the 10 older disagreements are outside the window.
        List<Boolean> history = new ArrayList<>(Collections.nCopies(45, true));
        history.addAll(Collections.nCopies(5, false));
        history.addAll(Collections.nCopies(10, false));
        SampleSuggestion yes = CalibrationRules.sampleRate(history, 0.2);
        assertThat(yes.reviews()).isEqualTo(50);
        assertThat(yes.agreement()).isEqualTo(0.9);
        assertThat(yes.suggest()).isTrue();
        assertThat(yes.suggestedRate()).isEqualTo(0.1);
        assertThat(yes.reason()).contains("Not changed automatically");

        assertThat(CalibrationRules.sampleRate(history, 0.08).suggestedRate()).isEqualTo(0.05);
        assertThat(CalibrationRules.sampleRate(history, 0.05).suggest()).isFalse();

        List<Boolean> low = new ArrayList<>(Collections.nCopies(44, true));
        low.addAll(Collections.nCopies(6, false));
        assertThat(CalibrationRules.sampleRate(low, 0.2).suggest()).isFalse();
    }
}

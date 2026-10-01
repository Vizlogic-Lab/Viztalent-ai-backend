package com.smartstaff.util;

import com.smartstaff.entity.QuestionType;
import com.smartstaff.util.ResultNarrator.Facts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultNarratorTest {

    @Test
    @DisplayName("evidence line from stored test results: hidden passes and failed categories in words")
    void evidenceLine() {
        Map<String, Object> tests = Map.of("kind", "tests", "hidden_passed", 5, "hidden_total", 12,
                "failed", List.of(Map.of("category", "EDGE", "status", "FAILED"), Map.of("category", "LARGE", "status", "TIMEOUT")));
        List<String> lines = ResultNarrator.evidenceLines(List.of(
                new ResultNarrator.Question(7, QuestionType.CODE_WRITE, 3.5, 10, List.of(tests), List.of())));
        assertThat(lines).containsExactly(
                "Q7 (CODE_WRITE): 3.5/10 — passed 5 of 12 hidden tests; failed EDGE: wrong answer; LARGE: timed out");
    }

    @Test
    @DisplayName("evidence line for rubric criteria counts the evidenced ones")
    void rubricLine() {
        List<Map<String, Object>> ev = List.of(
                Map.of("kind", "criterion", "criterion", "KP1", "score", 2.0),
                Map.of("kind", "criterion", "criterion", "KP2", "score", 0.0));
        assertThat(ResultNarrator.evidenceLines(List.of(
                new ResultNarrator.Question(2, QuestionType.SCENARIO, 2, 4, ev, List.of("GRADER_DISAGREEMENT")))))
                .containsExactly("Q2 (SCENARIO): 2/4 — rubric: 1 of 2 criteria evidenced; graders disagreed (median of 3 runs)");
    }

    @Test
    @DisplayName("number check: every number in the summary must exist in its input")
    void numberCheck() {
        String input = "Theory score: 82 (STRONG)\nHands-on score: 45.5 (BASIC)\n- Q7 (CODE_WRITE): 3/10 — passed 5 of 12 hidden tests";
        assertThat(ResultNarrator.numbersSupported("Theory 82.0, hands-on 45.5; Q7 passed 5 of 12.", input)).isTrue();
        assertThat(ResultNarrator.numbersSupported("Hands-on 46 out of 100.", input)).isFalse();
        assertThat(ResultNarrator.numbersSupported("Good understanding, weak practice.", input)).isTrue();
    }

    @Test
    @DisplayName("template summary uses the facts and the weakest questions as gaps (max 4)")
    void template() {
        Facts f = new Facts(new BigDecimal("82.00"), new BigDecimal("45.00"), new BigDecimal("56.10"), "STRONG", "BASIC",
                "NOT_RECOMMENDED", List.of("THEORY_PRACTICE_GAP"), Map.of(), List.of("a", "b", "c", "d", "e"),
                List.of("a", "b", "c", "d", "e"));
        Map<String, Object> m = ResultNarrator.template(f);
        assertThat(m.get("theory_line")).isEqualTo("Theoretical understanding: strong (82/100).");
        assertThat(m.get("hands_on_line")).isEqualTo("Hands-on capability: basic (45/100).");
        assertThat((List<?>) m.get("gaps")).hasSize(4);
        assertThat(m.get("source")).isEqualTo("TEMPLATE");
        assertThat(ResultNarrator.numbersSupported(m.toString(), f.asText() + " 100")).isTrue();
    }
}

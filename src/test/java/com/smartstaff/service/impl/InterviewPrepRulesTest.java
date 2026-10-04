package com.smartstaff.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartstaff.entity.Dimension;
import com.smartstaff.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewPrepRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aiFillsToTheTotalWithOneOrTwoPracticalPrompts() {
        assertThat(InterviewServiceImpl.split(0, 5)).containsExactly(3, 2);
        assertThat(InterviewServiceImpl.split(3, 5)).containsExactly(1, 1);
        assertThat(InterviewServiceImpl.split(4, 5)).containsExactly(0, 1);
        assertThat(InterviewServiceImpl.split(5, 5)).containsExactly(0, 0);
        assertThat(InterviewServiceImpl.split(7, 5)).as("more recruiter questions than the total: no AI questions").containsExactly(0, 0);
    }

    private static JsonNode read(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void parseTakesTheRequestedMixTopsUpAndSkipsUngradableQuestions() throws Exception {
        JsonNode obj = read("""
                {"intro": "Hi", "outro": "Bye",
                 "questions": [{"category": "Tech", "question": "Q1?", "expected_points": ["a", "b"]},
                               {"category": "Tech", "question": "No points?", "expected_points": []},
                               {"category": "Tech", "question": "Q2?", "expected_points": ["c", " "]}],
                 "practical_questions": [{"category": "P", "question": "How would you design X?", "expected_points": ["d"]}],
                 "recruiter_expected_points": [{"index": 1, "expected_points": ["e"]}, {"index": 2, "expected_points": ["f"]}]}
                """);
        InterviewServiceImpl.GeneratedInterview g = InterviewServiceImpl.parse(obj, 1, 2, List.of(2), 2);
        assertThat(g.questions()).extracting(InterviewServiceImpl.GeneratedQuestion::question)
                .containsExactly("Q1?", "How would you design X?", "Q2?");
        assertThat(g.questions()).extracting(InterviewServiceImpl.GeneratedQuestion::dimension)
                .containsExactly(Dimension.THEORY, Dimension.HANDS_ON, Dimension.THEORY);
        assertThat(g.questions().get(2).expectedPoints()).containsExactly("c");
        assertThat(g.recruiterPoints()).as("only the recruiter questions that asked for points").containsOnlyKeys(2);
    }

    @Test
    void noQuestionsAtAllIsAnError() throws Exception {
        JsonNode empty = read("{\"intro\": \"Hi\", \"outro\": \"Bye\", \"questions\": []}");
        assertThatThrownBy(() -> InterviewServiceImpl.parse(empty, 3, 2, List.of(), 0)).isInstanceOf(ApiException.class);
        assertThat(InterviewServiceImpl.parse(empty, 0, 0, List.of(), 2).questions()).as("recruiter questions alone are fine").isEmpty();
    }

    @Test
    void shortAnswerCounting() {
        assertThat(InterviewEvaluationJob.words(null)).isZero();
        assertThat(InterviewEvaluationJob.words("  ")).isZero();
        assertThat(InterviewEvaluationJob.words("Yes, sure.")).isEqualTo(2);
        assertThat(InterviewEvaluationJob.words(" I would  profile it ")).isEqualTo(4);
    }
}

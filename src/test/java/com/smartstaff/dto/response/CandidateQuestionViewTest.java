package com.smartstaff.dto.response;

import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.QuestionTestCase;
import com.smartstaff.entity.QuestionType;
import com.smartstaff.entity.RubricCriterion;
import com.smartstaff.entity.TestCaseCategory;
import com.smartstaff.mapper.AssessmentMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** The candidate view must never be able to carry answers, solutions, hidden
 *  tests or the rubric — checked structurally, including nested records. */
class CandidateQuestionViewTest {

    private static final List<String> FORBIDDEN = List.of(
            "correct", "answer", "solution", "rubric", "key_point", "hidden", "explanation",
            "validat", "fixed", "naive", "buggy", "visible", "weight", "category", "tolerance", "expected_complexity");

    private static void collectComponents(Class<?> type, Set<Class<?>> seen, List<String> names) {
        if (!type.isRecord() || !seen.add(type)) return;
        for (RecordComponent c : type.getRecordComponents()) {
            names.add(type.getSimpleName() + "." + c.getName());
            collectComponents(c.getType(), seen, names);
            collectGeneric(c.getGenericType(), seen, names);
        }
    }

    private static void collectGeneric(Type t, Set<Class<?>> seen, List<String> names) {
        if (t instanceof ParameterizedType p) {
            for (Type arg : p.getActualTypeArguments()) {
                if (arg instanceof Class<?> c) collectComponents(c, seen, names);
                collectGeneric(arg, seen, names);
            }
        }
    }

    @Test
    void noAnswerFieldsAnywhereInTheView() {
        List<String> names = new ArrayList<>();
        collectComponents(CandidateQuestionView.class, new HashSet<>(), names);

        assertThat(names).contains("SampleTest.input", "CandidateQuestionView.starter_code");
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            assertThat(FORBIDDEN).as("forbidden field %s", name).noneMatch(lower::contains);
        }
    }

    @Test
    void mapperDropsHiddenTestsAndAnswers() {
        AssessmentQuestion q = new AssessmentQuestion();
        q.setId(UUID.randomUUID());
        q.setLevel("L1");
        q.setType(QuestionType.CODE_WRITE);
        q.setPoints(20);
        q.setPrompt("Sum two numbers read from stdin.");
        q.setLanguages(List.of("java", "python"));
        q.setStarterCode(Map.of("python", "a, b = map(int, input().split())\n"));
        q.setReferenceSolution(Map.of("python", "SECRET_REFERENCE"));
        q.setModelAnswer("SECRET_MODEL");
        q.setRubric(List.of(new RubricCriterion("approach", 10, "SECRET_RUBRIC")));
        q.addTestCase(test("1 2", "3", true));
        q.addTestCase(test("100 200", "SECRET_HIDDEN_OUTPUT", false));

        CandidateQuestionView view = new AssessmentMapper().toCandidateView(q);

        assertThat(view.sample_tests()).hasSize(1);
        assertThat(view.sample_tests().get(0).expected_output()).isEqualTo("3");
        assertThat(view.toString()).doesNotContain("SECRET");
        assertThat(view.dimension()).isEqualTo("HANDS_ON");
        assertThat(view.competency()).isEqualTo("CODING");
    }

    private static QuestionTestCase test(String input, String output, boolean visible) {
        QuestionTestCase t = new QuestionTestCase();
        t.setInput(input);
        t.setExpectedOutput(output);
        t.setVisible(visible);
        t.setCategory(TestCaseCategory.BASIC);
        return t;
    }
}

package com.smartstaff.mapper;

import com.smartstaff.dto.response.AssessmentQuestionResponse;
import com.smartstaff.dto.response.CandidateQuestionView;
import com.smartstaff.entity.AssessmentQuestion;
import com.smartstaff.entity.QuestionType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AssessmentMapper {

    /** HR/SME answer-key row: the original popup fields plus every practical
     *  detail. Reads test cases lazily, so call inside a transaction. */
    public AssessmentQuestionResponse toQuestionResponse(AssessmentQuestion q) {
        List<Integer> indices = q.getCorrectIndices();
        Integer correctIndex = q.getType() == QuestionType.MCQ && !indices.isEmpty() ? indices.get(0) : null;
        List<Integer> correctIndices = q.getType() == QuestionType.MSQ ? indices : null;
        boolean debug = q.getType() == QuestionType.CODE_DEBUG;
        List<AssessmentQuestionResponse.TestCaseView> tests = q.getTestCases().stream()
                .map(t -> new AssessmentQuestionResponse.TestCaseView(t.getSeq(), t.getInput(), t.getExpectedOutput(),
                        t.isVisible(), t.getCategory().name(), t.getWeight(), t.getFloatTolerance()))
                .toList();
        return new AssessmentQuestionResponse(
                q.getType().name(), q.getPrompt(), q.getOptions(), correctIndex, correctIndices,
                q.getSkill(), q.getDifficulty(),
                q.getId() == null ? null : q.getId().toString(),
                q.getLevel(), q.getSeq(),
                q.getDimension() == null ? null : q.getDimension().name(),
                q.getCompetency() == null ? null : q.getCompetency().name(),
                q.getPoints(), q.getTimeEstimateSec(), q.getTitle(), q.getPrompt(),
                q.getConstraints(), q.getInputFormat(), q.getOutputFormat(),
                emptyToNull(q.getLanguages()),
                emptyToNull(q.getStarterCode()),
                debug ? null : emptyToNull(q.getReferenceSolution()),
                emptyToNull(q.getBuggyCode()),
                debug ? emptyToNull(q.getReferenceSolution()) : null,
                emptyToNull(q.getBugDescriptions()),
                q.getModelAnswer(),
                emptyToNull(q.getKeyPoints()),
                q.getExplanation(),
                emptyToNull(q.getRubric()),
                q.getExpectedComplexity(),
                tests.isEmpty() ? null : tests,
                q.isValidated(),
                q.getValidationLog(),
                q.getOrigin());
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }

    private static <K, V> Map<K, V> emptyToNull(Map<K, V> map) {
        return map == null || map.isEmpty() ? null : map;
    }

    /** Candidate-facing view: visible tests only. Reads test cases lazily, so
     *  call inside a transaction. */
    public CandidateQuestionView toCandidateView(AssessmentQuestion q) {
        List<CandidateQuestionView.SampleTest> samples = q.getTestCases().stream()
                .filter(t -> t.isVisible())
                .map(t -> new CandidateQuestionView.SampleTest(t.getSeq(), t.getInput(), t.getExpectedOutput()))
                .toList();
        return new CandidateQuestionView(
                q.getId() == null ? null : q.getId().toString(),
                q.getLevel(),
                q.getSeq(),
                q.getType().name(),
                q.getDimension().name(),
                q.getCompetency().name(),
                q.getPoints(),
                q.getTimeEstimateSec(),
                q.getTitle(),
                q.getPrompt(),
                q.getConstraints(),
                q.getInputFormat(),
                q.getOutputFormat(),
                List.copyOf(q.getOptions()),
                q.getSkill(),
                q.getDifficulty(),
                List.copyOf(q.getLanguages()),
                Map.copyOf(q.getStarterCode()),
                samples);
    }
}

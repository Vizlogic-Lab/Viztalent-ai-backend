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

    /** HR answer-key row. */
    public AssessmentQuestionResponse toQuestionResponse(AssessmentQuestion q) {
        List<Integer> indices = q.getCorrectIndices();
        Integer correctIndex = q.getType() == QuestionType.MCQ && !indices.isEmpty() ? indices.get(0) : null;
        List<Integer> correctIndices = q.getType() == QuestionType.MSQ ? indices : null;
        return new AssessmentQuestionResponse(q.getType().name(), q.getPrompt(), q.getOptions(),
                correctIndex, correctIndices, q.getSkill(), q.getDifficulty());
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

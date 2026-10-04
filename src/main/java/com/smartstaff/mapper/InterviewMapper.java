package com.smartstaff.mapper;

import com.smartstaff.dto.response.InterviewPrepareResponse;
import com.smartstaff.dto.response.InterviewQuestionResponse;
import com.smartstaff.dto.response.InterviewTranscriptResponse;
import com.smartstaff.dto.response.TranscriptTurnResponse;
import com.smartstaff.entity.Interview;
import com.smartstaff.entity.InterviewTurn;
import com.smartstaff.entity.SmeReview;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InterviewMapper {

    /** Candidate-facing: question text only — never expected points or scores. */
    public InterviewPrepareResponse toPrepareResponse(Interview interview, List<InterviewTurn> turns) {
        return new InterviewPrepareResponse(
                interview.getId().toString(),
                interview.getRoleTitle(),
                interview.getCandidateName(),
                interview.getLanguage(),
                interview.getIntro(),
                interview.getOutro(),
                turns.stream()
                        .map(t -> new InterviewQuestionResponse(t.getCategory(), t.getSkill(), t.getQuestion()))
                        .toList()
        );
    }

    /** review / reviewerName may be null. */
    public InterviewTranscriptResponse toTranscriptResponse(Interview interview, List<InterviewTurn> turns,
                                                            SmeReview review, String reviewerName) {
        String st = interview.getEvaluationStatus();
        String result = st == null ? null : "VALIDATED".equals(st) ? "VALIDATED" : "EVALUATING".equals(st) ? "PENDING" : "PROVISIONAL";
        return new InterviewTranscriptResponse(
                interview.getId().toString(),
                interview.getCandidateName(),
                interview.getPhone(),
                interview.getRoleTitle(),
                turns.stream().map(TranscriptTurnResponse::of).toList(),
                interview.getEndedAt() != null ? interview.getEndedAt() : interview.getCreatedAt(),
                interview.getMode(),
                st,
                result,
                interview.getTheoryScore(),
                interview.getHandsOnScore(),
                interview.getOverallScore(),
                interview.getTheoryBand(),
                interview.getHandsOnBand(),
                interview.getRecommendation(),
                interview.getFlags(),
                interview.getValidationReason(),
                review == null ? null : review.getId().toString(),
                review == null ? null : review.getStatus().name(),
                reviewerName,
                review == null ? null : review.getComment()
        );
    }
}

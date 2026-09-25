package com.smartstaff.mapper;

import com.smartstaff.dto.response.InterviewPrepareResponse;
import com.smartstaff.dto.response.InterviewQuestionResponse;
import com.smartstaff.dto.response.InterviewTranscriptResponse;
import com.smartstaff.dto.response.TranscriptTurnResponse;
import com.smartstaff.entity.Interview;
import com.smartstaff.entity.InterviewTurn;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InterviewMapper {

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

    public InterviewTranscriptResponse toTranscriptResponse(Interview interview, List<InterviewTurn> turns) {
        return new InterviewTranscriptResponse(
                interview.getId().toString(),
                interview.getCandidateName(),
                interview.getPhone(),
                interview.getRoleTitle(),
                turns.stream()
                        .map(t -> new TranscriptTurnResponse(t.getCategory(), t.getSkill(), t.getQuestion(), t.getAnswer()))
                        .toList(),
                interview.getEndedAt() != null ? interview.getEndedAt() : interview.getCreatedAt()
        );
    }
}

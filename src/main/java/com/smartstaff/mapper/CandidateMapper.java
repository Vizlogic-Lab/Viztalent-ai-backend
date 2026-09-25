package com.smartstaff.mapper;

import com.smartstaff.dto.response.CandidateRowResponse;
import com.smartstaff.entity.Candidate;
import org.springframework.stereotype.Component;

@Component
public class CandidateMapper {

    public CandidateRowResponse toRowResponse(Candidate c) {
        return new CandidateRowResponse(
                c.getResume().getFilename(),
                c.getCandidateName(),
                c.getEmail(),
                c.getPhone(),
                c.getYearsExperience(),
                c.getFitScore(),
                c.getMatchedRequiredCount(),
                c.getTotalRequiredCount(),
                String.join(", ", c.getMatchedSkills()),
                String.join(", ", c.getMissingSkills()),
                c.getScoreBreakdown(),
                c.getJob().getId().toString()
        );
    }
}

package com.smartstaff.mapper;

import com.smartstaff.dto.response.CandidateRowResponse;
import com.smartstaff.entity.Candidate;
import com.smartstaff.service.SettingsService;
import com.smartstaff.util.ScreeningDecision;
import org.springframework.stereotype.Component;

@Component
public class CandidateMapper {

    private final SettingsService settings;

    public CandidateMapper(SettingsService settings) {
        this.settings = settings;
    }

    public CandidateRowResponse toRowResponse(Candidate c) {
        ScreeningDecision.Result d = ScreeningDecision.decide(c, settings.getScreeningRule());
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
                c.getJob().getId().toString(),
                c.getAiMatchScore(),
                c.getAiMatchStatus(),
                c.getAiMatchSummary(),
                c.getAiMatchStrengths() == null ? "" : String.join("; ", c.getAiMatchStrengths()),
                c.getAiMatchGaps() == null ? "" : String.join("; ", c.getAiMatchGaps()),
                c.getExperienceStatus(),
                d.decision(),
                d.step(),
                d.reason(),
                c.isOverridePassed(),
                c.getOverrideReason(),
                c.getOverrideBy(),
                c.getOverrideAt() == null ? null : c.getOverrideAt().toString()
        );
    }
}

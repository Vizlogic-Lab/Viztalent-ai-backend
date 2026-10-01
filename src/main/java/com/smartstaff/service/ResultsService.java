package com.smartstaff.service;

import com.smartstaff.dto.response.AssessmentSubmissionsResponse;
import com.smartstaff.dto.response.AttemptReviewResponse;
import com.smartstaff.entity.ScoringSettings;
import com.smartstaff.entity.User;

import java.util.UUID;

/** F10 — HR-facing assessment results (read side) and per-job scoring settings. */
public interface ResultsService {

    AssessmentSubmissionsResponse submissions(UUID jobId);

    /** Every job the requester can see: all for admins, their own otherwise. */
    AssessmentSubmissionsResponse submissionsAll(User requester);

    AttemptReviewResponse attempt(UUID attemptId);

    ScoringSettings scoringSettings(UUID jobId);

    ScoringSettings updateScoringSettings(UUID jobId, ScoringSettings settings);
}

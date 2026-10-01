package com.smartstaff.service;

import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;

import java.util.UUID;

public interface AssessmentService {

    AssessmentStatusResponse status(UUID jobId);

    AssessmentGenerateResponse generate(AssessmentGenerateRequest req, User admin);

    /** The answer key of the current version, or of `version` when given. */
    AnswerKeyResponse answerKey(UUID jobId, Integer version);

    /** The scorecard for one attempt of this job (or a NONE placeholder while
     *  it is still being scored). */
    ScorecardResponse scorecard(UUID jobId, UUID attemptId);

    /** Re-run automatic scoring for a submitted attempt (e.g. after the runner
     *  or Gemini was down). */
    void rescore(UUID jobId, UUID attemptId);

    /** The scorecard rendered as a downloadable PDF (only once SCORED). */
    ScorecardPdfService.ScorecardPdf scorecardPdf(UUID jobId, UUID attemptId);
}

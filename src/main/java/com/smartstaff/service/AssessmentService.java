package com.smartstaff.service;

import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;

import java.util.UUID;

public interface AssessmentService {

    /** Submissions are still Phase 6 scope beyond generation itself — this
     *  backend has no candidate-facing assessment-taking page to submit
     *  from (see docs/FEATURES.md), so this always reports zero. */
    AssessmentSubmissionsResponse submissions(UUID jobId);

    AssessmentStatusResponse status(UUID jobId);

    AssessmentGenerateResponse generate(AssessmentGenerateRequest req, User admin);

    AnswerKeyResponse answerKey(UUID jobId);
}

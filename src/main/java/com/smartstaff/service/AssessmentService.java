package com.smartstaff.service;

import com.smartstaff.dto.request.AssessmentGenerateRequest;
import com.smartstaff.dto.response.*;
import com.smartstaff.entity.User;

import java.util.UUID;

public interface AssessmentService {

    /** The candidates who have taken (or are taking) this job's assessment.
     *  Scores land in F9; each row reports status and how many questions were
     *  answered, not a mark. */
    AssessmentSubmissionsResponse submissions(UUID jobId);

    AssessmentStatusResponse status(UUID jobId);

    AssessmentGenerateResponse generate(AssessmentGenerateRequest req, User admin);

    AnswerKeyResponse answerKey(UUID jobId);
}

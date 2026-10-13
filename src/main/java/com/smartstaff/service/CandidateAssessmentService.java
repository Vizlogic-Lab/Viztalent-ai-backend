package com.smartstaff.service;

import com.smartstaff.dto.request.AssessmentAnswerInput;
import com.smartstaff.dto.request.AssessmentSaveRequest;
import com.smartstaff.dto.request.AttemptEventsRequest;
import com.smartstaff.dto.response.AnswerSavedResponse;
import com.smartstaff.dto.response.AssessmentRunResponse;
import com.smartstaff.dto.response.AttemptEventsResponse;
import com.smartstaff.dto.response.CandidateAssessmentResponse;

import java.util.UUID;

/** The candidate-facing assessment flow, authorised only by the single-use
 *  invite token. One attempt per invite level, served in order; the invite is
 *  consumed when its last level is submitted. */
public interface CandidateAssessmentService {

    /** GET: start the current level on first open, or resume it. */
    CandidateAssessmentResponse start(String token);

    /** PUT: autosave one answer of the current level. */
    AnswerSavedResponse saveAnswer(String token, UUID questionId, AssessmentAnswerInput input);

    /** Bulk autosave (the old save_by_token alias). */
    CandidateAssessmentResponse saveAll(String token, AssessmentSaveRequest req);

    /** POST run: the candidate's code against the VISIBLE tests only (capped per question). */
    AssessmentRunResponse run(String token, UUID questionId, String language, String code);

    /** POST submit: finish the current level (exactly one concurrent submit wins). */
    CandidateAssessmentResponse submit(String token, AssessmentSaveRequest req);

    /** POST events: store integrity events (capped per attempt). */
    AttemptEventsResponse recordEvents(String token, AttemptEventsRequest req);

    /** Scheduler: submit every in-progress attempt whose deadline has passed. */
    int autoSubmitOverdue();

    /** The checks this test uses, for the consent screen; never starts the clock. */
    com.smartstaff.proctoring.CandidateProctoringView proctoring(String token);
}

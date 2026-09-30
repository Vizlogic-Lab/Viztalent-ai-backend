package com.smartstaff.service;

import com.smartstaff.dto.request.AssessmentRunRequest;
import com.smartstaff.dto.request.AssessmentSaveRequest;
import com.smartstaff.dto.response.AssessmentRunResponse;
import com.smartstaff.dto.response.CandidateAssessmentResponse;

/** The candidate-facing assessment-taking flow, driven entirely by the invite
 *  token (no login). Mirrors the interview by_token pattern: the link is
 *  consumed only at submit, so the candidate may resume until then. */
public interface CandidateAssessmentService {

    /** Start or resume the attempt for this token. */
    CandidateAssessmentResponse byToken(String token);

    /** Autosave draft answers without finalising. */
    CandidateAssessmentResponse save(String token, AssessmentSaveRequest req);

    /** Finalise: store the answers, consume the invite, mark the attempt submitted. */
    CandidateAssessmentResponse submit(String token, AssessmentSaveRequest req);

    /** Run the candidate's code for one question against its VISIBLE tests only. */
    AssessmentRunResponse run(String token, AssessmentRunRequest req);
}

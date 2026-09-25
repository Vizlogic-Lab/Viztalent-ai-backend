package com.smartstaff.dto.response;

import java.util.List;

/** POST /api/interview/prepare response — also reused as-is for
 *  GET /api/interview/by_token/{token} (CandidateInterview.jsx reads exactly
 *  this same shape, just never looks at interview_id). See InterviewRoom.jsx's
 *  prep state and CandidateInterview.jsx's prep state. */
public record InterviewPrepareResponse(
        String interview_id,
        String role_title,
        String candidate_name,
        String language,
        String intro,
        String outro,
        List<InterviewQuestionResponse> questions
) {}

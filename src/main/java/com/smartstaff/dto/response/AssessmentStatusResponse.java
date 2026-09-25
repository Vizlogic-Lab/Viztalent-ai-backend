package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** GET /api/assessment/status/{jobId} — see Candidates.jsx's
 *  AssessmentResults section (status.has_jd/num_questions/num_submissions/
 *  latest_submission_at/jd_title) and EmailPreviewModal.generateAssessment's
 *  poll loop (status.ready/generating/error). Generation here is
 *  synchronous (see AssessmentServiceImpl), so `ready` is always true and
 *  `generating` always false by the time this is ever polled — the fields
 *  still exist because the frontend's poll loop checks them unconditionally. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessmentStatusResponse(
        boolean ok,
        boolean has_jd,
        String jd_title,
        int num_questions,
        int num_submissions,
        Instant latest_submission_at,
        String assessment_url,
        boolean ready,
        boolean generating,
        String error
) {}

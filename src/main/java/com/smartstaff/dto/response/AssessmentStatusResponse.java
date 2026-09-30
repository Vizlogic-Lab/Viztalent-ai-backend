package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartstaff.entity.GenerationProgress;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** GET /api/assessment/status/{jobId}. The generation fields describe the
 *  latest version (the one being or last generated); current_version is the
 *  READY version invites use, which may be older while a regeneration runs.
 *  num_questions / counts are questions saved so far in the latest version. */
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
        String error,
        String assessment_id,
        Integer version,
        String status,
        Integer current_version,
        Map<String, Integer> counts,
        Integer slots_total,
        Integer slots_done,
        List<GenerationProgress.UnfilledSlot> unfilled,
        List<String> errors
) {}

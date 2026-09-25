package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Covers both response shapes VoiceScreening.jsx's uploadJDFile/applyJDUploadSuccess
 *  handle: status:"success" (job_id/jd_title/jd_number_display/...) and
 *  status:"duplicate" (existing_job_id/existing_jd_title/...). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JdUploadResponse(
        String status,
        String suggested_reply,
        String job_id,
        String jd_title,
        String jd_number_display,
        String assessment_url,
        List<EvictedJobResponse> evicted_jobs,
        String existing_job_id,
        String existing_jd_title,
        String existing_jd_number_display,
        boolean has_jd
) {}

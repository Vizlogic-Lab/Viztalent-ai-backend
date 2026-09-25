package com.smartstaff.dto.response;

/** See VoiceScreening.jsx's applyJDUploadSuccess: data.evicted_jobs[].job_id/
 *  jd_number/jd_title (the frontend builds "JD-0042" itself from jd_number). */
public record EvictedJobResponse(String job_id, Long jd_number, String jd_title) {}

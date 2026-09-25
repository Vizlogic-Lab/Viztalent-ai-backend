package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** One row in the Jobs page table — see Jobs.jsx's table columns. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobSummaryResponse(
        String job_id,
        Long jd_number,
        String jd_number_display,
        String jd_title,
        String role_title,
        String jd_filename,
        List<String> skills,
        int skills_count,
        String owner_id,
        String owner_name,
        String owner_email,
        String owner_role,
        Instant created_at,
        String assessment_url,
        int interviews_count,
        int submissions_count
) {}

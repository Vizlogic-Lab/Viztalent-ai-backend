package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** See Jobs.jsx's JobDetailModal and Candidates.jsx's fetchSubmissions (which
 *  sources its candidate table from jobRes.data.candidates). submissions/
 *  interviews are only ever read for their .length until Phase 6/7 add those
 *  tables — empty arrays are a safe, contract-correct placeholder. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobDetailResponse(
        boolean ok,
        String job_id,
        String jd_number_display,
        String jd_title,
        String jd_filename,
        Instant created_at,
        String jd_text,
        JdStructResponse jd_struct,
        List<CandidateRowResponse> candidates,
        List<Object> submissions,
        List<Object> interviews,
        Map<String, String> assessment_urls
) {}

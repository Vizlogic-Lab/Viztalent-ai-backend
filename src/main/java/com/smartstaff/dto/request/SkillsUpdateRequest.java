package com.smartstaff.dto.request;

import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Request to update must/nice skills and experience range for a job.
 * Does NOT automatically trigger re-screening.
 */
public record SkillsUpdateRequest(
        List<String> must_have_skills,
        List<String> nice_to_have_skills,
        @Min(0) Integer experience_min_years,
        @Min(0) Integer experience_max_years
) {}

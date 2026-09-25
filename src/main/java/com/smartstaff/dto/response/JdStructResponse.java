package com.smartstaff.dto.response;

import java.util.List;

/** See Jobs.jsx's JobDetailModal: struct.experience_min_years/max_years,
 *  struct.preferred_domains, struct.critical_skills, struct.important_skills. */
public record JdStructResponse(
        Integer experience_min_years,
        Integer experience_max_years,
        List<String> preferred_domains,
        List<String> critical_skills,
        List<String> important_skills
) {}

package com.smartstaff.dto.request;

import java.util.List;
import java.util.Map;

/** PUT /api/jobs/{id}/role_profile — HR correction. Null fields are left unchanged. */
public record RoleProfileRequest(
        String role_family,
        Boolean is_technical,
        List<String> languages,
        List<String> frameworks,
        String seniority,
        Map<String, Integer> skill_weights
) {}

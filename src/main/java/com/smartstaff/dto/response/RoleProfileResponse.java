package com.smartstaff.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** GET /api/jobs/{id}/role_profile, and `role_profile` in GET /api/jobs/{id}.
 *  source: AI | AI_FALLBACK | HR. */
public record RoleProfileResponse(
        String role_family,
        boolean is_technical,
        List<String> languages,
        List<String> frameworks,
        String seniority,
        Map<String, Integer> skill_weights,
        String source,
        String edited_by,
        Instant updated_at
) {}

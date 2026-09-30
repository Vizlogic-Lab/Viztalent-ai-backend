package com.smartstaff.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Role profile for a job: extracted or HR-edited skill expectations.
 *
 * @param roleFamily The job role category (BACKEND, FRONTEND, etc.)
 * @param isTechnical Whether this is a technical role
 * @param languages Preferred programming languages
 * @param frameworks Preferred frameworks/libraries
 * @param seniority Expected seniority level (JUNIOR, MID, SENIOR, LEAD)
 * @param skillWeights Map of skill -> weight (1-5)
 * @param source How the profile was created (AI, AI_FALLBACK, HR)
 */
public record RoleProfileResponse(
        String roleFamily,
        Boolean isTechnical,
        List<String> languages,
        List<String> frameworks,
        String seniority,
        Map<String, Integer> skillWeights,
        String source
) {}

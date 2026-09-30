package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/**
 * HR edit request for a job's role profile.
 * All fields are optional (partial updates allowed).
 */
public record RoleProfileRequest(
        String roleFamily,
        Boolean isTechnical,
        List<String> languages,
        List<String> frameworks,
        String seniority,
        Map<String, Integer> skillWeights
) {}

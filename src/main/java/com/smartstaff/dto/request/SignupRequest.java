package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Covers both shapes the frontend sends: admin signup {role,name,email,password}
 *  and employee signup {role,name,employee_id,email,password}. Fields not
 *  required by a given role (reviewer_skills: role=reviewer only, F11) are simply left null by the caller. */
public record SignupRequest(
        @NotBlank String role,
        String name,
        String email,
        String employee_id,
        @NotBlank String password,
        List<String> reviewer_skills
) {}

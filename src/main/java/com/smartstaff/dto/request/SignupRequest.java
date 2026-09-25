package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Covers both shapes the frontend sends: admin signup {role,name,email,password}
 *  and employee signup {role,name,employee_id,email,password}. Fields not
 *  required by a given role are simply left null by the caller. */
public record SignupRequest(
        @NotBlank String role,
        String name,
        String email,
        String employee_id,
        @NotBlank String password
) {}

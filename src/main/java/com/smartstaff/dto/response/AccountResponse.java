package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** The account shape the frontend reads everywhere (App.jsx checks
 *  account.role === 'admin', Sidebar shows employee_id, Employees page
 *  reads approved/last_seen_at/last_login_at/created_at). Role is
 *  deliberately lowercase to match the frontend's string comparisons. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountResponse(
        String id,
        String role,
        String name,
        String email,
        String employee_id,
        Boolean approved,
        Instant last_seen_at,
        Instant last_login_at,
        Instant created_at
) {}

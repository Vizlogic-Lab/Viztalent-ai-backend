package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record InviteMintRequest(
        @NotBlank String session_id,
        @NotBlank String candidate_email,
        String candidate_name,
        @NotEmpty List<String> levels,
        boolean combined,
        /** F17: also email the link from the backend (when SMTP is configured). */
        Boolean send_email
) {}

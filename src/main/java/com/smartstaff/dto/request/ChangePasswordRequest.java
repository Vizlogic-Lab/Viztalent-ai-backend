package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to change the authenticated user's password.
 *
 * Password policy:
 * - At least 10 characters
 * - At least 1 letter (a-z or A-Z)
 * - At least 1 digit (0-9)
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String current,
        @NotBlank(message = "New password is required")
        String new_password
) {}

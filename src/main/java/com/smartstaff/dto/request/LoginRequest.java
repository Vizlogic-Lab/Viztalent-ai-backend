package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String role,
        @NotBlank String identifier,
        @NotBlank String password
) {}

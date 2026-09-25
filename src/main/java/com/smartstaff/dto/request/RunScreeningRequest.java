package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RunScreeningRequest(@NotBlank String session_id) {}

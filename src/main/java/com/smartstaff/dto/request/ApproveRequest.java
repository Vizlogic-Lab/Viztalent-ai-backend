package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApproveRequest(
        @NotBlank String employee_id,
        @NotNull Boolean approved
) {}

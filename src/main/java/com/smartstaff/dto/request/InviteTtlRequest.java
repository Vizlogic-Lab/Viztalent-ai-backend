package com.smartstaff.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record InviteTtlRequest(@NotNull @Min(60) Long ttl_seconds, boolean persist) {}

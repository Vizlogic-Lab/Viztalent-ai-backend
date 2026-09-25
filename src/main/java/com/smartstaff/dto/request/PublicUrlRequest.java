package com.smartstaff.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PublicUrlRequest(@NotBlank String public_base_url, boolean persist) {}

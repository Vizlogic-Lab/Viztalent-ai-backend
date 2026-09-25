package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiConfigInfo(boolean configured, boolean has_key, String model, String key_preview) {}

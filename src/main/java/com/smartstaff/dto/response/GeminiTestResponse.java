package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiTestResponse(boolean ok, String message, String model, String sample) {}

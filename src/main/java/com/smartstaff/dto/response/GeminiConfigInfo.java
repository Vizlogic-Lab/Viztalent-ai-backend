package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
/** key_source: "environment" (GEMINI_API_KEY, wins), "settings" (saved, encrypted) or null. */
public record GeminiConfigInfo(boolean configured, boolean has_key, String model, String key_preview, String key_source) {}

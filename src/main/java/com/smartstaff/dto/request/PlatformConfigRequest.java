package com.smartstaff.dto.request;

import java.util.List;

/** See VoiceScreening.jsx's universal_execute call — persona becomes the
 *  Gemini system instruction, available_tools become function declarations. */
public record PlatformConfigRequest(String persona, String industry, List<ToolDeclarationRequest> available_tools) {}

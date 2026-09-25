package com.smartstaff.dto.request;

/** One entry of platform_config.available_tools — see VoiceScreening.jsx's
 *  universal_execute call. expected_params is a free-form, comma-separated
 *  list of parameter names (e.g. "job_description"), not a JSON schema — the
 *  original app's convention, translated into a real Gemini function-calling
 *  schema by RecruiterChatServiceImpl. */
public record ToolDeclarationRequest(String tag_name, String description, String expected_params) {}

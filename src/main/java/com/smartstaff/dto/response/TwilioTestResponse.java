package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TwilioTestResponse(boolean ok, String message, String account_name, String from_number) {}

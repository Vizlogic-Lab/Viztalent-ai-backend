package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartstaff.service.impl.EmailService;

/** email (F17): set when the mint asked for send_email — QUEUED with its id, or NOT_CONFIGURED. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InviteResponse(String level, String url, long remaining_seconds, EmailService.Queued email) {}

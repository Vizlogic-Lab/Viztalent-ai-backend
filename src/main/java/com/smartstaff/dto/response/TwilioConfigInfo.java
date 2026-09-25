package com.smartstaff.dto.response;

public record TwilioConfigInfo(
        boolean configured,
        String account_sid_preview,
        String auth_token_preview,
        String from_number
) {}

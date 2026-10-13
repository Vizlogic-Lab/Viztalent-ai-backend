package com.smartstaff.dto.response;

/** POST /api/auth/change_password — every earlier token of the account is now
 *  rejected, so the caller gets a fresh one to stay signed in. */
public record ChangePasswordResponse(boolean ok, String token) {}

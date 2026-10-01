package com.smartstaff.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Shape returned by login/signup — see auth.jsx's login()/signup() which
 *  read res.data.ok/token/account, and Login.jsx which reads
 *  res.pending_approval to show the "waiting for approval" banner. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        boolean ok,
        String token,
        AccountResponse account,
        Boolean pending_approval,
        String message,
        String code
) {
    public static AuthResponse success(String token, AccountResponse account) {
        return new AuthResponse(true, token, account, null, null, null);
    }

    /** An account created on someone else's behalf (no token for the creator). */
    public static AuthResponse created(AccountResponse account, String message) {
        return new AuthResponse(true, null, account, null, message, null);
    }

    public static AuthResponse pendingApproval(AccountResponse account, String message) {
        return new AuthResponse(true, null, account, true, message, null);
    }
}

package com.smartstaff.dto.request;

/** Empty-string fields mean "clear this value" — see Settings.jsx's
 *  saveTwilioConfig comment: "backend reads null as leave alone... but our
 *  form sends "" for untouched fields... backend treats empty string as
 *  clear." We instead treat blank as "leave unchanged" (simpler and safer:
 *  a recruiter clearing just the SID field shouldn't wipe a working token),
 *  and clearing all three intentionally blanks the whole config. */
public record TwilioConfigRequest(String account_sid, String auth_token, String from_number, boolean persist) {}

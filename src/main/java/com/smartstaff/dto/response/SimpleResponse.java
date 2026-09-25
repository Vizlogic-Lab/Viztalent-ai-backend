package com.smartstaff.dto.response;

/** Bare {"ok": true} acknowledgement for actions where the frontend doesn't
 *  read anything else back (logout, approve, delete, ...). */
public record SimpleResponse(boolean ok) {
    public static final SimpleResponse OK = new SimpleResponse(true);
}

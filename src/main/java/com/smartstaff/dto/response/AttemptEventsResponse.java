package com.smartstaff.dto.response;

/** stored = events kept; dropped = duplicates, over the rate limit or cap, server-only or unknown types.
 *  warnings = the attempt's proctoring warnings, counted by the server (the UI shows this number). */
public record AttemptEventsResponse(boolean ok, int stored, int dropped, int warnings) {}

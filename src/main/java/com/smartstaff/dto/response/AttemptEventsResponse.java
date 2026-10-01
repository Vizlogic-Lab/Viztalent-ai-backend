package com.smartstaff.dto.response;

/** stored = events kept; dropped = over the per-attempt cap or of unknown type. */
public record AttemptEventsResponse(boolean ok, int stored, int dropped) {}

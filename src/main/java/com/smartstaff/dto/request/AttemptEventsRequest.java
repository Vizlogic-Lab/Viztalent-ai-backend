package com.smartstaff.dto.request;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A batch of integrity events from the candidate page (sent every ~10 s).
 *  type: TAB_SWITCH | PASTE | FOCUS_LOST | FULLSCREEN_EXIT. payload holds facts
 *  only (e.g. {"length": 120} for a paste) — never pasted content. */
public record AttemptEventsRequest(List<Event> events) {
    public record Event(String type, Map<String, Object> payload, Instant at) {}
}

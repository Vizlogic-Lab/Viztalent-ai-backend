package com.smartstaff.dto.response;

import java.time.Instant;

/** See Dashboard.jsx/Analytics.jsx/NotificationBell.jsx: a.kind (jd/resumes/
 *  screening/submission/interview), a.message, a.at. Derived live from
 *  Job/Resume/Candidate timestamps — no separate activity_events table yet
 *  (that's the real Phase 4 scope; this is a minimal read-only projection
 *  pulled forward so Phase 2/3 data is actually visible on the Dashboard). */
public record ActivityEventResponse(String kind, String message, Instant at) {}

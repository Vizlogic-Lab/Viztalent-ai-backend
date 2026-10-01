package com.smartstaff.dto.response;

import java.time.Instant;

/** See Dashboard.jsx/Analytics.jsx/NotificationBell.jsx: a.kind (jd/resumes/
 *  screening/assessment/invite/submission/evaluation/validation/interview),
 *  a.message, a.at. */
public record ActivityEventResponse(String kind, String message, Instant at) {}

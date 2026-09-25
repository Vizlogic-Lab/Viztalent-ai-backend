package com.smartstaff.dto.response;

/** GET /api/progress — polled every 1s while VoiceScreening.jsx shows a
 *  "thinking" state. Scoring here is synchronous and fast (no LLM in the
 *  loop), so there's no real background job to report on; `log` is always
 *  null and the frontend simply shows no live line (it only renders one
 *  when `log` is truthy). Revisit if screening ever becomes async/slow
 *  enough to need real progress. */
public record ProgressResponse(String log) {
    public static final ProgressResponse IDLE = new ProgressResponse(null);
}

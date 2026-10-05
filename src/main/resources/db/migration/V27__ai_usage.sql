-- Gemini usage per day and feature (calls, failures, tokens), for the admin
-- usage view and the daily call cap.
CREATE TABLE ai_usage_daily (
    day           DATE        NOT NULL,
    feature       VARCHAR(32) NOT NULL,
    calls         INTEGER     NOT NULL DEFAULT 0,
    failures      INTEGER     NOT NULL DEFAULT 0,
    prompt_tokens BIGINT      NOT NULL DEFAULT 0,
    output_tokens BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (day, feature)
);

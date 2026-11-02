-- Per-job overrides of the pass marks, by level: {"L1": {"overall_pass": 60, ...}}.
-- Null or a missing level means "use the global value for that level" (app_settings
-- key level_pass_marks), then the built-in defaults.
ALTER TABLE jobs ADD COLUMN level_pass_marks JSONB;

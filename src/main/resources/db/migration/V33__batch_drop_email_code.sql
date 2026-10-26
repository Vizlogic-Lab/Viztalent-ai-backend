-- The batch link no longer emails a one-time code (no SMTP needed): candidates enter with the
-- email and name from their resume, matched against the roster. Drop the code columns.
ALTER TABLE batch_candidates
    DROP COLUMN otp_hash,
    DROP COLUMN otp_expires_at,
    DROP COLUMN otp_sent_at,
    DROP COLUMN otp_attempts,
    DROP COLUMN otp_sends;

-- Phase 8: outbound Twilio phone interviews. Twilio's own call status
-- ("queued", "ringing", "in-progress", "busy", "no-answer", "failed",
-- "canceled", "completed") is a much finer-grained string than the
-- interviews.status tri-state (PENDING/COMPLETED/FAILED) added in V7, and
-- InterviewRoom.jsx's PhoneCallRoom polls for exactly that finer string
-- (see its statusLabel lookup) — so it gets its own free-form column
-- rather than overloading V7's CHECK-constrained status.
ALTER TABLE interviews ADD COLUMN twilio_call_status VARCHAR(32);

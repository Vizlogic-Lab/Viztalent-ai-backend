-- Phase 5: settings — a simple key/value store for backend-wide config.
-- Secret values (gemini_api_key, twilio_auth_token) are AES-GCM encrypted
-- by CryptoService before being written here; everything else is plain.
CREATE TABLE app_settings (
    key        VARCHAR(64) PRIMARY KEY,
    value      TEXT,
    updated_by VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Changing a password must sign the account out everywhere. Each login token
-- carries the account's token_version ("tv" claim); changing the password bumps
-- it, so every token issued before the change is rejected by JwtAuthFilter.
-- Existing tokens have no claim, which counts as version 0 (= this default).
ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;

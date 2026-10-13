-- The bootstrap admin (BOOTSTRAP_ADMIN_EMAIL/PASSWORD) must replace the initial
-- password at first login; until then only /api/auth/me, change_password and
-- logout are allowed (JwtAuthFilter).
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

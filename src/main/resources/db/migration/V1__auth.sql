-- Phase 1: auth module.
-- Admin identifies by email; Employee identifies by employee_id. Both live in
-- one table (role discriminates) since the frontend's login/accounts screens
-- treat them as one pool of "accounts".
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role          VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    name          VARCHAR(255) NOT NULL,
    email         VARCHAR(255),
    employee_id   VARCHAR(64),
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'APPROVED'
                  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    last_login_at TIMESTAMPTZ,
    last_seen_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Admin logs in by email; employee by employee_id — each must be unique
-- within its role, and NULL-safe (a USER row has no email requirement).
CREATE UNIQUE INDEX ux_users_admin_email
    ON users (lower(email))
    WHERE role = 'ADMIN';

CREATE UNIQUE INDEX ux_users_employee_id
    ON users (lower(employee_id))
    WHERE role = 'USER';

-- Demo accounts (admin@smartstaff.demo / EMP1001) are seeded at application
-- startup by DemoDataSeeder, using the real BCryptPasswordEncoder bean —
-- not hardcoded here, so the hash always matches whatever encoder is active.

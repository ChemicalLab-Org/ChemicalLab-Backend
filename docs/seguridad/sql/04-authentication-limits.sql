-- Additive. Execute only in the deployment's approved schema/change window.
CREATE TABLE IF NOT EXISTS authentication_limits (
    bucket varchar(100) PRIMARY KEY,
    attempts integer NOT NULL,
    expires_at bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_limits_expiry ON authentication_limits (expires_at);

-- T02: additive upgrade of an existing installation. No user/profile/history deletion.
-- Use in the operator's approved maintenance window, before starting T02 with ddl-auto=validate.
-- With the existing ddl-auto=update setting, Hibernate applies these same additive mappings.
BEGIN;
ALTER TABLE user_accounts
    ADD COLUMN IF NOT EXISTS credentials_version bigint NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS account_sessions (
    id uuid PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES user_accounts(id),
    credentials_version bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone
);
CREATE INDEX IF NOT EXISTS idx_account_sessions_user ON account_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_account_sessions_expiry ON account_sessions(expires_at);
COMMIT;

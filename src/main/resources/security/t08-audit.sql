-- T08 v1. Run once with all writers stopped, an explicit search_path and one transaction.
-- Historical outcomes/correlation remain NULL: their commit semantics cannot be reconstructed.
LOCK TABLE system_logs IN ACCESS EXCLUSIVE MODE;
ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS outcome varchar(16);
ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS correlation_id uuid;
ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS declared_identifier varchar(80);
CREATE INDEX idx_system_logs_correlation ON system_logs(correlation_id);
CREATE TABLE audit_rejection_budget (
    id integer PRIMARY KEY CHECK(id=1),
    window_start timestamptz NOT NULL,
    used integer NOT NULL CHECK(used BETWEEN 0 AND 120)
);
INSERT INTO audit_rejection_budget VALUES(1,date_trunc('minute',statement_timestamp()),0);
CREATE FUNCTION audit_reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='P7508', MESSAGE='Audit evidence is append-only'; END;
$$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON system_logs
FOR EACH STATEMENT EXECUTE FUNCTION audit_reject_mutation();
CREATE TABLE audit_schema_version(version integer PRIMARY KEY CHECK(version=1));
INSERT INTO audit_schema_version VALUES(1);

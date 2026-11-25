-- Install on a stopped J+K+L database. Preserve all existing rows and ledgers.
BEGIN;
SET LOCAL search_path TO public, pg_catalog;
DO $prerequisites$
BEGIN
  IF (SELECT version FROM evaluation_runtime.contract) IS DISTINCT FROM 1
     OR (SELECT version FROM content_runtime.contract) IS DISTINCT FROM 1
     OR (SELECT version FROM board_runtime.contract) IS DISTINCT FROM 1
     OR (SELECT version FROM audit_schema_version) IS DISTINCT FROM 1 THEN
    RAISE EXCEPTION 'Expected J+K+L runtime and T08';
  END IF;
END $prerequisites$;

ALTER TABLE user_accounts DROP CONSTRAINT IF EXISTS user_accounts_role_check;
ALTER TABLE user_accounts ADD CONSTRAINT user_accounts_role_check
  CHECK (role IN ('ADMINISTRADOR','DOCENTE','ESTUDIANTE','INSTITUCIONAL'));
ALTER TABLE system_logs DROP CONSTRAINT IF EXISTS system_logs_actor_role_check;
ALTER TABLE system_logs ADD CONSTRAINT system_logs_actor_role_check
  CHECK (actor_role IN ('ADMINISTRADOR','DOCENTE','ESTUDIANTE','INSTITUCIONAL'));

CREATE SCHEMA administration_runtime;
CREATE TABLE administration_runtime.contract(version integer PRIMARY KEY CHECK(version=1));
INSERT INTO administration_runtime.contract VALUES (1);

-- One explicit bootstrap on existing installations. The legacy role alone is insufficient.
CREATE TABLE administration_runtime.platform_authority(
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  account_id bigint NOT NULL UNIQUE REFERENCES user_accounts(id)
);

CREATE TABLE administration_runtime.member_names(
  membership_id bigint PRIMARY KEY,
  institution_id bigint NOT NULL,
  display_name varchar(200) NOT NULL,
  FOREIGN KEY(membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id)
);

CREATE TABLE administration_runtime.changes(
  audit_id bigint PRIMARY KEY REFERENCES system_logs(id),
  institution_id bigint NOT NULL REFERENCES evaluation_runtime.institutions(id),
  actor_id bigint NOT NULL REFERENCES user_accounts(id),
  subject_membership_id bigint,
  action text NOT NULL,
  target_id bigint NOT NULL,
  FOREIGN KEY(subject_membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id)
);
CREATE INDEX administration_change_scope ON administration_runtime.changes(institution_id,audit_id DESC);
CREATE INDEX administration_change_subject ON administration_runtime.changes(institution_id,subject_membership_id,audit_id DESC);
CREATE TRIGGER administration_history BEFORE UPDATE OR DELETE ON administration_runtime.changes
  FOR EACH ROW EXECUTE FUNCTION evaluation_runtime.retain_history();
CREATE TRIGGER administration_history_truncate BEFORE TRUNCATE ON administration_runtime.changes
  FOR EACH STATEMENT EXECUTE FUNCTION evaluation_runtime.retain_history();

CREATE FUNCTION administration_runtime.verify_evidence() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.system_logs l WHERE l.id=NEW.audit_id
      AND l.actor_user_id=NEW.actor_id AND l.outcome='SUCCESS' AND l.action=NEW.action
      AND l.target_id=NEW.target_id AND l.metadata='institutionId='||NEW.institution_id
      AND l.xmin=pg_current_xact_id()::text::xid) THEN
    RAISE EXCEPTION 'Scoped administration requires same-transaction T08 evidence';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER administration_evidence BEFORE INSERT ON administration_runtime.changes
  FOR EACH ROW EXECUTE FUNCTION administration_runtime.verify_evidence();

CREATE INDEX administration_periods_scope ON evaluation_runtime.periods(institution_id,id);
CREATE INDEX administration_classes_scope ON evaluation_runtime.classes(institution_id,id);
CREATE INDEX administration_memberships_scope ON evaluation_runtime.memberships(institution_id,id);
CREATE INDEX administration_teaching_scope ON evaluation_runtime.teaching(institution_id,id);
CREATE INDEX administration_enrollments_scope ON evaluation_runtime.enrollments(institution_id,id);
CREATE INDEX administration_evaluation_faculties_scope ON evaluation_runtime.faculties(institution_id,id);
CREATE INDEX administration_content_faculties_scope ON content_runtime.faculties(institution_id,id);
CREATE INDEX administration_evaluation_activity ON evaluation_runtime.operations(institution_id,audit_id DESC)
  WHERE audit_id IS NOT NULL;
CREATE INDEX administration_content_activity ON content_runtime.operations(institution_id,audit_id DESC)
  WHERE audit_id IS NOT NULL;
CREATE INDEX administration_board_activity ON board_runtime.operations(institution_id,audit_id DESC)
  WHERE audit_id IS NOT NULL;
COMMIT;

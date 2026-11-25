-- Apply once, as the database owner, with application writers stopped.
BEGIN;
SET LOCAL search_path TO public, pg_catalog;
LOCK TABLE evaluations, evaluation_attempts, evaluation_assignments IN ACCESS EXCLUSIVE MODE;
DO $installation$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname LIKE 't03\_%' ESCAPE '\')
     OR to_regclass('t11b1_schema_version') IS NOT NULL THEN
    RAISE EXCEPTION 'Install separately from certified fixtures';
  END IF;
  IF EXISTS (SELECT 1 FROM evaluations) OR EXISTS (SELECT 1 FROM evaluation_attempts)
      OR EXISTS (SELECT 1 FROM evaluation_assignments) THEN
    RAISE EXCEPTION 'Historical evaluation cutover requires approved F08 mapping and a verified writer transition';
  END IF;
END $installation$;

CREATE SCHEMA evaluation_runtime;
CREATE TABLE evaluation_runtime.contract(version integer PRIMARY KEY CHECK (version=1));
INSERT INTO evaluation_runtime.contract VALUES (1);

CREATE TABLE evaluation_runtime.institutions(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name varchar(200) NOT NULL,
  active boolean NOT NULL DEFAULT true
);
CREATE TABLE evaluation_runtime.periods(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, institution_id bigint NOT NULL REFERENCES evaluation_runtime.institutions,
  name varchar(200) NOT NULL, open boolean NOT NULL DEFAULT true,
  valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK (valid_until>valid_from),
  UNIQUE(id,institution_id)
);
CREATE TABLE evaluation_runtime.classes(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, institution_id bigint NOT NULL,
  period_id bigint NOT NULL, code varchar(64) NOT NULL, name varchar(200) NOT NULL, active boolean NOT NULL DEFAULT true,
  FOREIGN KEY(period_id,institution_id) REFERENCES evaluation_runtime.periods(id,institution_id),
  UNIQUE(id,institution_id), UNIQUE(period_id,code)
);
CREATE TABLE evaluation_runtime.memberships(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, institution_id bigint NOT NULL REFERENCES evaluation_runtime.institutions,
  account_id bigint NOT NULL REFERENCES user_accounts, active boolean NOT NULL DEFAULT true,
  valid_from timestamptz NOT NULL, valid_until timestamptz CHECK (valid_until>valid_from),
  UNIQUE(institution_id,account_id), UNIQUE(id,institution_id), UNIQUE(id,institution_id,account_id)
);
CREATE INDEX runtime_memberships_account ON evaluation_runtime.memberships(account_id,institution_id);
CREATE TABLE evaluation_runtime.roles(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  membership_id bigint NOT NULL, institution_id bigint NOT NULL,
  role varchar(24) NOT NULL CHECK(role IN ('ESTUDIANTE','DOCENTE','ADMIN_INSTITUCION')),
  valid_from timestamptz NOT NULL, valid_until timestamptz CHECK(valid_until>valid_from), revoked_at timestamptz,
  FOREIGN KEY(membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id)
);
CREATE UNIQUE INDEX runtime_open_role ON evaluation_runtime.roles(membership_id,role) WHERE revoked_at IS NULL;
CREATE TABLE evaluation_runtime.teaching(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, institution_id bigint NOT NULL,
  class_id bigint NOT NULL, membership_id bigint NOT NULL, faculty varchar(16) NOT NULL CHECK(faculty IN ('RESPONSABLE','CO_DOCENTE')),
  valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK(valid_until>valid_from), ended_at timestamptz,
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id)
);
CREATE INDEX runtime_teaching_lookup ON evaluation_runtime.teaching(class_id,membership_id);
CREATE UNIQUE INDEX runtime_teaching_current ON evaluation_runtime.teaching(class_id,membership_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX runtime_responsible_current ON evaluation_runtime.teaching(class_id)
  WHERE ended_at IS NULL AND faculty='RESPONSABLE';
CREATE TABLE evaluation_runtime.enrollments(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, institution_id bigint NOT NULL,
  class_id bigint NOT NULL, membership_id bigint NOT NULL, account_id bigint NOT NULL,
  valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK(valid_until>valid_from), ended_at timestamptz,
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(membership_id,institution_id,account_id) REFERENCES evaluation_runtime.memberships(id,institution_id,account_id),
  UNIQUE(id,institution_id,class_id,account_id)
);
CREATE INDEX runtime_enrollments_lookup ON evaluation_runtime.enrollments(class_id,membership_id);
CREATE UNIQUE INDEX runtime_enrollment_open ON evaluation_runtime.enrollments(class_id,account_id) WHERE ended_at IS NULL;

-- Namespace disjoint from the historical tables. No bridge from a legacy PK or a fixture PK.
CREATE TABLE evaluation_runtime.resources(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 1000000000) PRIMARY KEY,
  institution_id bigint NOT NULL, origin_class_id bigint NOT NULL,
  author_account_id bigint NOT NULL, author_membership_id bigint NOT NULL,
  max_attempts integer NOT NULL CHECK(max_attempts BETWEEN 1 AND 10),
  creation varchar(16) NOT NULL DEFAULT 'NATIVE_V1' CHECK (creation='NATIVE_V1'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(origin_class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(author_membership_id,institution_id,author_account_id)
    REFERENCES evaluation_runtime.memberships(id,institution_id,account_id),
  UNIQUE(id,institution_id)
);
CREATE INDEX runtime_resources_institution ON evaluation_runtime.resources(institution_id,id);
CREATE TABLE evaluation_runtime.revisions(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 1000000000) PRIMARY KEY,
  resource_id bigint NOT NULL, institution_id bigint NOT NULL, parent_id bigint,
  revision_number integer NOT NULL CHECK(revision_number>0), version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
  payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
  payload_checksum text GENERATED ALWAYS AS (md5(payload::text)) STORED,
  published_at timestamptz,
  FOREIGN KEY(resource_id,institution_id) REFERENCES evaluation_runtime.resources(id,institution_id),
  UNIQUE(id,resource_id,institution_id), UNIQUE(id,resource_id,institution_id,payload_checksum),
  FOREIGN KEY(parent_id,resource_id,institution_id) REFERENCES evaluation_runtime.revisions(id,resource_id,institution_id),
  UNIQUE(resource_id,revision_number), CHECK((status='PUBLISHED') = (published_at IS NOT NULL))
);
CREATE UNIQUE INDEX runtime_one_draft ON evaluation_runtime.revisions(resource_id) WHERE status='DRAFT';
CREATE TABLE evaluation_runtime.faculties(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  resource_id bigint NOT NULL, institution_id bigint NOT NULL, membership_id bigint NOT NULL,
  faculty varchar(16) NOT NULL CHECK(faculty IN ('EDIT_DRAFT','PUBLISH','ASSIGN')),
  granted_by_membership_id bigint NOT NULL, valid_from timestamptz NOT NULL,
  valid_until timestamptz NOT NULL CHECK(valid_until>valid_from), revoked_at timestamptz,
  FOREIGN KEY(resource_id,institution_id) REFERENCES evaluation_runtime.resources(id,institution_id),
  FOREIGN KEY(membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id),
  FOREIGN KEY(granted_by_membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id)
);
CREATE UNIQUE INDEX runtime_open_faculty ON evaluation_runtime.faculties(resource_id,membership_id,faculty) WHERE revoked_at IS NULL;
CREATE TABLE evaluation_runtime.deliveries(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 1000000000) PRIMARY KEY,
  resource_id bigint NOT NULL, revision_id bigint NOT NULL, institution_id bigint NOT NULL, class_id bigint NOT NULL,
  starts_at timestamptz NOT NULL, due_at timestamptz NOT NULL CHECK(due_at>starts_at),
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(revision_id,resource_id,institution_id) REFERENCES evaluation_runtime.revisions(id,resource_id,institution_id),
  UNIQUE(revision_id,class_id), UNIQUE(id,resource_id,revision_id,institution_id,class_id)
);
CREATE INDEX runtime_deliveries_class ON evaluation_runtime.deliveries(class_id,id);
CREATE INDEX runtime_deliveries_window ON evaluation_runtime.deliveries(resource_id,due_at);
CREATE TABLE evaluation_runtime.recipients(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  delivery_id bigint NOT NULL, resource_id bigint NOT NULL, revision_id bigint NOT NULL,
  institution_id bigint NOT NULL, class_id bigint NOT NULL, enrollment_id bigint NOT NULL, account_id bigint NOT NULL,
  FOREIGN KEY(delivery_id,resource_id,revision_id,institution_id,class_id)
    REFERENCES evaluation_runtime.deliveries(id,resource_id,revision_id,institution_id,class_id),
  FOREIGN KEY(enrollment_id,institution_id,class_id,account_id)
    REFERENCES evaluation_runtime.enrollments(id,institution_id,class_id,account_id),
  UNIQUE(delivery_id,account_id),
  UNIQUE(id,delivery_id,resource_id,revision_id,institution_id,class_id,enrollment_id,account_id)
);
CREATE INDEX runtime_recipients_student ON evaluation_runtime.recipients(account_id,class_id,delivery_id);
CREATE TABLE evaluation_runtime.attempts(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 1000000000) PRIMARY KEY,
  resource_id bigint NOT NULL, evaluation_id bigint NOT NULL CHECK(evaluation_id=resource_id),
  institution_id bigint NOT NULL, class_id bigint NOT NULL, delivery_id bigint NOT NULL, revision_id bigint NOT NULL,
  recipient_id bigint NOT NULL, account_id bigint NOT NULL, enrollment_id bigint NOT NULL,
  root_version bigint NOT NULL CHECK(root_version=1), attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 1 AND 10),
  payload_checksum text NOT NULL, configuration jsonb NOT NULL, question_order jsonb NOT NULL,
  started_at timestamptz NOT NULL, deadline timestamptz NOT NULL CHECK(deadline>started_at),
  status varchar(24) NOT NULL DEFAULT 'IN_PROGRESS' CHECK(status IN ('IN_PROGRESS','PENDING_MANUAL_REVIEW','GRADED')),
  answers jsonb NOT NULL DEFAULT '{}', adjustments jsonb NOT NULL DEFAULT '[]',
  current_question_index integer NOT NULL DEFAULT 0 CHECK(current_question_index>=0),
  score integer, max_score integer, final_score numeric(4,2) CHECK(final_score BETWEEN 0 AND 20),
  overall_feedback varchar(1500), submitted_at timestamptz, graded_at timestamptz,
  grade_closed boolean NOT NULL DEFAULT false, grade_closed_at timestamptz, grade_closed_by bigint REFERENCES user_accounts,
  FOREIGN KEY(recipient_id,delivery_id,resource_id,revision_id,institution_id,class_id,enrollment_id,account_id)
    REFERENCES evaluation_runtime.recipients(id,delivery_id,resource_id,revision_id,institution_id,class_id,enrollment_id,account_id),
  FOREIGN KEY(revision_id,resource_id,institution_id,payload_checksum)
    REFERENCES evaluation_runtime.revisions(id,resource_id,institution_id,payload_checksum),
  UNIQUE(resource_id,account_id,attempt_number),
  CHECK ((status='IN_PROGRESS') = (submitted_at IS NULL)),
  CHECK ((status='GRADED') = (graded_at IS NOT NULL)),
  CHECK (grade_closed=(grade_closed_at IS NOT NULL) AND (NOT grade_closed OR status='GRADED'))
);
CREATE UNIQUE INDEX runtime_one_active_attempt ON evaluation_runtime.attempts(resource_id,account_id) WHERE status='IN_PROGRESS';
CREATE INDEX runtime_attempts_class ON evaluation_runtime.attempts(class_id,id);
CREATE INDEX runtime_attempts_student ON evaluation_runtime.attempts(delivery_id,account_id,id);
CREATE INDEX runtime_attempts_open_grades ON evaluation_runtime.attempts(resource_id) WHERE NOT grade_closed;

CREATE TABLE evaluation_runtime.operations(
  operation_id uuid PRIMARY KEY, institution_id bigint NOT NULL, class_id bigint NOT NULL,
  actor_id bigint NOT NULL REFERENCES user_accounts, evaluation_id bigint REFERENCES evaluation_runtime.resources,
  action varchar(40) NOT NULL, request_hash text NOT NULL CHECK(length(request_hash)=64),
  result jsonb, audit_id bigint, committed_at timestamptz,
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  CHECK ((result IS NULL)=(audit_id IS NULL) AND (result IS NULL)=(committed_at IS NULL))
);
CREATE TABLE evaluation_runtime.quotas(
  institution_id bigint PRIMARY KEY REFERENCES evaluation_runtime.institutions,
  max_rows bigint NOT NULL CHECK(max_rows>0), max_bytes bigint NOT NULL CHECK(max_bytes>0),
  used_rows bigint NOT NULL DEFAULT 0 CHECK(used_rows>=0), used_bytes bigint NOT NULL DEFAULT 0 CHECK(used_bytes>=0),
  CHECK(used_rows<=max_rows AND used_bytes<=max_bytes)
);

CREATE FUNCTION evaluation_runtime.institution_lock(id bigint) RETURNS bigint
  LANGUAGE sql IMMUTABLE AS $$ SELECT hashtextextended('chemical-lab/context/'||id,0) $$;

-- Entitlements can change through maintenance and direct operator SQL. DDL and table-owner
-- access are operational privileges. Row data writers cannot move an identity to another scope.
CREATE FUNCTION evaluation_runtime.guard_scope() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
DECLARE scope_id bigint;
BEGIN
  IF TG_TABLE_NAME='institutions' THEN
    IF TG_OP='UPDATE' AND OLD.id<>NEW.id THEN RAISE EXCEPTION 'Immutable institution identity'; END IF;
    scope_id := CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
  ELSE
    IF TG_OP='UPDATE' AND OLD.institution_id<>NEW.institution_id THEN RAISE EXCEPTION 'Immutable scope'; END IF;
    scope_id := CASE WHEN TG_OP='DELETE' THEN OLD.institution_id ELSE NEW.institution_id END;
  END IF;
  PERFORM pg_advisory_xact_lock(evaluation_runtime.institution_lock(scope_id));
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'End a relationship explicitly'; END IF;
  IF TG_TABLE_NAME='faculties' AND TG_OP='INSERT' THEN
    IF NOT EXISTS (
      SELECT 1 FROM evaluation_runtime.memberships m JOIN evaluation_runtime.roles g ON g.membership_id=m.id
      JOIN user_accounts u ON u.id=m.account_id
      WHERE m.id=NEW.granted_by_membership_id AND m.institution_id=scope_id AND m.active AND u.active
        AND g.role='ADMIN_INSTITUCION' AND g.revoked_at IS NULL
        AND m.valid_from<=statement_timestamp() AND (m.valid_until IS NULL OR statement_timestamp()<m.valid_until)
        AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR statement_timestamp()<g.valid_until)
    ) THEN RAISE EXCEPTION 'Current institutional grantor required'; END IF;
  END IF;
  IF TG_OP='UPDATE' THEN
    IF TG_TABLE_NAME='enrollments' OR TG_TABLE_NAME='teaching' THEN
      IF (to_jsonb(OLD)-'ended_at') IS DISTINCT FROM (to_jsonb(NEW)-'ended_at')
           OR OLD.ended_at IS NOT NULL THEN RAISE EXCEPTION 'Immutable episode'; END IF;
    ELSIF TG_TABLE_NAME='memberships' AND (to_jsonb(OLD)-ARRAY['active','valid_until']) IS DISTINCT FROM (to_jsonb(NEW)-ARRAY['active','valid_until']) THEN
      RAISE EXCEPTION 'Immutable membership identity';
    ELSIF TG_TABLE_NAME IN ('roles','faculties') AND
      ((to_jsonb(OLD)-'revoked_at') IS DISTINCT FROM (to_jsonb(NEW)-'revoked_at') OR OLD.revoked_at IS NOT NULL) THEN
      RAISE EXCEPTION 'Immutable grant';
    ELSIF TG_TABLE_NAME='classes' AND (to_jsonb(OLD)-'active') IS DISTINCT FROM (to_jsonb(NEW)-'active') THEN
      RAISE EXCEPTION 'Immutable class identity';
    END IF;
  END IF;
  RETURN NEW;
END $$;
DO $guards$
DECLARE n text;
BEGIN
  FOREACH n IN ARRAY ARRAY['institutions','periods','classes','memberships','roles','teaching','enrollments','faculties'] LOOP
    EXECUTE format('CREATE TRIGGER runtime_scope BEFORE INSERT OR UPDATE OR DELETE ON evaluation_runtime.%I FOR EACH ROW EXECUTE FUNCTION evaluation_runtime.guard_scope()',n);
  END LOOP;
END $guards$;

CREATE FUNCTION evaluation_runtime.guard_academic() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op evaluation_runtime.operations%ROWTYPE; expected text; root bigint;
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Immutable academic history'; END IF;
  SELECT * INTO STRICT op FROM evaluation_runtime.operations
    WHERE operation_id=nullif(current_setting('evaluation_runtime.operation',true),'')::uuid
      AND result IS NULL AND xmin=pg_current_xact_id()::text::xid;
  root := CASE WHEN TG_TABLE_NAME='resources' THEN NEW.id ELSE (to_jsonb(NEW)->>'resource_id')::bigint END;
  IF op.institution_id<>NEW.institution_id OR (op.evaluation_id IS DISTINCT FROM root AND op.action<>'CREATE_NEW_AUTHORING') THEN
    RAISE EXCEPTION 'Command scope mismatch';
  END IF;
  IF TG_TABLE_NAME='resources' THEN
    IF TG_OP<>'INSERT' OR op.action<>'CREATE_NEW_AUTHORING' OR NEW.origin_class_id<>op.class_id
        OR NEW.author_account_id<>op.actor_id THEN RAISE EXCEPTION 'Native creation required'; END IF;
  ELSIF TG_TABLE_NAME='revisions' THEN
    IF TG_OP='INSERT' THEN
      IF NEW.status<>'DRAFT' OR op.action NOT IN ('CREATE_NEW_AUTHORING','SUCCESSOR_NEW_AUTHORING') THEN
        RAISE EXCEPTION 'Draft creation required';
      END IF;
    ELSIF OLD.status='PUBLISHED' OR
      (to_jsonb(OLD)-ARRAY['payload','payload_checksum','status','version','published_at']) IS DISTINCT FROM
      (to_jsonb(NEW)-ARRAY['payload','payload_checksum','status','version','published_at'])
      OR NEW.version<>OLD.version+1 THEN
      RAISE EXCEPTION 'Published revision or immutable identity';
    ELSIF NOT ((op.action='EDIT_NEW_AUTHORING' AND NEW.status='DRAFT') OR
       (op.action='PUBLISH_NEW_AUTHORING' AND NEW.status='PUBLISHED' AND NEW.payload=OLD.payload)) THEN
      RAISE EXCEPTION 'Revision command mismatch';
    END IF;
  ELSIF TG_TABLE_NAME IN ('deliveries','recipients') THEN
    IF TG_OP<>'INSERT' OR op.action<>'ASSIGN_NEW_DELIVERY' OR NEW.class_id<>op.class_id THEN
      RAISE EXCEPTION 'Immutable delivery or admission';
    END IF;
    IF TG_TABLE_NAME='deliveries' AND NOT EXISTS (
      SELECT 1 FROM evaluation_runtime.revisions WHERE id=NEW.revision_id AND status='PUBLISHED') THEN
      RAISE EXCEPTION 'Published revision required';
    END IF;
  ELSE
    IF NEW.class_id<>op.class_id THEN RAISE EXCEPTION 'Attempt class mismatch'; END IF;
    IF TG_OP='INSERT' THEN
      PERFORM id FROM evaluation_runtime.resources WHERE id=root FOR UPDATE;
      IF op.action<>'START_NEW_EXECUTION' OR NEW.account_id<>op.actor_id OR NEW.status<>'IN_PROGRESS'
        OR NEW.attempt_number<>(SELECT count(*)+1 FROM evaluation_runtime.attempts WHERE resource_id=root AND account_id=NEW.account_id)
        OR NEW.attempt_number>(SELECT max_attempts FROM evaluation_runtime.resources WHERE id=root) THEN
        RAISE EXCEPTION 'F09 attempt budget';
      END IF;
    ELSE
      IF OLD.grade_closed OR (to_jsonb(OLD) - ARRAY['answers','adjustments','current_question_index','status','score','max_score',
        'final_score','overall_feedback','submitted_at','graded_at','grade_closed','grade_closed_at','grade_closed_by']) IS DISTINCT FROM
        (to_jsonb(NEW) - ARRAY['answers','adjustments','current_question_index','status','score','max_score',
        'final_score','overall_feedback','submitted_at','graded_at','grade_closed','grade_closed_at','grade_closed_by']) THEN
        RAISE EXCEPTION 'Immutable attempt identity or closed grade';
      END IF;
      expected := replace(op.action,'_NEW_EXECUTION','');
      IF (expected IN ('SAVE','SUBMIT') AND (OLD.account_id<>op.actor_id OR OLD.status<>'IN_PROGRESS'))
         OR (expected IN ('GRADE','COMPLETE','ADD','REMOVE','FEEDBACK','CLOSE') AND (OLD.account_id=op.actor_id OR OLD.status='IN_PROGRESS'))
         OR expected NOT IN ('SAVE','SUBMIT','GRADE','COMPLETE','ADD','REMOVE','FEEDBACK','CLOSE')
         OR (expected='SAVE' AND (NEW.status<>OLD.status OR clock_timestamp()>=OLD.deadline))
         OR (expected NOT IN ('SUBMIT','CLOSE') AND NEW.grade_closed) THEN
        RAISE EXCEPTION 'Attempt command mismatch';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION evaluation_runtime.guard_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retain command history'; END IF;
  IF TG_OP='UPDATE' AND (OLD.result IS NOT NULL OR
    (to_jsonb(OLD)-ARRAY['result','audit_id','committed_at','evaluation_id']) IS DISTINCT FROM
    (to_jsonb(NEW)-ARRAY['result','audit_id','committed_at','evaluation_id']) OR
    (OLD.evaluation_id IS NOT NULL AND OLD.evaluation_id IS DISTINCT FROM NEW.evaluation_id)) THEN
    RAISE EXCEPTION 'Immutable receipt';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER runtime_operation BEFORE UPDATE OR DELETE ON evaluation_runtime.operations
  FOR EACH ROW EXECUTE FUNCTION evaluation_runtime.guard_operation();
CREATE FUNCTION evaluation_runtime.require_audited_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM evaluation_runtime.operations o JOIN system_logs l ON l.id=o.audit_id
    JOIN evaluation_runtime.resources r ON r.id=o.evaluation_id AND r.institution_id=o.institution_id
    WHERE o.operation_id=NEW.operation_id AND o.result IS NOT NULL AND l.outcome='SUCCESS'
      AND l.action=o.action AND l.correlation_id=o.operation_id AND l.actor_user_id=o.actor_id
      AND l.target_id=o.evaluation_id AND l.target_type='ContextResolution'
      AND l.xmin=pg_current_xact_id()::text::xid) THEN
    RAISE EXCEPTION 'Missing same-transaction receipt and audit';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER runtime_audit AFTER INSERT ON evaluation_runtime.operations
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION evaluation_runtime.require_audited_receipt();

CREATE FUNCTION evaluation_runtime.charge() RETURNS trigger LANGUAGE plpgsql SET TimeZone='UTC' AS $$
DECLARE added_rows bigint := 0; before_bytes bigint := 0;
BEGIN
  IF TG_OP='INSERT' THEN added_rows := 1; ELSE before_bytes := octet_length(to_jsonb(OLD)::text); END IF;
  UPDATE evaluation_runtime.quotas SET used_rows=used_rows+added_rows,
    used_bytes=used_bytes+octet_length(to_jsonb(NEW)::text)-before_bytes
    WHERE institution_id=NEW.institution_id
      AND used_rows+added_rows<=max_rows
      AND used_bytes+octet_length(to_jsonb(NEW)::text)-before_bytes<=max_bytes;
  IF NOT FOUND THEN RAISE EXCEPTION 'Institutional quota exceeded' USING ERRCODE='P7807'; END IF;
  RETURN NULL;
END $$;
DO $academic$
DECLARE n text;
BEGIN
  FOREACH n IN ARRAY ARRAY['resources','revisions','deliveries','recipients','attempts','operations'] LOOP
    IF n<>'operations' THEN
      EXECUTE format('CREATE TRIGGER runtime_academic BEFORE INSERT OR UPDATE OR DELETE ON evaluation_runtime.%I FOR EACH ROW EXECUTE FUNCTION evaluation_runtime.guard_academic()',n);
    END IF;
    EXECUTE format('CREATE TRIGGER runtime_quota AFTER INSERT OR UPDATE ON evaluation_runtime.%I FOR EACH ROW EXECUTE FUNCTION evaluation_runtime.charge()',n);
  END LOOP;
END $academic$;

CREATE FUNCTION evaluation_runtime.seal_legacy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'This database uses the institutional evaluation contract' USING ERRCODE='P7809';
END $$;
CREATE FUNCTION evaluation_runtime.retain_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Retain institutional evaluation history'; END $$;
DO $cut$
DECLARE n text;
BEGIN
  FOREACH n IN ARRAY ARRAY['evaluations','evaluation_assignments','evaluation_attempts','evaluation_questions','evaluation_options','evaluation_answers'] LOOP
    EXECUTE format('CREATE TRIGGER evaluation_runtime_cut BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION evaluation_runtime.seal_legacy()',n);
  END LOOP;
  FOREACH n IN ARRAY ARRAY['resources','revisions','deliveries','recipients','attempts','operations',
    'institutions','periods','classes','memberships','roles','teaching','enrollments','faculties','quotas'] LOOP
    EXECUTE format('CREATE TRIGGER runtime_retain BEFORE TRUNCATE ON evaluation_runtime.%I FOR EACH STATEMENT EXECUTE FUNCTION evaluation_runtime.retain_history()',n);
  END LOOP;
END $cut$;
COMMIT;

-- Successor to evaluation-v1.sql. Apply once with all application writers stopped.
BEGIN;
SET LOCAL search_path TO public, pg_catalog;
LOCK TABLE concept_contents, concept_assignments, concept_materials, concept_content_formation_steps,
  concept_content_key_points, concept_content_examples IN ACCESS EXCLUSIVE MODE;
DO $install$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM evaluation_runtime.contract WHERE version=1) THEN
    RAISE EXCEPTION 'Evaluation runtime v1 is required';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname LIKE 't03\_%' ESCAPE '\')
     OR to_regclass('t11b1_schema_version') IS NOT NULL THEN
    RAISE EXCEPTION 'Install separately from certified fixtures';
  END IF;
  IF EXISTS (SELECT 1 FROM concept_contents) OR EXISTS (SELECT 1 FROM concept_assignments)
     OR EXISTS (SELECT 1 FROM concept_materials)
     OR EXISTS (SELECT 1 FROM concept_content_formation_steps)
     OR EXISTS (SELECT 1 FROM concept_content_key_points)
     OR EXISTS (SELECT 1 FROM concept_content_examples) THEN
    RAISE EXCEPTION 'Historical content requires approved F08 mapping and a verified writer transition';
  END IF;
END $install$;

CREATE SCHEMA content_runtime;
CREATE TABLE content_runtime.contract(
  version integer PRIMARY KEY CHECK(version=1),
  evaluation_version integer NOT NULL DEFAULT 1 CHECK(evaluation_version=1)
    REFERENCES evaluation_runtime.contract(version)
);
INSERT INTO content_runtime.contract(version) VALUES (1);

CREATE TABLE content_runtime.resources(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 2000000000) PRIMARY KEY,
  institution_id bigint NOT NULL, origin_class_id bigint NOT NULL,
  author_account_id bigint NOT NULL, author_membership_id bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(id,institution_id),
  FOREIGN KEY(origin_class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(author_membership_id,institution_id,author_account_id)
    REFERENCES evaluation_runtime.memberships(id,institution_id,account_id)
);
CREATE INDEX content_resources_scope ON content_runtime.resources(institution_id,id);
CREATE TABLE content_runtime.files(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 2000000000) PRIMARY KEY,
  resource_id bigint NOT NULL, institution_id bigint NOT NULL,
  name varchar(255) NOT NULL, title varchar(150), mime text NOT NULL CHECK(mime IN
    ('image/png','image/jpeg','application/pdf','application/vnd.ms-powerpoint',
      'application/vnd.openxmlformats-officedocument.presentationml.presentation')),
  data bytea NOT NULL CHECK(octet_length(data) BETWEEN 1 AND 10485760),
  size integer GENERATED ALWAYS AS (octet_length(data)) STORED,
  UNIQUE(id,resource_id,institution_id),
  FOREIGN KEY(resource_id,institution_id) REFERENCES content_runtime.resources(id,institution_id)
);
CREATE TABLE content_runtime.revisions(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 2000000000) PRIMARY KEY,
  resource_id bigint NOT NULL, institution_id bigint NOT NULL, parent_id bigint,
  revision_number integer NOT NULL CHECK(revision_number>0),
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
  payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=196608),
  title text GENERATED ALWAYS AS (payload->>'title') STORED NOT NULL,
  category text GENERATED ALWAYS AS (payload->>'category') STORED NOT NULL,
  file_id bigint, published_at timestamptz,
  UNIQUE(id,resource_id,institution_id), UNIQUE(id,resource_id,institution_id,status),
  UNIQUE(resource_id,revision_number),
  FOREIGN KEY(resource_id,institution_id) REFERENCES content_runtime.resources(id,institution_id),
  FOREIGN KEY(parent_id,resource_id,institution_id) REFERENCES content_runtime.revisions(id,resource_id,institution_id),
  FOREIGN KEY(file_id,resource_id,institution_id) REFERENCES content_runtime.files(id,resource_id,institution_id) DEFERRABLE INITIALLY DEFERRED,
  CHECK((status='PUBLISHED')=(published_at IS NOT NULL))
);
CREATE UNIQUE INDEX content_one_draft ON content_runtime.revisions(resource_id) WHERE status='DRAFT';
CREATE INDEX content_revisions_scope ON content_runtime.revisions(institution_id,id);
CREATE INDEX content_revisions_file ON content_runtime.revisions(file_id) WHERE file_id IS NOT NULL;
CREATE TABLE content_runtime.faculties(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  resource_id bigint NOT NULL, institution_id bigint NOT NULL, membership_id bigint NOT NULL,
  faculty varchar(16) NOT NULL CHECK(faculty IN ('EDIT_DRAFT','PUBLISH','ASSIGN')),
  granted_by_membership_id bigint NOT NULL, valid_from timestamptz NOT NULL,
  valid_until timestamptz NOT NULL CHECK(valid_until>valid_from), revoked_at timestamptz,
  FOREIGN KEY(resource_id,institution_id) REFERENCES content_runtime.resources(id,institution_id),
  FOREIGN KEY(membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id),
  FOREIGN KEY(granted_by_membership_id,institution_id) REFERENCES evaluation_runtime.memberships(id,institution_id)
);
CREATE UNIQUE INDEX content_open_faculty ON content_runtime.faculties(membership_id,resource_id,faculty) WHERE revoked_at IS NULL;
CREATE TRIGGER content_scope BEFORE INSERT OR UPDATE OR DELETE ON content_runtime.faculties
  FOR EACH ROW EXECUTE FUNCTION evaluation_runtime.guard_scope();

CREATE TABLE content_runtime.deliveries(
  id bigint GENERATED ALWAYS AS IDENTITY (START WITH 2000000000) PRIMARY KEY,
  resource_id bigint NOT NULL, revision_id bigint NOT NULL, institution_id bigint NOT NULL, class_id bigint NOT NULL,
  status varchar(12) GENERATED ALWAYS AS ('PUBLISHED') STORED,
  assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(revision_id,resource_id,institution_id,status)
    REFERENCES content_runtime.revisions(id,resource_id,institution_id,status),
  UNIQUE(revision_id,class_id), UNIQUE(id,resource_id,institution_id,class_id)
);
CREATE TABLE content_runtime.recipients(
  delivery_id bigint NOT NULL, resource_id bigint NOT NULL, institution_id bigint NOT NULL, class_id bigint NOT NULL,
  enrollment_id bigint NOT NULL, account_id bigint NOT NULL,
  FOREIGN KEY(delivery_id,resource_id,institution_id,class_id)
    REFERENCES content_runtime.deliveries(id,resource_id,institution_id,class_id),
  FOREIGN KEY(enrollment_id,institution_id,class_id,account_id)
    REFERENCES evaluation_runtime.enrollments(id,institution_id,class_id,account_id),
  PRIMARY KEY(delivery_id,account_id)
);
CREATE INDEX content_student_catalog ON content_runtime.recipients(account_id,class_id,delivery_id);

CREATE TABLE content_runtime.operations(
  operation_id uuid PRIMARY KEY, institution_id bigint NOT NULL, class_id bigint NOT NULL,
  actor_id bigint NOT NULL, membership_id bigint NOT NULL, resource_id bigint,
  action text NOT NULL CHECK(action IN ('CONTENT_CREATE','CONTENT_EDIT','CONTENT_PUBLISH',
    'CONTENT_SUCCESSOR','CONTENT_ASSIGN','CONTENT_FILE','CONTENT_REMOVE_FILE')),
  request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
  result jsonb, audit_id bigint UNIQUE REFERENCES system_logs(id) ON DELETE SET NULL, committed_at timestamptz,
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(membership_id,institution_id,actor_id) REFERENCES evaluation_runtime.memberships(id,institution_id,account_id),
  FOREIGN KEY(resource_id,institution_id) REFERENCES content_runtime.resources(id,institution_id),
  CHECK((result IS NULL)=(committed_at IS NULL))
);

CREATE FUNCTION content_runtime.guard_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retain content receipts'; END IF;
  IF pg_trigger_depth()>1 AND OLD.audit_id IS NOT NULL AND NEW.audit_id IS NULL
     AND (to_jsonb(OLD)-'audit_id')=(to_jsonb(NEW)-'audit_id') THEN RETURN NEW; END IF;
  IF OLD.action='CONTENT_CREATE' AND OLD.resource_id IS NULL AND NEW.resource_id IS NOT NULL AND OLD.result IS NULL
     AND (to_jsonb(OLD)-'resource_id')=(to_jsonb(NEW)-'resource_id') AND EXISTS (
       SELECT 1 FROM content_runtime.resources r WHERE r.id=NEW.resource_id AND r.institution_id=NEW.institution_id
         AND r.origin_class_id=NEW.class_id AND r.author_membership_id=NEW.membership_id
     ) THEN RETURN NEW; END IF;
  IF OLD.result IS NOT NULL OR NEW.result IS NULL OR NEW.audit_id IS NULL
    OR (to_jsonb(OLD)-ARRAY['result','audit_id','committed_at','resource_id']) IS DISTINCT FROM
       (to_jsonb(NEW)-ARRAY['result','audit_id','committed_at','resource_id'])
    OR (OLD.resource_id IS NOT NULL AND OLD.resource_id IS DISTINCT FROM NEW.resource_id) THEN
    RAISE EXCEPTION 'Immutable content receipt';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_operation BEFORE UPDATE OR DELETE ON content_runtime.operations
  FOR EACH ROW EXECUTE FUNCTION content_runtime.guard_operation();

CREATE FUNCTION content_runtime.guard_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE command record; root_id bigint; scope_id bigint;
BEGIN
  IF TG_OP='DELETE' THEN
    root_id := OLD.resource_id; scope_id := OLD.institution_id;
  ELSE
    IF TG_TABLE_NAME='resources' THEN root_id := NEW.id; ELSE root_id := NEW.resource_id; END IF;
    scope_id := NEW.institution_id;
  END IF;
  SELECT * INTO command FROM content_runtime.operations
    WHERE operation_id=nullif(current_setting('content_runtime.operation',true),'')::uuid
      AND institution_id=scope_id AND result IS NULL AND xmin=pg_current_xact_id()::text::xid;
  IF NOT FOUND OR (command.resource_id IS DISTINCT FROM root_id AND NOT (TG_TABLE_NAME='resources' AND command.action='CONTENT_CREATE' AND command.resource_id IS NULL)) THEN
    RAISE EXCEPTION 'Content change requires its pending command';
  END IF;
  IF TG_TABLE_NAME='resources' THEN
    IF TG_OP<>'INSERT' OR command.action<>'CONTENT_CREATE' OR command.actor_id<>NEW.author_account_id
      OR command.membership_id<>NEW.author_membership_id OR command.class_id<>NEW.origin_class_id THEN
      RAISE EXCEPTION 'Immutable content provenance';
    END IF;
  ELSIF TG_TABLE_NAME='revisions' THEN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retain revision history'; END IF;
    IF TG_OP='INSERT' THEN
      IF NEW.status<>'DRAFT' OR NEW.version<>1 OR
        (NEW.parent_id IS NULL AND (NEW.revision_number<>1 OR command.action<>'CONTENT_CREATE')) OR
        (NEW.parent_id IS NOT NULL AND (command.action<>'CONTENT_SUCCESSOR' OR NOT EXISTS (
          SELECT 1 FROM content_runtime.revisions p WHERE p.id=NEW.parent_id AND p.resource_id=root_id
            AND p.status='PUBLISHED' AND p.revision_number+1=NEW.revision_number))) THEN
        RAISE EXCEPTION 'Invalid content revision lineage';
      END IF;
    ELSIF OLD.status<>'DRAFT' OR NEW.version<>OLD.version+1
      OR (to_jsonb(OLD)-ARRAY['status','version','payload','file_id','published_at','title','category']) IS DISTINCT FROM
         (to_jsonb(NEW)-ARRAY['status','version','payload','file_id','published_at','title','category'])
      OR (NEW.status='PUBLISHED') IS DISTINCT FROM (command.action='CONTENT_PUBLISH')
      OR (NEW.payload IS DISTINCT FROM OLD.payload AND command.action<>'CONTENT_EDIT')
      OR (NEW.file_id IS DISTINCT FROM OLD.file_id AND command.action NOT IN ('CONTENT_FILE','CONTENT_REMOVE_FILE'))
      OR command.action NOT IN ('CONTENT_EDIT','CONTENT_FILE','CONTENT_REMOVE_FILE','CONTENT_PUBLISH') THEN
      RAISE EXCEPTION 'Immutable publication or invalid version';
    END IF;
  ELSIF TG_TABLE_NAME='files' THEN
    IF TG_OP='UPDATE' OR (TG_OP='INSERT' AND command.action<>'CONTENT_FILE')
      OR (TG_OP='DELETE' AND command.action NOT IN ('CONTENT_FILE','CONTENT_REMOVE_FILE')) THEN
      RAISE EXCEPTION 'Immutable file bytes';
    END IF;
  ELSIF TG_OP<>'INSERT' OR command.class_id<>NEW.class_id OR command.action<>'CONTENT_ASSIGN' THEN
    RAISE EXCEPTION 'Immutable delivery and recipient history';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION content_runtime.require_audited_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM content_runtime.operations o JOIN system_logs l ON l.id=o.audit_id
    WHERE o.operation_id=NEW.operation_id AND o.resource_id IS NOT NULL AND o.result IS NOT NULL
      AND l.outcome='SUCCESS' AND l.category='CONCEPT_CONTENT' AND l.action=o.action
      AND l.correlation_id=o.operation_id AND l.actor_user_id=o.actor_id
      AND l.target_type='ConceptContent' AND l.target_id=o.resource_id
      AND l.xmin=pg_current_xact_id()::text::xid) THEN
    RAISE EXCEPTION 'Missing same-transaction content receipt and audit';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER content_audit AFTER INSERT ON content_runtime.operations
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION content_runtime.require_audited_receipt();

-- Share the existing institution budget. Binary rows count actual stored bytes once.
CREATE FUNCTION content_runtime.charge() RETURNS trigger LANGUAGE plpgsql SET TimeZone='UTC' AS $$
DECLARE delta_rows bigint := 0; before_bytes bigint := 0; after_bytes bigint := 0; scope_id bigint;
BEGIN
  IF TG_OP<>'INSERT' THEN
    scope_id := OLD.institution_id;
    IF TG_TABLE_NAME='files' THEN before_bytes := OLD.size + octet_length((to_jsonb(
      ROW(OLD.id,OLD.resource_id,OLD.institution_id,OLD.name,OLD.title,OLD.mime,OLD.size)))::text);
    ELSE before_bytes := octet_length(to_jsonb(OLD)::text); END IF;
  END IF;
  IF TG_OP<>'DELETE' THEN
    scope_id := NEW.institution_id;
    IF TG_TABLE_NAME='files' THEN after_bytes := NEW.size + octet_length((to_jsonb(
      ROW(NEW.id,NEW.resource_id,NEW.institution_id,NEW.name,NEW.title,NEW.mime,NEW.size)))::text);
    ELSE after_bytes := octet_length(to_jsonb(NEW)::text); END IF;
  END IF;
  IF TG_OP='INSERT' THEN delta_rows := 1; ELSIF TG_OP='DELETE' THEN delta_rows := -1; END IF;
  UPDATE evaluation_runtime.quotas SET used_rows=used_rows+delta_rows, used_bytes=used_bytes+after_bytes-before_bytes
    WHERE institution_id=scope_id AND used_rows+delta_rows<=max_rows AND used_bytes+after_bytes-before_bytes<=max_bytes;
  IF NOT FOUND THEN RAISE EXCEPTION 'Institutional quota exceeded' USING ERRCODE='P7807'; END IF;
  RETURN NULL;
END $$;

CREATE FUNCTION content_runtime.reject_legacy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'This database uses the institutional content contract' USING ERRCODE='P7809'; END $$;
DO $guards$
DECLARE name text;
BEGIN
  FOREACH name IN ARRAY ARRAY['resources','files','revisions','deliveries','recipients','operations','faculties','contract'] LOOP
    EXECUTE format('CREATE TRIGGER content_retain BEFORE TRUNCATE ON content_runtime.%I FOR EACH STATEMENT
      EXECUTE FUNCTION evaluation_runtime.retain_history()',name);
    IF name NOT IN ('operations','faculties','contract') THEN
      EXECUTE format('CREATE TRIGGER content_change BEFORE INSERT OR UPDATE OR DELETE ON content_runtime.%I FOR EACH ROW
        EXECUTE FUNCTION content_runtime.guard_change()',name);
    END IF;
    IF name NOT IN ('faculties','contract') THEN
      EXECUTE format('CREATE TRIGGER content_quota AFTER INSERT OR UPDATE OR DELETE ON content_runtime.%I FOR EACH ROW
        EXECUTE FUNCTION content_runtime.charge()',name);
    END IF;
  END LOOP;
  FOREACH name IN ARRAY ARRAY['concept_contents','concept_assignments','concept_materials','concept_content_formation_steps',
    'concept_content_key_points','concept_content_examples'] LOOP
    EXECUTE format('CREATE TRIGGER content_runtime_cut BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT
      EXECUTE FUNCTION content_runtime.reject_legacy()',name);
  END LOOP;
END $guards$;
COMMIT;

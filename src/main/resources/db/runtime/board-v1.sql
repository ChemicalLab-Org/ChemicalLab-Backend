-- Apply with writers stopped. The existing board engine tables must have no ambiguous source rows.
BEGIN;
SET LOCAL search_path TO public, pg_catalog;
LOCK TABLE whiteboard_sessions, whiteboard_participants, whiteboard_object_ownership IN ACCESS EXCLUSIVE MODE;
DO $install$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM evaluation_runtime.contract WHERE version=1)
      OR NOT EXISTS (SELECT 1 FROM content_runtime.contract WHERE version=1) THEN
    RAISE EXCEPTION 'Evaluation and content runtime v1 are required';
  END IF;
  IF to_regclass('t11b1_schema_version') IS NOT NULL
      OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspname LIKE 't03\_%' ESCAPE '\') THEN
    RAISE EXCEPTION 'Install separately from certified fixtures';
  END IF;
  IF EXISTS (SELECT 1 FROM whiteboard_sessions) OR EXISTS (SELECT 1 FROM whiteboard_participants)
      OR EXISTS (SELECT 1 FROM whiteboard_object_ownership) THEN
    RAISE EXCEPTION 'Historical boards require approved F08 mapping and a verified writer transition';
  END IF;
END $install$;

CREATE SCHEMA board_runtime;
CREATE TABLE board_runtime.contract(version integer PRIMARY KEY CHECK(version=1));
INSERT INTO board_runtime.contract VALUES (1);
CREATE TABLE board_runtime.boards(
  board_id bigint PRIMARY KEY REFERENCES public.whiteboard_sessions,
  institution_id bigint NOT NULL, class_id bigint NOT NULL,
  author_id bigint NOT NULL, membership_id bigint NOT NULL,
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(membership_id,institution_id,author_id) REFERENCES evaluation_runtime.memberships(id,institution_id,account_id),
  UNIQUE(board_id,institution_id,class_id)
);
CREATE INDEX board_scope ON board_runtime.boards(institution_id,class_id,board_id DESC);

CREATE TABLE board_runtime.admissions(
  participant_id bigint PRIMARY KEY REFERENCES public.whiteboard_participants,
  board_id bigint NOT NULL, institution_id bigint NOT NULL, class_id bigint NOT NULL,
  enrollment_id bigint NOT NULL, account_id bigint NOT NULL,
  FOREIGN KEY(board_id,institution_id,class_id) REFERENCES board_runtime.boards(board_id,institution_id,class_id),
  FOREIGN KEY(enrollment_id,institution_id,class_id,account_id) REFERENCES evaluation_runtime.enrollments(id,institution_id,class_id,account_id),
  UNIQUE(board_id,account_id)
);
CREATE VIEW board_runtime.current_admissions AS
SELECT a.*
FROM board_runtime.admissions a
JOIN evaluation_runtime.enrollments e ON e.id=a.enrollment_id
JOIN evaluation_runtime.memberships m ON m.id=e.membership_id
JOIN public.user_accounts u ON u.id=a.account_id
WHERE e.ended_at IS NULL AND e.valid_from<=statement_timestamp() AND statement_timestamp()<e.valid_until
  AND u.active AND m.active AND m.valid_from<=statement_timestamp()
  AND (m.valid_until IS NULL OR statement_timestamp()<m.valid_until)
  AND EXISTS (SELECT 1 FROM evaluation_runtime.roles g WHERE g.membership_id=m.id AND g.role='ESTUDIANTE'
    AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp()
    AND (g.valid_until IS NULL OR statement_timestamp()<g.valid_until));

CREATE TABLE board_runtime.operations(
  operation_id uuid PRIMARY KEY, institution_id bigint NOT NULL, class_id bigint NOT NULL, actor_id bigint NOT NULL,
  membership_id bigint NOT NULL, board_id bigint, action text NOT NULL CHECK(action IN
    ('CREATE','JOIN','DRAW','PAUSE','RESUME','CLOSE','INTERACTION','PARTICIPANT')),
  request_hash text NOT NULL CHECK(length(request_hash)=64), result jsonb,
  audit_id bigint UNIQUE REFERENCES public.system_logs ON DELETE SET NULL,
  committed_at timestamptz,
  FOREIGN KEY(board_id,institution_id,class_id) REFERENCES board_runtime.boards(board_id,institution_id,class_id),
  FOREIGN KEY(class_id,institution_id) REFERENCES evaluation_runtime.classes(id,institution_id),
  FOREIGN KEY(membership_id,institution_id,actor_id) REFERENCES evaluation_runtime.memberships(id,institution_id,account_id)
);

CREATE FUNCTION board_runtime.guard_operation() RETURNS trigger LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retain board operations'; END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.result IS NOT NULL OR NEW.audit_id IS NOT NULL OR NEW.committed_at IS NOT NULL
       OR (NEW.board_id IS NULL) IS DISTINCT FROM (NEW.action='CREATE') THEN
      RAISE EXCEPTION 'Expected a pending board command';
    END IF;
    RETURN NEW;
  END IF;
  IF pg_trigger_depth()>1 AND OLD.audit_id IS NOT NULL AND NEW.audit_id IS NULL
     AND (to_jsonb(OLD)-'audit_id')=(to_jsonb(NEW)-'audit_id') THEN RETURN NEW; END IF;
  IF OLD.result IS NOT NULL OR NEW.result IS NULL OR NEW.audit_id IS NULL OR NEW.committed_at IS NULL
     OR NEW.board_id IS NULL OR (OLD.board_id IS NOT NULL AND NEW.board_id<>OLD.board_id)
     OR (to_jsonb(OLD)-ARRAY['result','audit_id','committed_at','board_id'])
       IS DISTINCT FROM (to_jsonb(NEW)-ARRAY['result','audit_id','committed_at','board_id']) THEN
    RAISE EXCEPTION 'Invalid board receipt transition';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER board_operation BEFORE INSERT OR UPDATE OR DELETE ON board_runtime.operations
  FOR EACH ROW EXECUTE FUNCTION board_runtime.guard_operation();

CREATE FUNCTION board_runtime.require_audit() RETURNS trigger LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM board_runtime.operations o JOIN system_logs l ON l.id=o.audit_id
    WHERE o.operation_id=NEW.operation_id AND o.result IS NOT NULL AND o.committed_at IS NOT NULL
      AND o.board_id IS NOT NULL AND l.outcome='SUCCESS' AND l.category='WHITEBOARD' AND l.action=o.action
      AND l.correlation_id=o.operation_id AND l.actor_user_id=o.actor_id
      AND l.target_type='WhiteboardSession' AND l.target_id=o.board_id
      AND l.xmin=pg_current_xact_id()::text::xid) THEN
    RAISE EXCEPTION 'Missing same-transaction board receipt and audit';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER board_audit AFTER INSERT ON board_runtime.operations
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION board_runtime.require_audit();

CREATE FUNCTION board_runtime.require_context() RETURNS trigger LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
BEGIN
  IF TG_TABLE_NAME='whiteboard_sessions' THEN
    IF NOT EXISTS (SELECT 1 FROM board_runtime.boards WHERE board_id=NEW.id) THEN
      RAISE EXCEPTION 'Board context required';
    END IF;
  ELSE
    IF NOT EXISTS (SELECT 1 FROM board_runtime.admissions WHERE participant_id=NEW.id) THEN
      RAISE EXCEPTION 'Board admission required';
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER board_context AFTER INSERT ON public.whiteboard_sessions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION board_runtime.require_context();
CREATE CONSTRAINT TRIGGER board_admission AFTER INSERT ON public.whiteboard_participants
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION board_runtime.require_context();

CREATE FUNCTION board_runtime.guard_change() RETURNS trigger LANGUAGE plpgsql SET search_path=public,pg_catalog AS $$
DECLARE op board_runtime.operations; parent_id bigint;
BEGIN
  IF TG_OP='DELETE' OR (TG_OP='UPDATE' AND TG_TABLE_NAME IN ('boards','admissions')) THEN
    RAISE EXCEPTION 'Retain board history';
  END IF;
  SELECT * INTO op FROM board_runtime.operations WHERE operation_id=
    nullif(current_setting('board_runtime.operation',true),'')::uuid
      AND result IS NULL AND xmin=pg_current_xact_id()::text::xid;
  IF NOT FOUND THEN RAISE EXCEPTION 'Board write requires its pending command'; END IF;
  IF TG_TABLE_NAME='whiteboard_sessions' AND TG_OP='INSERT' THEN
    IF op.action<>'CREATE' OR op.board_id IS NOT NULL THEN RAISE EXCEPTION 'Invalid board creation'; END IF;
    RETURN NEW;
  END IF;
  IF TG_TABLE_NAME='boards' THEN
    IF op.action<>'CREATE' OR NEW.institution_id<>op.institution_id OR NEW.class_id<>op.class_id
       OR NEW.author_id<>op.actor_id OR NEW.membership_id<>op.membership_id OR NOT EXISTS (
         SELECT 1 FROM whiteboard_sessions b JOIN teacher_profiles t ON t.id=b.teacher_id
         WHERE b.id=NEW.board_id AND t.user_id=NEW.author_id AND b.xmin=pg_current_xact_id()::text::xid
       ) THEN RAISE EXCEPTION 'Invalid board context'; END IF;
    RETURN NEW;
  END IF;
  CASE TG_TABLE_NAME
    WHEN 'whiteboard_sessions' THEN parent_id := NEW.id;
    WHEN 'whiteboard_participants' THEN parent_id := NEW.session_id;
    ELSE parent_id := NEW.board_id;
  END CASE;
  IF parent_id IS DISTINCT FROM op.board_id THEN RAISE EXCEPTION 'Board command scope mismatch'; END IF;
  IF TG_TABLE_NAME='admissions' THEN
    IF op.action<>'JOIN' OR NEW.account_id<>op.actor_id OR NOT EXISTS (
      SELECT 1 FROM whiteboard_participants p JOIN student_profiles s ON s.id=p.student_id
      WHERE p.id=NEW.participant_id AND p.session_id=parent_id AND s.user_id=NEW.account_id
    ) THEN RAISE EXCEPTION 'Invalid board admission'; END IF;
  END IF;
  IF TG_TABLE_NAME='whiteboard_sessions' THEN
    IF OLD.status='CLOSED' OR (OLD.id,OLD.teacher_id,OLD.grade,OLD.section)
       IS DISTINCT FROM (NEW.id,NEW.teacher_id,NEW.grade,NEW.section) THEN
      RAISE EXCEPTION 'Immutable board identity or closed state';
    END IF;
  END IF;
  IF TG_TABLE_NAME='whiteboard_object_ownership' THEN
    IF op.action<>'DRAW' OR NEW.owner_user_id IS NULL OR (TG_OP='INSERT' AND NEW.owner_user_id<>op.actor_id)
      OR (TG_OP='UPDATE' AND (OLD.board_id,OLD.object_kind,OLD.object_id,OLD.owner_user_id)
       IS DISTINCT FROM (NEW.board_id,NEW.object_kind,NEW.object_id,NEW.owner_user_id)) THEN
      RAISE EXCEPTION 'Immutable board ownership';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION board_runtime.session_bytes(b public.whiteboard_sessions) RETURNS bigint
  LANGUAGE sql STABLE SET TimeZone='UTC' AS $$
    SELECT coalesce(octet_length(b.final_snapshot_data),0) + octet_length(jsonb_build_array(
      b.id,b.name,b.description,b.teacher_id,b.grade,b.section,b.status,b.interaction_enabled,
      b.current_state_json,b.state_revision,b.state_updated_at,b.final_snapshot_content_type,
      b.final_snapshot_file_name,b.final_snapshot_size,b.created_at,b.started_at,b.paused_at,
      b.resumed_at,b.closed_at,b.closed_by)::text)
  $$;
CREATE FUNCTION board_runtime.charge() RETURNS trigger LANGUAGE plpgsql SET search_path=public,pg_catalog SET TimeZone='UTC' AS $$
DECLARE scope bigint; before_bytes bigint := 0; after_bytes bigint; delta_rows bigint := 0;
BEGIN
  IF TG_TABLE_NAME='operations' THEN scope := NEW.institution_id;
  ELSE
    SELECT institution_id INTO STRICT scope FROM board_runtime.operations
      WHERE operation_id=nullif(current_setting('board_runtime.operation',true),'')::uuid;
  END IF;
  IF TG_TABLE_NAME='whiteboard_sessions' THEN
    after_bytes := board_runtime.session_bytes(NEW);
    IF TG_OP='UPDATE' THEN before_bytes := board_runtime.session_bytes(OLD); END IF;
  ELSIF TG_TABLE_NAME='operations' THEN
    after_bytes := octet_length((to_jsonb(NEW)-'audit_id')::text);
    IF TG_OP='UPDATE' THEN before_bytes := octet_length((to_jsonb(OLD)-'audit_id')::text); END IF;
  ELSE
    after_bytes := octet_length(to_jsonb(NEW)::text);
    IF TG_OP='UPDATE' THEN before_bytes := octet_length(to_jsonb(OLD)::text); END IF;
  END IF;
  IF TG_OP='INSERT' THEN delta_rows := 1; END IF;
  UPDATE evaluation_runtime.quotas SET used_rows=used_rows+delta_rows, used_bytes=used_bytes+after_bytes-before_bytes
    WHERE institution_id=scope AND used_rows+delta_rows<=max_rows AND used_bytes+after_bytes-before_bytes<=max_bytes;
  IF NOT FOUND THEN RAISE EXCEPTION 'Institutional board quota exceeded' USING ERRCODE='P7501'; END IF;
  RETURN NULL;
END $$;

-- Also reject ordinary writes from a predecessor binary. The HTTP and STOMP cutover
-- covers readers in the successor and must be installed with the matching binary.
DO $guards$
DECLARE relation text;
BEGIN
  FOREACH relation IN ARRAY ARRAY['public.whiteboard_sessions','public.whiteboard_participants',
    'public.whiteboard_object_ownership','board_runtime.boards','board_runtime.admissions',
    'board_runtime.operations','board_runtime.contract'] LOOP
    EXECUTE format('CREATE TRIGGER board_retain BEFORE TRUNCATE ON %s FOR EACH STATEMENT
      EXECUTE FUNCTION evaluation_runtime.retain_history()',relation);
    IF relation NOT IN ('board_runtime.operations','board_runtime.contract') THEN
      EXECUTE format('CREATE TRIGGER board_change BEFORE INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW
        EXECUTE FUNCTION board_runtime.guard_change()',relation);
      EXECUTE format('CREATE TRIGGER board_charge AFTER INSERT OR UPDATE ON %s FOR EACH ROW
        EXECUTE FUNCTION board_runtime.charge()',relation);
    END IF;
  END LOOP;
END $guards$;
-- Charge the receipt on insertion and sealing. Its retention-managed audit pointer is excluded.
CREATE TRIGGER board_receipt_charge AFTER INSERT OR UPDATE ON board_runtime.operations
  FOR EACH ROW WHEN (pg_trigger_depth()=0) EXECUTE FUNCTION board_runtime.charge();
COMMIT;

-- T11-B2 v1. Explicit atomic expansion on the unchanged T11-B1 v1.
-- No startup installer. Verify schema, source and backup before an authorized application.
DO $migration$
DECLARE installed boolean; expected text; actual text; b1_expected text; audit_check record; event_column smallint;
BEGIN
    PERFORM pg_advisory_xact_lock(110011,1);
    IF current_schema() IS NULL OR to_regclass(current_schema()||'.t11b1_schema_version') IS NULL THEN
        RAISE EXCEPTION 'T11-B2 requires T11-B1 in the selected schema';
    END IF;
    SELECT fingerprint INTO b1_expected FROM t11b1_schema_version WHERE version=1 AND revision='t11b1-v1';
    IF b1_expected IS NULL OR (SELECT count(*) FROM t11b1_schema_version)<>1 THEN
        RAISE EXCEPTION 'T11-B2 unknown T11-B1 version';
    END IF;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
        SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') AS definition
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND r.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b1_%'
        UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle
        FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('institutions_id_seq','institution_memberships_id_seq','membership_events_id_seq','membership_role_grants_id_seq')
    ) definitions;
    IF actual IS DISTINCT FROM b1_expected THEN RAISE EXCEPTION 'T11-B2 prerequisite T11-B1 divergence'; END IF;
    installed:=to_regclass(current_schema()||'.t11b2_schema_version') IS NOT NULL;
    IF installed THEN
        SELECT fingerprint INTO expected FROM t11b2_schema_version WHERE version=1 AND revision='t11b2-v1';
        IF expected IS NULL OR (SELECT count(*) FROM t11b2_schema_version)<>1 THEN RAISE EXCEPTION 'T11-B2 unknown version'; END IF;
    ELSE
        IF EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace
            AND relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments',
                'academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events'))
            OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b2_%') THEN
            RAISE EXCEPTION 'T11-B2 unversioned objects; reconcile before expansion';
        END IF;
        CREATE TABLE t11b2_schema_version(version integer PRIMARY KEY CHECK(version=1),
            revision text NOT NULL, fingerprint text NOT NULL, installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
        -- Explicit evolution of Hibernate's legacy enum CHECKs: retain their existing
        -- predicate for old events, add only the eight B2 events. Never depend on update.
        -- No log data, T08 guard, budget or version is changed. A staged schema may
        -- omit system_logs; any later addition of event CHECKs is detectable divergence.
        IF to_regclass(current_schema()||'.system_logs') IS NOT NULL THEN
            LOCK TABLE system_logs IN ACCESS EXCLUSIVE MODE;
            SELECT attnum INTO event_column FROM pg_attribute
                WHERE attrelid=to_regclass(current_schema()||'.system_logs') AND attname='event_type' AND NOT attisdropped;
            IF event_column IS NULL THEN RAISE EXCEPTION 'T11-B2 missing legacy audit event_type'; END IF;
            FOR audit_check IN SELECT conname,conkey,convalidated,pg_get_expr(conbin,conrelid) AS expression
                FROM pg_constraint WHERE conrelid=to_regclass(current_schema()||'.system_logs')
                AND contype='c' AND event_column=ANY(conkey) ORDER BY conname LOOP
                IF audit_check.conkey<>ARRAY[event_column] OR NOT audit_check.convalidated THEN
                    RAISE EXCEPTION 'T11-B2 unsupported audit event constraint; explicit reconciliation required';
                END IF;
                EXECUTE format('ALTER TABLE system_logs DROP CONSTRAINT %I',audit_check.conname);
                EXECUTE format($check$ALTER TABLE system_logs ADD CONSTRAINT %I CHECK ((%s) OR event_type::text IN
                    ('ACADEMIC_PERIOD_CREATED','ACADEMIC_PERIOD_STATE_CHANGED','ACADEMIC_CLASS_CREATED','ACADEMIC_CLASS_STATE_CHANGED',
                     'TEACHING_ASSIGNMENT_CREATED','TEACHING_ASSIGNMENT_STATE_CHANGED','ENROLLMENT_CREATED','ENROLLMENT_STATE_CHANGED'))$check$,
                    audit_check.conname,replace(audit_check.expression,'::character varying','::text'));
            END LOOP;
        END IF;
        CREATE TABLE academic_periods(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            institution_id bigint NOT NULL REFERENCES institutions(id),
            local_code varchar(64) NOT NULL CHECK(local_code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
            name varchar(200) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 200),
            valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL,
            status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status::text IN ('PLANNED','OPEN','CLOSED','ARCHIVED')),
            version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
            state_since timestamptz NOT NULL CHECK(isfinite(state_since)),
            changed_by bigint REFERENCES user_accounts(id),
            change_reason varchar(32) NOT NULL CHECK(length(btrim(change_reason)) BETWEEN 1 AND 32),
            created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(institution_id,local_code), UNIQUE(id,institution_id),
            CHECK(isfinite(valid_from) AND isfinite(valid_until) AND valid_from<valid_until));
        CREATE TABLE academic_classes(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            institution_id bigint NOT NULL REFERENCES institutions(id), period_id bigint NOT NULL,
            local_code varchar(64) NOT NULL CHECK(local_code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
            name varchar(200) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 200),
            course varchar(200) NOT NULL CHECK(length(btrim(course)) BETWEEN 1 AND 200),
            grade varchar(1) NOT NULL CHECK(grade ~ '^[1-5]$'),
            section varchar(1) NOT NULL CHECK(section ~ '^[A-Z]$'),
            status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status::text IN ('PLANNED','ACTIVE','CLOSED','ARCHIVED')),
            version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
            state_since timestamptz NOT NULL CHECK(isfinite(state_since)),
            changed_by bigint REFERENCES user_accounts(id),
            change_reason varchar(32) NOT NULL CHECK(length(btrim(change_reason)) BETWEEN 1 AND 32),
            created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(institution_id,period_id,local_code), UNIQUE(id,institution_id),
            FOREIGN KEY(period_id,institution_id) REFERENCES academic_periods(id,institution_id));
        CREATE TABLE teaching_assignments(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            institution_id bigint NOT NULL REFERENCES institutions(id),
            class_id bigint NOT NULL, membership_id bigint NOT NULL,
            status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status::text IN ('PLANNED','ACTIVE','ENDED','CANCELLED')),
            valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL,
            activated_at timestamptz, ended_at timestamptz,
            function varchar(16) NOT NULL DEFAULT 'RESPONSABLE' CHECK(function::text='RESPONSABLE'),
            version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
            state_since timestamptz NOT NULL CHECK(isfinite(state_since)),
            changed_by bigint REFERENCES user_accounts(id),
            change_reason varchar(32) NOT NULL CHECK(length(btrim(change_reason)) BETWEEN 1 AND 32),
            created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(id,institution_id),
            FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id),
            FOREIGN KEY(membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
            CHECK(isfinite(valid_from) AND isfinite(valid_until) AND valid_from<valid_until),
            CHECK(activated_at IS NULL OR (isfinite(activated_at) AND activated_at>=valid_from AND activated_at<valid_until)),
            CHECK(ended_at IS NULL OR (isfinite(ended_at) AND activated_at IS NOT NULL AND ended_at>activated_at)),
            CHECK((status::text IN ('PLANNED','CANCELLED') AND activated_at IS NULL AND ended_at IS NULL)
                OR (status::text IN ('ACTIVE') AND activated_at IS NOT NULL AND ended_at IS NULL)
                OR (status::text IN ('ENDED') AND activated_at IS NOT NULL AND ended_at IS NOT NULL)),
            EXCLUDE USING gist(class_id WITH =,
                (tstzrange(activated_at,least(valid_until,ended_at),'[)')) WITH &&) WHERE(activated_at IS NOT NULL));
        CREATE TABLE enrollments(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            institution_id bigint NOT NULL REFERENCES institutions(id),
            class_id bigint NOT NULL, membership_id bigint NOT NULL,
            status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status::text IN ('PENDING','ACTIVE','SUSPENDED','COMPLETED','WITHDRAWN','TRANSFERRED','CANCELLED')),
            valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL,
            activated_at timestamptz, ended_at timestamptz,
            episode_number integer NOT NULL CHECK(episode_number>0),
            UNIQUE(class_id,membership_id,episode_number),
            version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
            state_since timestamptz NOT NULL CHECK(isfinite(state_since)),
            changed_by bigint REFERENCES user_accounts(id),
            change_reason varchar(32) NOT NULL CHECK(length(btrim(change_reason)) BETWEEN 1 AND 32),
            created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(id,institution_id),
            FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id),
            FOREIGN KEY(membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
            CHECK(isfinite(valid_from) AND isfinite(valid_until) AND valid_from<valid_until),
            CHECK(activated_at IS NULL OR (isfinite(activated_at) AND activated_at>=valid_from AND activated_at<valid_until)),
            CHECK(ended_at IS NULL OR (isfinite(ended_at) AND activated_at IS NOT NULL AND ended_at>activated_at)),
            CHECK((status::text IN ('PENDING','CANCELLED') AND activated_at IS NULL AND ended_at IS NULL)
                OR (status::text IN ('ACTIVE','SUSPENDED') AND activated_at IS NOT NULL AND ended_at IS NULL)
                OR (status::text IN ('COMPLETED','WITHDRAWN','TRANSFERRED') AND activated_at IS NOT NULL AND ended_at IS NOT NULL)),
            EXCLUDE USING gist(class_id WITH =, membership_id WITH =,
                (tstzrange(activated_at,least(valid_until,ended_at),'[)')) WITH &&) WHERE(activated_at IS NOT NULL));
        CREATE TABLE academic_period_events(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            entity_id bigint NOT NULL, institution_id bigint NOT NULL, version bigint NOT NULL,
            effective_at timestamptz NOT NULL, changed_by bigint REFERENCES user_accounts(id),
            reason varchar(32) NOT NULL, snapshot jsonb NOT NULL,
            recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(entity_id,version),
            FOREIGN KEY(entity_id,institution_id) REFERENCES academic_periods(id,institution_id));
        CREATE TABLE academic_class_events(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            entity_id bigint NOT NULL, institution_id bigint NOT NULL, version bigint NOT NULL,
            effective_at timestamptz NOT NULL, changed_by bigint REFERENCES user_accounts(id),
            reason varchar(32) NOT NULL, snapshot jsonb NOT NULL,
            recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(entity_id,version),
            FOREIGN KEY(entity_id,institution_id) REFERENCES academic_classes(id,institution_id));
        CREATE TABLE teaching_assignment_events(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            entity_id bigint NOT NULL, institution_id bigint NOT NULL, version bigint NOT NULL,
            effective_at timestamptz NOT NULL, changed_by bigint REFERENCES user_accounts(id),
            reason varchar(32) NOT NULL, snapshot jsonb NOT NULL,
            recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(entity_id,version),
            FOREIGN KEY(entity_id,institution_id) REFERENCES teaching_assignments(id,institution_id));
        CREATE TABLE enrollment_events(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            entity_id bigint NOT NULL, institution_id bigint NOT NULL, version bigint NOT NULL,
            effective_at timestamptz NOT NULL, changed_by bigint REFERENCES user_accounts(id),
            reason varchar(32) NOT NULL, snapshot jsonb NOT NULL,
            recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(entity_id,version),
            FOREIGN KEY(entity_id,institution_id) REFERENCES enrollments(id,institution_id));
        EXECUTE $ddl$ CREATE FUNCTION t11b2_history_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
            IF TG_OP='INSERT' AND pg_trigger_depth()>1 THEN RETURN NEW; END IF;
            RAISE EXCEPTION 'T11-B2 history is generated and append only; deletion/truncation forbidden';
        END $fn$ $ddl$;
        EXECUTE $ddl$ CREATE FUNCTION t11b2_event() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
            EXECUTE format('INSERT INTO %I(entity_id,institution_id,version,effective_at,changed_by,reason,snapshot) VALUES($1,$2,$3,$4,$5,$6,$7)',TG_ARGV[0])
            USING NEW.id,NEW.institution_id,NEW.version,NEW.state_since,NEW.changed_by,NEW.change_reason,to_jsonb(NEW);
            RETURN NEW;
        END $fn$ $ddl$;
        EXECUTE $ddl$ CREATE FUNCTION t11b2_academic_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        DECLARE p academic_periods%ROWTYPE; institution_status text;
        BEGIN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B2 academic identity cannot be deleted'; END IF;
            SELECT status INTO institution_status FROM institutions WHERE id=NEW.institution_id FOR UPDATE;
            IF TG_OP='INSERT' THEN
                IF NEW.status<>'PLANNED' OR NEW.version<>0 OR NEW.change_reason<>'CREATED' THEN
                    RAISE EXCEPTION 'T11-B2 initial academic state';
                END IF;
            ELSE
                IF NEW.id<>OLD.id OR NEW.institution_id<>OLD.institution_id OR NEW.local_code<>OLD.local_code
                    OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'T11-B2 immutable academic identity'; END IF;
                IF NEW.state_since<=OLD.state_since THEN RAISE EXCEPTION 'T11-B2 non-monotonic transition'; END IF;
                IF TG_TABLE_NAME='academic_periods' THEN
                    IF NEW.valid_from<>OLD.valid_from OR NEW.valid_until<>OLD.valid_until OR NEW.name<>OLD.name
                        OR NOT ((OLD.status='PLANNED' AND NEW.status='OPEN') OR (OLD.status='OPEN' AND NEW.status='CLOSED')
                            OR (OLD.status='CLOSED' AND NEW.status='ARCHIVED')) THEN RAISE EXCEPTION 'T11-B2 invalid period transition'; END IF;
                ELSE
                    IF NEW.period_id<>OLD.period_id OR NEW.name<>OLD.name OR NEW.course<>OLD.course
                        OR NEW.grade<>OLD.grade OR NEW.section<>OLD.section
                        OR NOT ((OLD.status='PLANNED' AND NEW.status='ACTIVE') OR (OLD.status='ACTIVE' AND NEW.status='CLOSED')
                            OR (OLD.status='CLOSED' AND NEW.status='ARCHIVED')) THEN RAISE EXCEPTION 'T11-B2 invalid class transition'; END IF;
                END IF;
                IF NEW.change_reason<>NEW.status THEN RAISE EXCEPTION 'T11-B2 invalid academic reason'; END IF;
                NEW.version:=OLD.version+1; NEW.updated_at:=statement_timestamp();
            END IF;
            IF NEW.status IN ('OPEN','ACTIVE') THEN
                IF institution_status IS DISTINCT FROM 'ACTIVE' THEN RAISE EXCEPTION 'T11-B2 institution not active'; END IF;
                IF TG_TABLE_NAME='academic_periods' THEN
                    IF NEW.state_since<NEW.valid_from OR NEW.state_since>=NEW.valid_until THEN RAISE EXCEPTION 'T11-B2 period activation outside window'; END IF;
                ELSE
                    SELECT * INTO p FROM academic_periods WHERE id=NEW.period_id AND institution_id=NEW.institution_id FOR UPDATE;
                    IF NOT FOUND OR p.status<>'OPEN' OR NEW.state_since<p.state_since OR NEW.state_since<p.valid_from OR NEW.state_since>=p.valid_until THEN
                        RAISE EXCEPTION 'T11-B2 class activation requires open period and valid window';
                    END IF;
                END IF;
            END IF;
            RETURN NEW;
        END $fn$ $ddl$;
        EXECUTE $ddl$ CREATE FUNCTION t11b2_relation_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        DECLARE m institution_memberships%ROWTYPE; c academic_classes%ROWTYPE; p academic_periods%ROWTYPE;
            role_name text; initial_state text; terminal boolean; inst_status text;
        BEGIN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B2 relation history cannot be deleted'; END IF;
            -- Same internal lock order as service: membership, institution, period, class, relation.
            -- B1 grant/revoke/ending also locks membership, so activation reads the committed grant state.
            SELECT * INTO m FROM institution_memberships WHERE id=NEW.membership_id AND institution_id=NEW.institution_id FOR UPDATE;
            IF NOT FOUND THEN RAISE EXCEPTION 'T11-B2 inconsistent membership institution'; END IF;
            SELECT status INTO inst_status FROM institutions WHERE id=NEW.institution_id FOR UPDATE;
            SELECT * INTO c FROM academic_classes WHERE id=NEW.class_id AND institution_id=NEW.institution_id;
            IF NOT FOUND THEN RAISE EXCEPTION 'T11-B2 inconsistent class institution'; END IF;
            SELECT * INTO p FROM academic_periods WHERE id=c.period_id AND institution_id=NEW.institution_id FOR UPDATE;
            SELECT * INTO c FROM academic_classes WHERE id=NEW.class_id AND institution_id=NEW.institution_id FOR UPDATE;
            IF NEW.valid_from<p.valid_from OR NEW.valid_until>p.valid_until THEN RAISE EXCEPTION 'T11-B2 relation outside period'; END IF;
            role_name:=CASE WHEN TG_TABLE_NAME='teaching_assignments' THEN 'DOCENTE' ELSE 'ESTUDIANTE' END;
            initial_state:=CASE WHEN TG_TABLE_NAME='teaching_assignments' THEN 'PLANNED' ELSE 'PENDING' END;
            terminal:=NEW.status IN ('ENDED','COMPLETED','WITHDRAWN','TRANSFERRED');
            IF TG_OP='INSERT' THEN
                IF NEW.status<>initial_state OR NEW.version<>0 OR NEW.change_reason<>'CREATED'
                    OR NEW.activated_at IS NOT NULL OR NEW.ended_at IS NOT NULL THEN RAISE EXCEPTION 'T11-B2 initial relation state'; END IF;
            ELSE
                IF NEW.id<>OLD.id OR NEW.institution_id<>OLD.institution_id OR NEW.class_id<>OLD.class_id
                    OR NEW.membership_id<>OLD.membership_id OR NEW.valid_from<>OLD.valid_from OR NEW.valid_until<>OLD.valid_until
                    OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'T11-B2 immutable relation identity/window'; END IF;
                IF TG_TABLE_NAME='enrollments' THEN
                    IF NEW.episode_number<>OLD.episode_number THEN RAISE EXCEPTION 'T11-B2 immutable episode'; END IF;
                ELSE
                    IF NEW.function<>OLD.function THEN RAISE EXCEPTION 'T11-B2 immutable function'; END IF;
                END IF;
                IF NEW.state_since<OLD.state_since OR (NEW.state_since=OLD.state_since
                    AND NOT (OLD.status=initial_state AND NEW.status='ACTIVE')) THEN
                    RAISE EXCEPTION 'T11-B2 non-monotonic transition';
                END IF;
                IF NOT ((OLD.status=initial_state AND NEW.status IN ('ACTIVE','CANCELLED'))
                    OR (OLD.status='ACTIVE' AND (terminal OR (TG_TABLE_NAME='enrollments' AND NEW.status='SUSPENDED')))
                    OR (TG_TABLE_NAME='enrollments' AND OLD.status='SUSPENDED' AND (terminal OR NEW.status='ACTIVE'))) THEN
                    RAISE EXCEPTION 'T11-B2 invalid relation transition';
                END IF;
                IF NEW.change_reason<>NEW.status THEN RAISE EXCEPTION 'T11-B2 invalid relation reason'; END IF;
                IF OLD.status=initial_state AND NEW.status='ACTIVE' THEN
                    IF NEW.activated_at IS DISTINCT FROM NEW.state_since THEN RAISE EXCEPTION 'T11-B2 activation instant required'; END IF;
                ELSIF NEW.activated_at IS DISTINCT FROM OLD.activated_at THEN RAISE EXCEPTION 'T11-B2 immutable activation'; END IF;
                IF terminal THEN
                    IF NEW.ended_at IS DISTINCT FROM NEW.state_since THEN RAISE EXCEPTION 'T11-B2 ending instant required'; END IF;
                ELSIF NEW.ended_at IS DISTINCT FROM OLD.ended_at THEN RAISE EXCEPTION 'T11-B2 cannot rewrite ending'; END IF;
                NEW.version:=OLD.version+1; NEW.updated_at:=statement_timestamp();
            END IF;
            IF NEW.status='ACTIVE' THEN
                IF inst_status IS DISTINCT FROM 'ACTIVE' OR p.status<>'OPEN' OR c.status<>'ACTIVE'
                    OR NEW.state_since<c.state_since OR NEW.state_since<p.state_since
                    OR NEW.state_since<NEW.valid_from OR NEW.state_since>=NEW.valid_until THEN
                    RAISE EXCEPTION 'T11-B2 activation requires active class/institution and valid window';
                END IF;
                IF m.status<>'ACTIVE' OR NEW.state_since<m.state_since OR NEW.state_since<m.valid_from
                    OR (m.valid_until IS NOT NULL AND NEW.state_since>=m.valid_until)
                    OR NOT EXISTS(SELECT 1 FROM membership_role_grants g WHERE g.membership_id=m.id AND g.institution_id=m.institution_id
                        AND g.role=role_name AND g.status='ACTIVE' AND g.valid_from<=NEW.state_since
                        AND (g.valid_until IS NULL OR NEW.state_since<g.valid_until)) THEN
                    RAISE EXCEPTION 'T11-B2 activation requires current membership and institutional role';
                END IF;
            END IF;
            RETURN NEW;
        END $fn$ $ddl$;
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON academic_periods FOR EACH ROW EXECUTE FUNCTION t11b2_academic_guard();
        CREATE TRIGGER t11b2_event AFTER INSERT OR UPDATE ON academic_periods FOR EACH ROW EXECUTE FUNCTION t11b2_event('academic_period_events');
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON academic_periods FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON academic_period_events FOR EACH ROW EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON academic_period_events FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON academic_classes FOR EACH ROW EXECUTE FUNCTION t11b2_academic_guard();
        CREATE TRIGGER t11b2_event AFTER INSERT OR UPDATE ON academic_classes FOR EACH ROW EXECUTE FUNCTION t11b2_event('academic_class_events');
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON academic_classes FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON academic_class_events FOR EACH ROW EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON academic_class_events FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON teaching_assignments FOR EACH ROW EXECUTE FUNCTION t11b2_relation_guard();
        CREATE TRIGGER t11b2_event AFTER INSERT OR UPDATE ON teaching_assignments FOR EACH ROW EXECUTE FUNCTION t11b2_event('teaching_assignment_events');
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON teaching_assignments FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON teaching_assignment_events FOR EACH ROW EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON teaching_assignment_events FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON enrollments FOR EACH ROW EXECUTE FUNCTION t11b2_relation_guard();
        CREATE TRIGGER t11b2_event AFTER INSERT OR UPDATE ON enrollments FOR EACH ROW EXECUTE FUNCTION t11b2_event('enrollment_events');
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON enrollments FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_guard BEFORE INSERT OR UPDATE OR DELETE ON enrollment_events FOR EACH ROW EXECUTE FUNCTION t11b2_history_guard();
        CREATE TRIGGER t11b2_truncate BEFORE TRUNCATE ON enrollment_events FOR EACH STATEMENT EXECUTE FUNCTION t11b2_history_guard();
    END IF;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
        SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') AS definition
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version')
        UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version')
        UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version')
        UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version')
        UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b2_%'
        UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle
        FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('academic_periods_id_seq','academic_classes_id_seq','teaching_assignments_id_seq','enrollments_id_seq','academic_period_events_id_seq','academic_class_events_id_seq','teaching_assignment_events_id_seq','enrollment_events_id_seq')
        UNION ALL SELECT 'audit-event-check:'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='event_type' AND a.attnum=ANY(c.conkey)
        WHERE c.conrelid=to_regclass(current_schema()||'.system_logs') AND c.contype='c'
    ) definitions;
    IF installed THEN
        IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'T11-B2 schema divergence; no repair attempted'; END IF;
    ELSE
        INSERT INTO t11b2_schema_version(version,revision,fingerprint) VALUES(1,'t11b2-v1',actual);
    END IF;
END $migration$;

-- T11-B1 / version 1. Explicit expansion, never run by application startup.
-- psql --single-transaction --set ON_ERROR_STOP=1 --file t11b1-v1.sql
-- Select and verify the target schema through PGOPTIONS/search_path first.
DO $migration$
DECLARE
    installed boolean;
    expected text;
    actual text;
BEGIN
    PERFORM pg_advisory_xact_lock(110011, 1);
    IF current_schema() IS NULL OR NOT EXISTS (SELECT 1 FROM pg_class
        WHERE oid=to_regclass('user_accounts') AND relnamespace=current_schema()::regnamespace) THEN
        RAISE EXCEPTION 'T11-B1 requires a selected legacy schema';
    END IF;
    installed := EXISTS (SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace
        AND relname='t11b1_schema_version');
    IF installed THEN
        SELECT fingerprint INTO expected FROM t11b1_schema_version WHERE version=1 AND revision='t11b1-v1';
        IF expected IS NULL OR (SELECT count(*) FROM t11b1_schema_version) <> 1 THEN
            RAISE EXCEPTION 'T11-B1 unknown schema version';
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace
            AND relname IN ('institutions','institution_memberships','membership_events','membership_role_grants'))
            OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b1_%') THEN
            RAISE EXCEPTION 'T11-B1 unversioned objects; reconcile before expansion';
        END IF;
        -- The operator classes must be visible in the explicitly selected search_path.
        CREATE EXTENSION IF NOT EXISTS btree_gist;
        CREATE TABLE t11b1_schema_version (
            version integer PRIMARY KEY CHECK (version=1), revision text NOT NULL,
            fingerprint text NOT NULL, installed_at timestamptz NOT NULL DEFAULT statement_timestamp()
        );
        CREATE TABLE institutions (
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            platform_code varchar(64) NOT NULL UNIQUE CHECK (platform_code ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
            name varchar(200) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
            timezone varchar(100) NOT NULL DEFAULT 'America/Lima',
            status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','ARCHIVED')),
            access_version bigint NOT NULL DEFAULT 0 CHECK (access_version>=0),
            created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            updated_at timestamptz NOT NULL DEFAULT statement_timestamp()
        );
        CREATE TABLE institution_memberships (
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            institution_id bigint NOT NULL REFERENCES institutions(id),
            account_id bigint NOT NULL REFERENCES user_accounts(id),
            status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','ENDED')),
            valid_from timestamptz NOT NULL,
            valid_until timestamptz,
            state_since timestamptz NOT NULL,
            access_version bigint NOT NULL DEFAULT 0 CHECK (access_version>=0),
            changed_by bigint REFERENCES user_accounts(id),
            change_reason varchar(32) NOT NULL CHECK (change_reason IN ('CREATED','ACTIVATED','SUSPENDED','REACTIVATED','ENDED','READMITTED','ROLE_CHANGED')),
            created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE (institution_id,account_id), UNIQUE (id,institution_id),
            CHECK (isfinite(valid_from) AND isfinite(state_since) AND state_since>=valid_from),
            CHECK (valid_until IS NULL OR (isfinite(valid_until) AND valid_from<valid_until AND state_since<=valid_until)),
            CHECK ((status='ENDED')=(valid_until IS NOT NULL))
        );
        CREATE TABLE membership_events (
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            membership_id bigint NOT NULL,
            institution_id bigint NOT NULL,
            status varchar(16) NOT NULL,
            valid_from timestamptz NOT NULL, valid_until timestamptz, state_since timestamptz NOT NULL,
            access_version bigint NOT NULL, changed_by bigint REFERENCES user_accounts(id),
            reason varchar(32) NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE (membership_id,access_version),
            FOREIGN KEY (membership_id,institution_id) REFERENCES institution_memberships(id,institution_id)
        );
        CREATE TABLE membership_role_grants (
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            membership_id bigint NOT NULL, institution_id bigint NOT NULL,
            role varchar(32) NOT NULL CHECK (role IN ('ADMIN_INSTITUCION','DOCENTE','ESTUDIANTE')),
            status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
            valid_from timestamptz NOT NULL, valid_until timestamptz,
            granted_by bigint REFERENCES user_accounts(id), revoked_by bigint REFERENCES user_accounts(id),
            revoked_at timestamptz, revocation_reason varchar(32),
            created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            FOREIGN KEY (membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
            CHECK (isfinite(valid_from) AND (valid_until IS NULL OR (isfinite(valid_until) AND valid_from<valid_until))),
            CHECK ((status='ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
                OR (status='REVOKED' AND revoked_at IS NOT NULL AND isfinite(revoked_at)
                    AND revocation_reason IS NOT NULL AND revocation_reason IN ('REMOVED','MEMBERSHIP_ENDED'))),
            -- A revoked grant retains its effective history; revocation before start is an empty range.
            EXCLUDE USING gist (membership_id WITH =, role WITH =,
                (tstzrange(valid_from, CASE WHEN revoked_at IS NULL THEN valid_until
                    ELSE greatest(valid_from,least(valid_until,revoked_at)) END, '[)')) WITH &&)
        );
        EXECUTE $ddl$
        CREATE FUNCTION t11b1_institution_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B1 institutions cannot be deleted'; END IF;
            IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name=NEW.timezone) THEN
                RAISE EXCEPTION 'T11-B1 invalid timezone';
            END IF;
            IF TG_OP='INSERT' THEN
                IF NEW.status<>'DRAFT' OR NEW.access_version<>0 THEN RAISE EXCEPTION 'T11-B1 initial institution state'; END IF;
            ELSE
                IF NEW.id<>OLD.id OR NEW.platform_code<>OLD.platform_code OR NEW.created_at<>OLD.created_at THEN
                    RAISE EXCEPTION 'T11-B1 immutable institution identity';
                END IF;
                IF OLD.status='ARCHIVED' OR (NEW.status<>OLD.status AND NOT
                    ((OLD.status='DRAFT' AND NEW.status='ACTIVE') OR
                     (OLD.status='ACTIVE' AND NEW.status='SUSPENDED') OR
                     (OLD.status='SUSPENDED' AND NEW.status IN ('ACTIVE','ARCHIVED')))) THEN
                    RAISE EXCEPTION 'T11-B1 invalid institution transition';
                END IF;
                NEW.access_version:=OLD.access_version+1; NEW.updated_at:=statement_timestamp();
            END IF;
            RETURN NEW;
        END $fn$ $ddl$;
        CREATE TRIGGER t11b1_institution_guard BEFORE INSERT OR UPDATE OR DELETE ON institutions
            FOR EACH ROW EXECUTE FUNCTION t11b1_institution_guard();
        EXECUTE $ddl$
        CREATE FUNCTION t11b1_membership_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B1 membership history cannot be deleted'; END IF;
            IF TG_OP='INSERT' THEN
                IF NEW.status<>'PENDING' OR NEW.access_version<>0 OR NEW.change_reason<>'CREATED'
                    OR NEW.state_since<>NEW.valid_from THEN RAISE EXCEPTION 'T11-B1 initial membership state'; END IF;
            ELSE
                IF NEW.id<>OLD.id OR NEW.institution_id<>OLD.institution_id OR NEW.account_id<>OLD.account_id
                    OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'T11-B1 immutable membership identity'; END IF;
                IF NEW.status=OLD.status THEN
                    IF NEW.change_reason<>'ROLE_CHANGED' OR NEW.valid_from<>OLD.valid_from
                        OR NEW.valid_until IS DISTINCT FROM OLD.valid_until OR NEW.state_since<>OLD.state_since THEN
                        RAISE EXCEPTION 'T11-B1 invalid membership update'; END IF;
                ELSE
                    IF NEW.state_since<=OLD.state_since THEN RAISE EXCEPTION 'T11-B1 non-monotonic transition'; END IF;
                    IF NOT ((OLD.status='PENDING' AND NEW.status='ACTIVE' AND NEW.change_reason='ACTIVATED') OR
                        (OLD.status='ACTIVE' AND NEW.status='SUSPENDED' AND NEW.change_reason='SUSPENDED') OR
                        (OLD.status='SUSPENDED' AND NEW.status='ACTIVE' AND NEW.change_reason='REACTIVATED') OR
                        (OLD.status IN ('ACTIVE','SUSPENDED') AND NEW.status='ENDED' AND NEW.change_reason='ENDED') OR
                        (OLD.status='ENDED' AND NEW.status='ACTIVE' AND NEW.change_reason='READMITTED')) THEN
                        RAISE EXCEPTION 'T11-B1 invalid membership transition'; END IF;
                    IF OLD.status='ENDED' THEN
                        IF NEW.valid_from<>NEW.state_since OR NEW.valid_until IS NOT NULL THEN
                            RAISE EXCEPTION 'T11-B1 readmission must open a new interval'; END IF;
                    ELSIF NEW.valid_from<>OLD.valid_from OR
                        (NEW.status='ENDED' AND NEW.valid_until IS DISTINCT FROM NEW.state_since) OR
                        (NEW.status<>'ENDED' AND NEW.valid_until IS NOT NULL) THEN
                        RAISE EXCEPTION 'T11-B1 cannot rewrite membership interval';
                    END IF;
                    IF NEW.status='ENDED' AND EXISTS (SELECT 1 FROM membership_role_grants
                        WHERE membership_id=NEW.id AND (status='ACTIVE' OR
                            (revoked_at>NEW.valid_until AND valid_from<revoked_at
                                AND (valid_until IS NULL OR valid_until>NEW.valid_until)))) THEN
                        RAISE EXCEPTION 'T11-B1 revoke grants within membership interval before ending'; END IF;
                END IF;
                NEW.access_version:=OLD.access_version+1; NEW.updated_at:=statement_timestamp();
            END IF;
            RETURN NEW;
        END $fn$ $ddl$;
        CREATE TRIGGER t11b1_membership_guard BEFORE INSERT OR UPDATE OR DELETE ON institution_memberships
            FOR EACH ROW EXECUTE FUNCTION t11b1_membership_guard();
        EXECUTE $ddl$
        CREATE FUNCTION t11b1_membership_event() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
            INSERT INTO membership_events(membership_id,institution_id,status,valid_from,valid_until,state_since,access_version,changed_by,reason)
            VALUES(NEW.id,NEW.institution_id,NEW.status,NEW.valid_from,NEW.valid_until,NEW.state_since,NEW.access_version,NEW.changed_by,NEW.change_reason);
            RETURN NEW;
        END $fn$ $ddl$;
        CREATE TRIGGER t11b1_membership_event AFTER INSERT OR UPDATE ON institution_memberships
            FOR EACH ROW EXECUTE FUNCTION t11b1_membership_event();
        EXECUTE $ddl$
        CREATE FUNCTION t11b1_history_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
            IF TG_OP='INSERT' AND pg_trigger_depth()>1 THEN RETURN NEW; END IF;
            RAISE EXCEPTION 'T11-B1 history is append only and generated by membership transitions';
        END $fn$ $ddl$;
        CREATE TRIGGER t11b1_history_guard BEFORE INSERT OR UPDATE OR DELETE ON membership_events
            FOR EACH ROW EXECUTE FUNCTION t11b1_history_guard();
        CREATE TRIGGER t11b1_history_truncate BEFORE TRUNCATE ON membership_events
            FOR EACH STATEMENT EXECUTE FUNCTION t11b1_history_guard();
        EXECUTE $ddl$
        CREATE FUNCTION t11b1_grant_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        DECLARE m institution_memberships%ROWTYPE;
        BEGIN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B1 grant history cannot be deleted'; END IF;
            SELECT * INTO m FROM institution_memberships WHERE id=NEW.membership_id FOR UPDATE;
            IF NOT FOUND OR m.institution_id<>NEW.institution_id THEN RAISE EXCEPTION 'T11-B1 inconsistent institution'; END IF;
            IF TG_OP='INSERT' THEN
                IF m.status<>'ACTIVE' OR NEW.status<>'ACTIVE' OR NEW.valid_from<m.state_since THEN
                    RAISE EXCEPTION 'T11-B1 grant requires active membership and current interval'; END IF;
            ELSE
                IF OLD.status<>'ACTIVE' OR NEW.status<>'REVOKED' OR NEW.id<>OLD.id
                    OR NEW.membership_id<>OLD.membership_id OR NEW.institution_id<>OLD.institution_id OR NEW.role<>OLD.role
                    OR NEW.valid_from<>OLD.valid_from OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
                    OR NEW.granted_by IS DISTINCT FROM OLD.granted_by OR NEW.created_at<>OLD.created_at
                    OR NEW.revoked_at<m.state_since THEN RAISE EXCEPTION 'T11-B1 grants can only be revoked'; END IF;
            END IF;
            RETURN NEW;
        END $fn$ $ddl$;
        CREATE TRIGGER t11b1_grant_guard BEFORE INSERT OR UPDATE OR DELETE ON membership_role_grants
            FOR EACH ROW EXECUTE FUNCTION t11b1_grant_guard();
        EXECUTE $ddl$
        CREATE FUNCTION t11b1_grant_version() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
            UPDATE institution_memberships SET change_reason='ROLE_CHANGED',
                changed_by=CASE WHEN TG_OP='INSERT' THEN NEW.granted_by ELSE NEW.revoked_by END WHERE id=NEW.membership_id;
            RETURN NEW;
        END $fn$ $ddl$;
        CREATE TRIGGER t11b1_grant_version AFTER INSERT OR UPDATE ON membership_role_grants
            FOR EACH ROW EXECUTE FUNCTION t11b1_grant_version();
        -- No TRUNCATE escape for persistent identities/grants (ordinary DELETE is guarded above).
        CREATE TRIGGER t11b1_institutions_truncate BEFORE TRUNCATE ON institutions
            FOR EACH STATEMENT EXECUTE FUNCTION t11b1_history_guard();
        CREATE TRIGGER t11b1_memberships_truncate BEFORE TRUNCATE ON institution_memberships
            FOR EACH STATEMENT EXECUTE FUNCTION t11b1_history_guard();
        CREATE TRIGGER t11b1_grants_truncate BEFORE TRUNCATE ON membership_role_grants
            FOR EACH STATEMENT EXECUTE FUNCTION t11b1_history_guard();
    END IF;
    -- Catalog signature excludes data, OIDs and sequence consumption. Detects changed columns,
    -- constraints (including NOT VALID), indexes, triggers/enablement, functions and identity sequences.
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
    IF installed THEN
        IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'T11-B1 schema divergence; no repair was attempted'; END IF;
    ELSE
        INSERT INTO t11b1_schema_version(version,revision,fingerprint) VALUES(1,'t11b1-v1',actual);
    END IF;
END $migration$;

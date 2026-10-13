-- T11-B2 v2: explicit audit vocabulary transition from the immutable v1 baseline.
-- Apply B1 v1, B2 v1, then this file; validate current B2 with v2 thereafter.
-- Original fingerprints are evidence of the predecessor and are never overwritten.
DO $migration$
DECLARE installed boolean; expected text; predecessor text; actual text; b1_expected text;
        audit_check record; event_column smallint;
BEGIN
    PERFORM pg_advisory_xact_lock(110011,1);
    IF current_schema() IS NULL OR to_regclass(current_schema()||'.t11b1_schema_version') IS NULL
        OR to_regclass(current_schema()||'.t11b2_schema_version') IS NULL THEN
        RAISE EXCEPTION 'T11-B2 v2 requires the versioned B1/B2 v1 baseline';
    END IF;
    SELECT fingerprint INTO b1_expected FROM t11b1_schema_version WHERE version=1 AND revision='t11b1-v1';
    IF b1_expected IS NULL OR (SELECT count(*) FROM t11b1_schema_version)<>1 THEN
        RAISE EXCEPTION 'T11-B2 v2 unknown B1 version';
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
    SELECT fingerprint INTO predecessor FROM t11b2_schema_version WHERE version=1 AND revision='t11b2-v1';
    IF predecessor IS NULL OR (SELECT count(*) FROM t11b2_schema_version)<>1 THEN
        RAISE EXCEPTION 'T11-B2 v2 unknown predecessor';
    END IF;
    installed:=to_regclass(current_schema()||'.t11b2_audit_version') IS NOT NULL;
    IF installed THEN
        SELECT fingerprint INTO expected FROM t11b2_audit_version
            WHERE version=2 AND revision='t11b2-v2' AND predecessor_fingerprint=predecessor;
        IF expected IS NULL OR (SELECT count(*) FROM t11b2_audit_version)<>1 THEN
            RAISE EXCEPTION 'T11-B2 v2 unknown transition or predecessor divergence';
        END IF;
    ELSE
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
        IF actual IS DISTINCT FROM predecessor THEN
            RAISE EXCEPTION 'T11-B2 v2 predecessor schema divergence; no repair attempted';
        END IF;
        IF to_regclass(current_schema()||'.system_logs') IS NULL THEN
            RAISE EXCEPTION 'T11-B2 v2 requires the legacy audit schema';
        END IF;
        LOCK TABLE system_logs IN ACCESS EXCLUSIVE MODE;
        SELECT attnum INTO event_column FROM pg_attribute
            WHERE attrelid=to_regclass(current_schema()||'.system_logs') AND attname='event_type' AND NOT attisdropped;
        IF event_column IS NULL OR NOT EXISTS(SELECT 1 FROM pg_constraint
            WHERE conrelid=to_regclass(current_schema()||'.system_logs') AND contype='c' AND event_column=ANY(conkey)) THEN
            RAISE EXCEPTION 'T11-B2 v2 requires a closed legacy event CHECK';
        END IF;
        FOR audit_check IN SELECT conname,conkey,convalidated,pg_get_expr(conbin,conrelid) AS expression
            FROM pg_constraint WHERE conrelid=to_regclass(current_schema()||'.system_logs')
            AND contype='c' AND event_column=ANY(conkey) ORDER BY conname LOOP
            IF audit_check.conkey<>ARRAY[event_column] OR NOT audit_check.convalidated THEN
                RAISE EXCEPTION 'T11-B2 unsupported audit event constraint; explicit reconciliation required';
            END IF;
            EXECUTE format('ALTER TABLE system_logs DROP CONSTRAINT %I',audit_check.conname);
            EXECUTE format($check$ALTER TABLE system_logs ADD CONSTRAINT %I CHECK ((%s) OR event_type::text IN
                ('INSTITUTION_CREATED','INSTITUTION_STATE_CHANGED','MEMBERSHIP_CREATED','MEMBERSHIP_STATE_CHANGED',
                 'MEMBERSHIP_ROLE_GRANTED','MEMBERSHIP_ROLE_REVOKED','ACADEMIC_PERIOD_CREATED','ACADEMIC_PERIOD_STATE_CHANGED','ACADEMIC_CLASS_CREATED','ACADEMIC_CLASS_STATE_CHANGED',
                 'TEACHING_ASSIGNMENT_CREATED','TEACHING_ASSIGNMENT_STATE_CHANGED','ENROLLMENT_CREATED','ENROLLMENT_STATE_CHANGED'))$check$,
                audit_check.conname,replace(audit_check.expression,'::character varying','::text'));
        END LOOP;
        CREATE TABLE t11b2_audit_version(version integer PRIMARY KEY CHECK(version=2),
            revision text NOT NULL, predecessor_fingerprint text NOT NULL,
            fingerprint text NOT NULL, installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
    END IF;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
        SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') AS definition
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b2_%'
        UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle
        FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('academic_periods_id_seq','academic_classes_id_seq','teaching_assignments_id_seq','enrollments_id_seq','academic_period_events_id_seq','academic_class_events_id_seq','teaching_assignment_events_id_seq','enrollment_events_id_seq')
        UNION ALL SELECT 'audit-event-check:'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='event_type' AND a.attnum=ANY(c.conkey)
        WHERE c.conrelid=to_regclass(current_schema()||'.system_logs') AND c.contype='c'
    ) definitions;
    IF installed THEN
        IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'T11-B2 v2 schema divergence; no repair attempted'; END IF;
    ELSE
        INSERT INTO t11b2_audit_version(version,revision,predecessor_fingerprint,fingerprint)
            VALUES(2,'t11b2-v2',predecessor,actual);
    END IF;
END $migration$;

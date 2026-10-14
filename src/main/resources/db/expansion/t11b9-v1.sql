-- T11-B9 / P04: frozen B8 full-chain certifier. Eleven historical files unchanged.
DO $required_b9$ BEGIN
 IF to_regclass(current_schema()||'.t11b8_schema_version') IS NULL THEN
  RAISE EXCEPTION 'T11-B9 requires installed certified B8 v1'; END IF;
END $required_b9$;
-- T11-B8 v1 / P03. Frozen B7 full-chain certifier, unchanged below.
-- Ten historical SQL and their ledgers remain intact. Explicit installation only.
DO $required_b8$ BEGIN
 IF to_regclass(current_schema()||'.t11b7_schema_version') IS NULL THEN
  RAISE EXCEPTION 'T11-B8 requires installed certified B7 v1'; END IF;
END $required_b8$;
-- T11-B7 v1 / P02. Frozen, unchanged B6 full-chain certifier follows.
-- Additive tables only. Nine predecessor SQL/ledgers/fingerprints preserved.
-- B7 v1 is the successor certifier. No startup installation or live backfill.
DO $required_b7$ BEGIN
 IF to_regclass(current_schema()||'.t11b6_schema_version') IS NULL THEN
  RAISE EXCEPTION 'T11-B7 requires installed certified B6 v1'; END IF;
END $required_b7$;
-- T11-B6 v1: versioned additive P01 preparation; no startup installer.
-- Frozen B5 certifier below, normalized ONLY for t11b6_slot_sync on B2.
-- Current full-chain certifier after installation: this file. No prior ledger update.
DO $required_b6$ BEGIN
 IF to_regclass(current_schema()||'.t11b5_schema_version') IS NULL THEN
  RAISE EXCEPTION 'T11-B6 requires installed certified B5 v1'; END IF;
END $required_b6$;
-- T11-B5 v1: explicit additive identity preparation. No startup installer.
-- The following frozen B4 v2 certifier also checks B1/B2/B3. Require installed
-- successors first: its installation branches cannot execute in this script.
DO $required$ BEGIN
 IF to_regclass(current_schema()||'.t11b4_apply_version') IS NULL
  OR to_regclass(current_schema()||'.t11b3_apply_version') IS NULL THEN
  RAISE EXCEPTION 'T11-B5 requires installed certified B4 v2 and B3 v2'; END IF;
END $required$;
-- T11-B4 v2. Explicit successor; prior SQL sources and version fingerprints are immutable.
-- Current certifiers after transition: B1 v1, B3 v2, B4 v2. B4 v1 alone rejects a v2 catalog.
-- T11-B4 v1. Explicit additive transition. No runtime installer or real backfill.
DO $prerequisite$ BEGIN
 IF to_regclass(current_schema()||'.t11b3_apply_version') IS NULL THEN
  RAISE EXCEPTION 'T11-B4 requires installed certified B3 v2'; END IF;
END $prerequisite$;
-- T11-B3 v2. Explicit, atomic transition from certified B3 v1; never a startup installer.
-- Preserves B1/B2/B3 v1 sources and version rows. Current certifiers: B1 v1 + B3 v2.
DO $migration$
DECLARE installed boolean; expected text; actual text; predecessor text; successor text; original text;
BEGIN
    PERFORM pg_advisory_xact_lock(110011,1);
    IF to_regclass(current_schema()||'.t11b3_schema_version') IS NULL
       OR to_regclass(current_schema()||'.t11b2_audit_version') IS NULL THEN
        RAISE EXCEPTION 'T11-B3 v2 requires certified B1/B2/B3 v1'; END IF;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
        SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') AS definition
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND NOT (r.relname='teaching_assignments' AND t.tgname='t11b6_slot_sync') AND r.relname IN ('institutions','institution_memberships','membership_events','membership_role_grants')
        UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b1_%'
        UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle
        FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('institutions_id_seq','institution_memberships_id_seq','membership_events_id_seq','membership_role_grants_id_seq')
    ) definitions;

    IF actual IS DISTINCT FROM (SELECT fingerprint FROM t11b1_schema_version WHERE revision='t11b1-v1') THEN
        RAISE EXCEPTION 'T11-B3 B1 prerequisite divergence'; END IF;
    SELECT fingerprint INTO predecessor FROM t11b2_audit_version WHERE revision='t11b2-v2' AND version=2
        AND predecessor_fingerprint=(SELECT fingerprint FROM t11b2_schema_version WHERE revision='t11b2-v1');
    IF predecessor IS NULL OR (SELECT count(*) FROM t11b2_audit_version)<>1 THEN
        RAISE EXCEPTION 'T11-B3 unknown B2 predecessor'; END IF;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
        SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') AS definition
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND NOT (r.relname='teaching_assignments' AND t.tgname='t11b6_slot_sync') AND r.relname IN ('academic_periods','academic_classes','teaching_assignments','enrollments','academic_period_events','academic_class_events','teaching_assignment_events','enrollment_events','t11b2_schema_version','t11b2_audit_version')
        UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b2_%'
        UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle
        FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname IN ('academic_periods_id_seq','academic_classes_id_seq','teaching_assignments_id_seq','enrollments_id_seq','academic_period_events_id_seq','academic_class_events_id_seq','teaching_assignment_events_id_seq','enrollment_events_id_seq')
        UNION ALL SELECT 'audit-event-check:'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated
        FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='event_type' AND a.attnum=ANY(c.conkey)
        WHERE c.conrelid=to_regclass(current_schema()||'.system_logs') AND c.contype='c'
    ) definitions;

    SELECT fingerprint,b2_successor_fingerprint INTO original,successor FROM t11b3_schema_version
      WHERE revision='t11b3-v1' AND version=1 AND predecessor_fingerprint=predecessor;
    IF original IS NULL OR actual IS DISTINCT FROM successor OR (SELECT count(*) FROM t11b3_schema_version)<>1 THEN
        RAISE EXCEPTION 'T11-B3 v2 B2 successor or B3 predecessor divergence'; END IF;
    installed:=to_regclass(current_schema()||'.t11b3_apply_version') IS NOT NULL;
    expected:=original;
    IF installed THEN
      SELECT fingerprint INTO expected FROM t11b3_apply_version
        WHERE version=2 AND revision='t11b3-v2' AND predecessor_fingerprint=original;
      IF expected IS NULL OR (SELECT count(*) FROM t11b3_apply_version)<>1 THEN
        RAISE EXCEPTION 'T11-B3 v2 unknown successor'; END IF;
    END IF;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
      SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b3_%' AND a.attnum>0 AND NOT a.attisdropped
      UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b3_%'
      UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b3_%'
      UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b3_%'
      UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND r.relname LIKE 't11b3_%'
      UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b3_%'
      UNION ALL SELECT 'view:'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b3_%' AND c.relkind='v'
      UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b3_%'
      UNION ALL SELECT 'storage-function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname IN ('storage_delta','storage_row_quota','storage_owner_immutable')
      UNION ALL SELECT 'quota:'||resource||':'||maximum FROM storage_limits WHERE resource IN ('REVISION_BYTES','REVISION_COUNT')
    ) definitions;
    IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'T11-B3 v2 schema divergence; no repair attempted'; END IF;
    IF NOT installed THEN
        CREATE TABLE t11b3_apply_version(version integer PRIMARY KEY CHECK(version=2),revision text NOT NULL,
          predecessor_fingerprint text NOT NULL,fingerprint text NOT NULL,
          installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
        EXECUTE $ddl$
        CREATE OR REPLACE FUNCTION t11b3_apply(entry bigint,actor bigint DEFAULT NULL) RETURNS text LANGUAGE plpgsql AS $fn$
        #variable_conflict use_column
        DECLARE r t11b3_reconciliations%ROWTYPE; inst bigint; author bigint; member bigint; grp bigint; budget bigint;
          rev bigint; delivery bigint; recipient bigint; x jsonb; y jsonb; acct record; bad boolean:=false; allbad boolean:=false;
          used bigint; activeid bigint; mapkind text; pkcol text; status_result text; src jsonb;
        BEGIN
          IF current_database()<>'chemicallab_t01_test' OR inet_server_addr()<>'127.0.0.1'::inet OR current_schema() !~ '^t03_[a-f0-9]{32}$' THEN RAISE EXCEPTION 'Owned disposable fixtures only; real backfill disabled'; END IF;
          -- A live writer cannot mutate any part of the snapshot during validation/application.
          LOCK TABLE evaluations,evaluation_assignments,evaluation_questions,evaluation_options,evaluation_attempts,
            evaluation_answers,evaluation_attempt_adjustments,evaluation_attempt_events,student_profiles,teacher_profiles,
            concept_contents,concept_materials,concept_assignments,concept_content_formation_steps,concept_content_key_points,concept_content_examples,institutions,institution_memberships,academic_classes,enrollments
            IN SHARE ROW EXCLUSIVE MODE;
          SELECT * INTO STRICT r FROM t11b3_reconciliations WHERE id=entry FOR UPDATE;
          -- Approval is required before freshness can cause any transition.
          IF r.status='PROPOSED' THEN RAISE EXCEPTION 'Explicit fixture approval required'; END IF;
          -- STALE is terminal even if the source subsequently matches its old snapshot.
          IF r.status='STALE' THEN RETURN r.status; END IF;
          src:=t11b3_snapshot(r.source_kind,r.source_pk)||jsonb_build_object('map_context',t11b3_map_snapshot(r.proposed));
          IF md5(src::text)<>r.source_checksum THEN
            UPDATE t11b3_reconciliations SET status='STALE',changed_by=actor WHERE id=entry;
            RETURN (SELECT status FROM t11b3_reconciliations WHERE id=entry);
          END IF;
          IF r.status IN ('APPLIED','CONFLICT') THEN RETURN r.status; END IF;
          inst:=(r.proposed->>'institutionId')::bigint;author:=(r.proposed->>'authorAccountId')::bigint;member:=(r.proposed->>'authorMembershipId')::bigint;
          -- Identity claims come exclusively from the explicit map, checked against historical authorship.
          IF inst IS NULL OR author IS NULL OR author<>(src->'author'->>'user_id')::bigint
            OR NOT EXISTS(SELECT 1 FROM institutions WHERE id=inst)
            OR (member IS NOT NULL AND NOT EXISTS(SELECT 1 FROM institution_memberships WHERE id=member AND institution_id=inst AND account_id=author))
            OR jsonb_typeof(r.proposed->'deliveries') IS DISTINCT FROM 'array'
            OR jsonb_typeof(r.proposed->'contexts') IS DISTINCT FROM 'array' THEN allbad:=true; END IF;
          IF r.source_kind='EVALUATION' THEN
            IF EXISTS(SELECT 1 FROM t11b3_opportunity_groups WHERE evaluation_id=r.source_pk) THEN
              UPDATE t11b3_reconciliations SET status='CONFLICT',changed_by=actor WHERE id=entry; RETURN 'CONFLICT'; END IF;
            INSERT INTO t11b3_opportunity_groups(origin,evaluation_id,max_attempts,configuration,assignments,opportunities_closed,evaluated_at_local,reconciliation_id)
              VALUES(r.origin,r.source_pk,(src->'resource'->>'max_attempts')::int,src->'resource',src->'assignments',t11b3_review_closed(src->'resource',src->'assignments',statement_timestamp() AT TIME ZONE 'America/Lima'),statement_timestamp() AT TIME ZONE 'America/Lima',entry) RETURNING id INTO grp;
            FOR acct IN SELECT p.user_id FROM student_profiles p JOIN evaluation_attempts a ON a.student_id=p.id WHERE a.evaluation_id=r.source_pk UNION SELECT (y->>'accountId')::bigint FROM jsonb_array_elements(r.proposed->'deliveries') x CROSS JOIN LATERAL jsonb_array_elements(x->'recipients') y ORDER BY user_id LOOP
              SELECT count(*),min(id) FILTER(WHERE status='IN_PROGRESS') INTO used,activeid FROM evaluation_attempts a JOIN
                (SELECT id AS profile FROM student_profiles WHERE user_id=acct.user_id) p ON p.profile=a.student_id WHERE a.evaluation_id=r.source_pk;
              bad:=allbad OR (SELECT count(*) FROM student_profiles WHERE user_id=acct.user_id)<>1
                 OR EXISTS(SELECT 1 FROM evaluation_attempts a JOIN student_profiles p ON p.id=a.student_id WHERE a.evaluation_id=r.source_pk AND p.user_id=acct.user_id
                   GROUP BY p.user_id HAVING count(DISTINCT attempt_number)<>count(*) OR min(attempt_number)<>1 OR max(attempt_number)<>count(*) OR count(*) FILTER(WHERE status='IN_PROGRESS')>1)
                 OR used>(src->'resource'->>'max_attempts')::int;
              allbad:=allbad OR bad;
              INSERT INTO t11b3_account_attempt_budgets(group_id,account_id,used_count,next_number,active_attempt_id,status,reconciliation_id)
                VALUES(grp,acct.user_id,used,CASE WHEN bad THEN NULL ELSE used+1 END,CASE WHEN bad THEN NULL ELSE activeid END,CASE WHEN bad THEN 'CONFLICT' ELSE 'RECONCILED' END,entry) RETURNING id INTO budget;
              INSERT INTO t11b3_attempt_consumptions(attempt_id,budget_id,group_id,account_id,attempt_number,status_at_reconciliation,verified,source_snapshot,reconciliation_id)
                SELECT a.id,budget,grp,acct.user_id,a.attempt_number,a.status,NOT bad,to_jsonb(a),entry FROM evaluation_attempts a JOIN student_profiles p ON p.id=a.student_id
                WHERE a.evaluation_id=r.source_pk AND p.user_id=acct.user_id ORDER BY a.id;
            END LOOP;
          END IF;
          -- Mapping contradictions are preserved as conflict; business/SQL/quota failures roll everything back.
          BEGIN
            IF allbad THEN RAISE EXCEPTION USING ERRCODE='P1133',MESSAGE='Contradictory legacy source/map'; END IF;
            mapkind:=lower(r.source_kind);pkcol:=CASE WHEN r.source_kind='CONCEPT' THEN 'concept_id' ELSE 'evaluation_id' END;
            EXECUTE format('INSERT INTO t11b3_%s_resources(%s,institution_id,author_account_id,author_membership_id,reconciliation_id) VALUES($1,$2,$3,$4,$5)',mapkind,pkcol) USING r.source_pk,inst,author,member,entry;
            -- A revision copies immutable content/refs, not attempts nor the mutable roster.
            EXECUTE format('INSERT INTO t11b3_%s_revisions(%s,institution_id,revision_number,payload,payload_checksum,reconciliation_id) VALUES($1,$2,1,$3,md5($3::text),$4) RETURNING id',mapkind,pkcol)
              INTO rev USING r.source_pk,inst,src-ARRAY['attempts','profiles','answers','adjustments','events','map_context'],entry;
            FOR x IN SELECT value FROM jsonb_array_elements(r.proposed->'deliveries') LOOP
              SELECT value INTO STRICT y FROM jsonb_array_elements(src->'assignments') WHERE (value->>'id')::bigint=(x->>'assignmentId')::bigint;
              EXECUTE format('INSERT INTO t11b3_%s_deliveries(institution_id,class_id,%s,revision_id,legacy_assignment_id,assignment_snapshot,reconciliation_id) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id',mapkind,pkcol)
                INTO delivery USING inst,(x->>'classId')::bigint,r.source_pk,rev,(x->>'assignmentId')::bigint,y,entry;
              FOR y IN SELECT value FROM jsonb_array_elements(x->'recipients') LOOP
                EXECUTE format('INSERT INTO t11b3_%s_recipients(delivery_id,institution_id,class_id,account_id,enrollment_id,evidence) VALUES($1,$2,$3,$4,$5,$6) RETURNING id',mapkind)
                  INTO recipient USING delivery,inst,(x->>'classId')::bigint,(y->>'accountId')::bigint,(y->>'enrollmentId')::bigint,y->>'evidence';
              END LOOP;
            END LOOP;
            IF r.source_kind='EVALUATION' THEN
              FOR x IN SELECT value FROM jsonb_array_elements(r.proposed->'contexts') LOOP
                INSERT INTO t11b3_attempt_contexts(attempt_id,institution_id,delivery_id,revision_id,recipient_id,account_id,enrollment_id,started_at,source_timezone,evidence)
                  SELECT a.id,inst,d.id,rev,rc.id,rc.account_id,(x->>'enrollmentId')::bigint,a.started_at,x->>'sourceTimezone',x->>'evidence'
                  FROM evaluation_attempts a JOIN t11b3_evaluation_deliveries d ON d.legacy_assignment_id=a.assignment_id
                  JOIN t11b3_evaluation_recipients rc ON rc.delivery_id=d.id AND rc.account_id=(x->>'accountId')::bigint
                  WHERE a.id=(x->>'attemptId')::bigint AND d.reconciliation_id=entry;
                IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P1133',MESSAGE='Missing demonstrated context'; END IF;
              END LOOP;
            END IF;
            status_result:='APPLIED';
          EXCEPTION WHEN SQLSTATE 'P1133' OR foreign_key_violation OR unique_violation OR no_data_found OR raise_exception THEN
            status_result:='CONFLICT';
          END;
          UPDATE t11b3_reconciliations SET status=status_result,changed_by=actor WHERE id=entry;
          RETURN status_result;
        END $fn$ $ddl$;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
      SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
        FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b3_%' AND a.attnum>0 AND NOT a.attisdropped
      UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b3_%'
      UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b3_%'
      UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b3_%'
      UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND r.relname LIKE 't11b3_%'
      UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b3_%'
      UNION ALL SELECT 'view:'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b3_%' AND c.relkind='v'
      UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b3_%'
      UNION ALL SELECT 'storage-function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname IN ('storage_delta','storage_row_quota','storage_owner_immutable')
      UNION ALL SELECT 'quota:'||resource||':'||maximum FROM storage_limits WHERE resource IN ('REVISION_BYTES','REVISION_COUNT')
    ) definitions;
      INSERT INTO t11b3_apply_version(version,revision,predecessor_fingerprint,fingerprint)
        VALUES(2,'t11b3-v2',original,actual);
    END IF;
END $migration$;

DO $b4v2$
DECLARE actual text; expected text; original text; predecessor text; installed boolean;
BEGIN
 PERFORM pg_advisory_xact_lock(110011,1);
 SELECT fingerprint INTO predecessor FROM t11b3_apply_version WHERE version=2 AND revision='t11b3-v2';
 IF to_regclass(current_schema()||'.t11b4_schema_version') IS NULL THEN RAISE EXCEPTION 'T11-B4 v2 requires certified B4 v1'; END IF;
 SELECT fingerprint INTO original FROM t11b4_schema_version WHERE version=1 AND revision='t11b4-v1' AND predecessor_fingerprint=predecessor;
 IF original IS NULL OR (SELECT count(*) FROM t11b4_schema_version)<>1 THEN RAISE EXCEPTION 'Unknown T11-B4 v1 predecessor'; END IF;
 installed:=to_regclass(current_schema()||'.t11b4_apply_version') IS NOT NULL;
 expected:=original;
 IF installed THEN
  SELECT fingerprint INTO expected FROM t11b4_apply_version WHERE version=2 AND revision='t11b4-v2' AND predecessor_fingerprint=original;
  IF expected IS NULL OR (SELECT count(*) FROM t11b4_apply_version)<>1 THEN RAISE EXCEPTION 'Unknown T11-B4 v2 successor'; END IF;
 END IF;
SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
 SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
 FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
 WHERE c.relnamespace=current_schema()::regnamespace AND (c.relname LIKE 't11b4_%' OR c.relname IN ('whiteboard_sessions','whiteboard_participants','whiteboard_object_ownership','usage_events','system_logs','audit_schema_version','audit_rejection_budget','storage_schema_version','storage_limits','storage_usage')) AND a.attnum>0 AND NOT a.attisdropped
 UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||
 CASE WHEN c.conname IN ('system_logs_actor_role_check','system_logs_category_check','system_logs_severity_check','usage_events_user_role_check')
 THEN regexp_replace(regexp_replace(replace(pg_get_constraintdef(c.oid),'::character varying','::text'),$rx$\('([A-Z_]+)'::text\)::text$rx$,$replacement$'\1'::text$replacement$,'g'),$rx$\(ARRAY(\[[^]]+\])\)::text\[\]$rx$,$replacement$ARRAY\1$replacement$,'g')
 ELSE pg_get_constraintdef(c.oid) END||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND (r.relname LIKE 't11b4_%' OR r.relname IN ('whiteboard_sessions','whiteboard_participants','whiteboard_object_ownership','usage_events','system_logs','audit_schema_version','audit_rejection_budget','storage_schema_version','storage_limits','storage_usage'))
 UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b4_%'
 UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b4_%'
 UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND (r.relname LIKE 't11b4_%' OR r.relname IN ('whiteboard_sessions','whiteboard_participants','whiteboard_object_ownership','usage_events','system_logs'))
 UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND (proname LIKE 't11b4_%' OR proname IN ('audit_reject_mutation','storage_delta','storage_row_quota','storage_owner_immutable'))
 UNION ALL SELECT 'view:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b4_%' AND c.relkind='v'
 UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b4_%'
 UNION ALL SELECT 'quota:'||resource||':'||maximum FROM storage_limits
) definitions;
 IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'T11-B4 v2 divergence; no repair attempted'; END IF;
 IF NOT installed THEN
  CREATE TABLE t11b4_apply_version(version integer PRIMARY KEY CHECK(version=2),revision text NOT NULL,
   predecessor_fingerprint text NOT NULL,fingerprint text NOT NULL,installed_at timestamptz NOT NULL);
  CREATE TRIGGER t11b4_immutable BEFORE UPDATE OR DELETE ON t11b4_apply_version FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
  CREATE TRIGGER t11b4_truncate BEFORE TRUNCATE ON t11b4_apply_version FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
EXECUTE $ddl$ CREATE OR REPLACE FUNCTION t11b4_event_parent(k text,s jsonb) RETURNS jsonb LANGUAGE plpgsql STABLE AS $fn$
 DECLARE typ text; pk bigint; tab text; key text:='id'; r jsonb; freshness jsonb:='{}';
 BEGIN
 typ:=CASE WHEN k='AUDIT' THEN s->>'target_type' ELSE s->>'resource_type' END;
 -- Global facts retain their v1 snapshot and classification, even with an unusual target.
 IF typ='WhiteboardSession' AND NOT coalesce(CASE WHEN k='AUDIT' THEN s->>'event_type' IN
  ('LOGIN_SUCCESS','LOGIN_FAILED','LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','SESSION_REVOKED','SESSIONS_REVOKED','USER_CREATED','USER_UPDATED','USER_DEACTIVATED','USER_REACTIVATED','PERMISSIONS_CHANGED','SYSTEM_HEALTH_CHECK','TECHNICAL_RETENTION_EXECUTED') ELSE s->>'module'='SYSTEM_STATUS' END,false) THEN
  IF coalesce(CASE WHEN k='AUDIT' THEN s->>'target_id' ELSE s->>'resource_id' END,'') !~ '^[1-9][0-9]{0,17}$' THEN
   RETURN jsonb_build_object('known',true,'record',null); END IF;
  pk:=(CASE WHEN k='AUDIT' THEN s->>'target_id' ELSE s->>'resource_id' END)::bigint;
  SELECT to_jsonb(c),jsonb_build_object('status',b.status,
   'current',b.source_checksum=md5(t11b4_snapshot('BOARD',pk,b.proposed)::text),
   'source',t11b4_snapshot('BOARD',pk,b.proposed)) INTO r,freshness
   FROM t11b4_board_contexts c JOIN t11b4_reconciliations b ON b.id=c.reconciliation_id WHERE c.board_id=pk;
  RETURN jsonb_build_object('known',true,'record',r,'institution_id',(r->>'institution_id')::bigint,
   'class_id',(r->>'class_id')::bigint,'reconciliation',freshness);
 END IF;
 tab:=CASE typ WHEN 'Institution' THEN 'institutions' WHEN 'InstitutionMembership' THEN 'institution_memberships'
  WHEN 'MembershipRoleGrant' THEN 'membership_role_grants' WHEN 'AcademicPeriod' THEN 'academic_periods'
  WHEN 'AcademicClass' THEN 'academic_classes' WHEN 'TeachingAssignment' THEN 'teaching_assignments' WHEN 'Enrollment' THEN 'enrollments'
  WHEN 'ConceptContent' THEN 't11b3_concept_resources' WHEN 'CONCEPT' THEN 't11b3_concept_resources'
  WHEN 'Evaluation' THEN 't11b3_evaluation_resources' WHEN 'EVALUATION' THEN 't11b3_evaluation_resources' END;
 IF tab IS NULL THEN RETURN jsonb_build_object('known',false); END IF;
 IF coalesce(CASE WHEN k='AUDIT' THEN s->>'target_id' ELSE s->>'resource_id' END,'') !~ '^[1-9][0-9]{0,17}$' THEN RETURN jsonb_build_object('known',true,'record',null); END IF;
 pk:=(CASE WHEN k='AUDIT' THEN s->>'target_id' ELSE s->>'resource_id' END)::bigint;
 IF tab='t11b3_concept_resources' THEN key:='concept_id'; ELSIF tab='t11b3_evaluation_resources' THEN key:='evaluation_id'; END IF;
 EXECUTE format('SELECT to_jsonb(t) FROM %I t WHERE %I=$1',tab,key) INTO r USING pk;
 IF tab LIKE 't11b3_%' AND r IS NOT NULL THEN
  SELECT jsonb_build_object('status',b.status,'current',b.source_checksum=md5((t11b3_snapshot(b.source_kind,b.source_pk)||jsonb_build_object('map_context',t11b3_map_snapshot(b.proposed)))::text))
   INTO freshness FROM t11b3_reconciliations b WHERE b.id=(r->>'reconciliation_id')::bigint;
 END IF;
 RETURN jsonb_build_object('known',true,'record',r,
  'institution_id',CASE WHEN typ='Institution' THEN pk ELSE (r->>'institution_id')::bigint END,
  'class_id',CASE WHEN typ='AcademicClass' THEN pk ELSE (r->>'class_id')::bigint END,'reconciliation',freshness);
 END $fn$ $ddl$;
EXECUTE $ddl$ CREATE OR REPLACE FUNCTION t11b4_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
 DECLARE r t11b4_reconciliations%ROWTYPE; m jsonb; s jsonb; p jsonb; e enrollments%ROWTYPE; acct bigint; at_utc timestamptz; global_event boolean; parent jsonb;
 BEGIN
 SELECT * INTO STRICT r FROM t11b4_reconciliations WHERE id=NEW.reconciliation_id FOR SHARE;
 IF r.status<>'APPROVED' OR r.source_checksum<>md5(t11b4_snapshot(r.source_kind,r.source_pk,r.proposed)::text) THEN RAISE EXCEPTION 'Approved current provenance required'; END IF;
 m:=r.proposed;s:=r.source_snapshot->'source';
 IF TG_TABLE_NAME='t11b4_board_contexts' THEN
  IF r.source_kind<>'BOARD' OR r.source_pk<>NEW.board_id OR NEW.institution_id IS DISTINCT FROM (m->>'institutionId')::bigint
   OR NEW.class_id IS DISTINCT FROM (m->>'classId')::bigint OR NEW.author_account_id IS DISTINCT FROM (r.source_snapshot->'author'->>'user_id')::bigint
   OR NEW.author_account_id IS DISTINCT FROM (m->>'authorAccountId')::bigint OR NEW.author_membership_id IS DISTINCT FROM (m->>'authorMembershipId')::bigint
   OR (NEW.author_membership_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM institution_memberships WHERE id=NEW.author_membership_id AND account_id=NEW.author_account_id)) THEN
   RAISE EXCEPTION 'Contradictory board provenance'; END IF;
 ELSIF TG_TABLE_NAME='t11b4_participant_contexts' THEN
  SELECT x INTO STRICT p FROM jsonb_array_elements(m->'participants') x WHERE (x->>'participantId')::bigint=NEW.participant_id;
  SELECT sp.user_id INTO STRICT acct FROM whiteboard_participants wp JOIN student_profiles sp ON sp.id=wp.student_id WHERE wp.id=NEW.participant_id AND wp.session_id=NEW.board_id;
  IF r.source_kind<>'BOARD' OR r.source_pk<>NEW.board_id OR acct<>NEW.account_id OR NEW.account_id IS DISTINCT FROM (p->>'accountId')::bigint
   OR NEW.institution_id IS DISTINCT FROM (m->>'institutionId')::bigint OR NEW.class_id IS DISTINCT FROM (m->>'classId')::bigint
   OR NOT EXISTS(SELECT 1 FROM t11b4_board_contexts WHERE board_id=NEW.board_id AND reconciliation_id=NEW.reconciliation_id)
   OR NEW.enrollment_id IS DISTINCT FROM (p->>'enrollmentId')::bigint OR NEW.source_timezone IS DISTINCT FROM p->>'sourceTimezone' OR NEW.evidence IS DISTINCT FROM p->>'evidence' THEN RAISE EXCEPTION 'Contradictory participant'; END IF;
  IF NEW.enrollment_id IS NOT NULL THEN
   SELECT * INTO STRICT e FROM enrollments WHERE id=NEW.enrollment_id;
   IF NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=NEW.source_timezone) THEN RAISE EXCEPTION 'Explicit source timezone required'; END IF;
   SELECT joined_at AT TIME ZONE NEW.source_timezone INTO at_utc FROM whiteboard_participants WHERE id=NEW.participant_id;
   IF e.class_id<>NEW.class_id OR e.institution_id<>NEW.institution_id OR e.activated_at IS NULL OR at_utc IS NULL
    OR at_utc<e.activated_at OR at_utc>=least(e.valid_until,e.ended_at)
    OR NOT EXISTS(SELECT 1 FROM institution_memberships WHERE id=e.membership_id AND account_id=NEW.account_id) THEN RAISE EXCEPTION 'Original enrollment is not demonstrated'; END IF;
  END IF;
 ELSE
  IF (TG_TABLE_NAME='t11b4_usage_contexts' AND r.source_kind<>'USAGE') OR (TG_TABLE_NAME='t11b4_audit_contexts' AND r.source_kind<>'AUDIT')
   OR NEW.event_id<>r.source_pk OR NEW.scope IS DISTINCT FROM m->>'scope' OR NEW.institution_id IS DISTINCT FROM (m->>'institutionId')::bigint
   OR NEW.class_id IS DISTINCT FROM (m->>'classId')::bigint OR NEW.board_id IS DISTINCT FROM (m->>'boardId')::bigint
   OR NEW.evidence IS DISTINCT FROM m->>'evidence' OR NEW.account_id IS DISTINCT FROM (r.source_snapshot->'author'->>'user_id')::bigint THEN RAISE EXCEPTION 'Contradictory event provenance'; END IF;
  global_event:=CASE WHEN r.source_kind='AUDIT' THEN s->>'event_type' IN ('LOGIN_SUCCESS','LOGIN_FAILED','LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','SESSION_REVOKED','SESSIONS_REVOKED','USER_CREATED','USER_UPDATED','USER_DEACTIVATED','USER_REACTIVATED','PERMISSIONS_CHANGED','SYSTEM_HEALTH_CHECK','TECHNICAL_RETENTION_EXECUTED') ELSE s->>'module'='SYSTEM_STATUS' END;
  IF global_event AND NEW.scope<>'GLOBAL' THEN RAISE EXCEPTION 'Global event cannot receive an institution'; END IF;
  IF NEW.scope='GLOBAL' AND NOT global_event THEN RAISE EXCEPTION 'Global provenance not demonstrated'; END IF;
  parent:=r.source_snapshot->'event_parent';
  IF NEW.scope='INSTITUTION' AND (parent->>'known')::boolean THEN
   IF parent->>'record' IS NULL OR NEW.institution_id IS DISTINCT FROM (parent->>'institution_id')::bigint
    OR NEW.class_id IS DISTINCT FROM (parent->>'class_id')::bigint
    OR (parent->'reconciliation'<>'{}'::jsonb AND (parent->'reconciliation'->>'status' IS DISTINCT FROM 'APPLIED' OR (parent->'reconciliation'->>'current')::boolean IS DISTINCT FROM true)) THEN
    RAISE EXCEPTION 'Institutional event contradicts its verified parent'; END IF;
  END IF;
  IF NEW.board_id IS NOT NULL THEN
   IF r.source_snapshot->'board_context'->>'status' IS DISTINCT FROM 'APPLIED'
    OR (r.source_snapshot->'board_context'->>'source_current')::boolean IS DISTINCT FROM true
    OR (r.source_kind='USAGE' AND (s->>'resource_type' IS DISTINCT FROM 'WhiteboardSession' OR s->>'resource_id' IS DISTINCT FROM NEW.board_id::text OR s->>'module'<>'WHITEBOARD'))
    OR (r.source_kind='AUDIT' AND (s->>'target_type' IS DISTINCT FROM 'WhiteboardSession' OR (s->>'target_id')::bigint IS DISTINCT FROM NEW.board_id)) THEN RAISE EXCEPTION 'Verified board relation required'; END IF;
  ELSIF (r.source_kind='USAGE' AND (s->>'module'='WHITEBOARD' OR s->>'resource_type'='WhiteboardSession')) OR (r.source_kind='AUDIT' AND s->>'category'='WHITEBOARD' AND s->>'target_type' IS DISTINCT FROM 'WhiteboardSession') THEN
   RAISE EXCEPTION 'Board event requires original board relation';
  END IF;
 END IF;
 RETURN NEW;
 END $fn$ $ddl$;
SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
 SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
 FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
 WHERE c.relnamespace=current_schema()::regnamespace AND (c.relname LIKE 't11b4_%' OR c.relname IN ('whiteboard_sessions','whiteboard_participants','whiteboard_object_ownership','usage_events','system_logs','audit_schema_version','audit_rejection_budget','storage_schema_version','storage_limits','storage_usage')) AND a.attnum>0 AND NOT a.attisdropped
 UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||
 CASE WHEN c.conname IN ('system_logs_actor_role_check','system_logs_category_check','system_logs_severity_check','usage_events_user_role_check')
 THEN regexp_replace(regexp_replace(replace(pg_get_constraintdef(c.oid),'::character varying','::text'),$rx$\('([A-Z_]+)'::text\)::text$rx$,$replacement$'\1'::text$replacement$,'g'),$rx$\(ARRAY(\[[^]]+\])\)::text\[\]$rx$,$replacement$ARRAY\1$replacement$,'g')
 ELSE pg_get_constraintdef(c.oid) END||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND (r.relname LIKE 't11b4_%' OR r.relname IN ('whiteboard_sessions','whiteboard_participants','whiteboard_object_ownership','usage_events','system_logs','audit_schema_version','audit_rejection_budget','storage_schema_version','storage_limits','storage_usage'))
 UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b4_%'
 UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b4_%'
 UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND NOT t.tgisinternal AND (r.relname LIKE 't11b4_%' OR r.relname IN ('whiteboard_sessions','whiteboard_participants','whiteboard_object_ownership','usage_events','system_logs'))
 UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND (proname LIKE 't11b4_%' OR proname IN ('audit_reject_mutation','storage_delta','storage_row_quota','storage_owner_immutable'))
 UNION ALL SELECT 'view:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b4_%' AND c.relkind='v'
 UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b4_%'
 UNION ALL SELECT 'quota:'||resource||':'||maximum FROM storage_limits
) definitions;
  INSERT INTO t11b4_apply_version VALUES(2,'t11b4-v2',original,actual,statement_timestamp());
 END IF;
END $b4v2$;

-- B5 catalog follows. Predecessor ledgers and catalog are not mutated.
DO $b5$
DECLARE installed boolean; actual text; expected text; tab text;
BEGIN
 PERFORM pg_advisory_xact_lock(110011,1);
 installed:=to_regclass(current_schema()||'.t11b5_schema_version') IS NOT NULL;
 IF NOT installed THEN
  IF EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace AND relname LIKE 't11b5_%')
   OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b5_%') THEN
   RAISE EXCEPTION 'T11-B5 unversioned objects; no repair'; END IF;
  CREATE TABLE t11b5_schema_version(version integer PRIMARY KEY CHECK(version=1),revision text NOT NULL,
   predecessor_fingerprint text NOT NULL,fingerprint text NOT NULL,installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
  CREATE TABLE t11b5_usage(id integer PRIMARY KEY CHECK(id=1),rows_used bigint NOT NULL CHECK(rows_used BETWEEN 0 AND 100000),
   bytes_used bigint NOT NULL CHECK(bytes_used BETWEEN 0 AND 67108864));
  INSERT INTO t11b5_usage VALUES(1,0,0);
  CREATE TABLE t11b5_admin_proofs(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,account_id bigint NOT NULL REFERENCES user_accounts(id),
   evidence text NOT NULL UNIQUE CHECK(evidence ~ '^FIXTURE-[A-Z0-9-]{1,80}$'),
   source_checksum text NOT NULL CHECK(source_checksum ~ '^[a-f0-9]{32}$'),
   observed_at timestamptz NOT NULL DEFAULT statement_timestamp(),checked_by bigint REFERENCES user_accounts(id),
   UNIQUE(id,account_id));
  CREATE TABLE t11b5_platform_role_grants(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,account_id bigint NOT NULL REFERENCES user_accounts(id),
   role text NOT NULL DEFAULT 'ADMINISTRADOR' CHECK(role='ADMINISTRADOR'),proof_id bigint NOT NULL UNIQUE,
   status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
   valid_from timestamptz NOT NULL,valid_until timestamptz,granted_by bigint REFERENCES user_accounts(id),
   revoked_at timestamptz,revoked_by bigint REFERENCES user_accounts(id),revocation_reason text,
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   FOREIGN KEY(proof_id,account_id) REFERENCES t11b5_admin_proofs(id,account_id),
   CHECK(isfinite(valid_from) AND (valid_until IS NULL OR (isfinite(valid_until) AND valid_until>valid_from))),
   CHECK((status='ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
    OR (status='REVOKED' AND revoked_at IS NOT NULL AND isfinite(revoked_at) AND revocation_reason IS NOT NULL AND revocation_reason='REMOVED')),
   EXCLUDE USING gist(account_id WITH =,role WITH =,
    tstzrange(valid_from,CASE WHEN revoked_at IS NULL THEN valid_until ELSE greatest(valid_from,least(valid_until,revoked_at)) END,'[)') WITH &&));
  CREATE TABLE t11b5_login_identifiers(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,account_id bigint NOT NULL REFERENCES user_accounts(id),
   kind text NOT NULL CHECK(kind IN ('USERNAME','EMAIL')),value text COLLATE "C" NOT NULL CHECK(length(value) BETWEEN 1 AND 255),
   comparison_value text COLLATE "C" NOT NULL,comparison_policy text NOT NULL DEFAULT 'LEGACY_EXACT_V1' CHECK(comparison_policy='LEGACY_EXACT_V1'),
   verified boolean NOT NULL DEFAULT false CHECK(NOT verified),
   evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Z0-9-]{1,80}$'),
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),recorded_by bigint REFERENCES user_accounts(id),
   UNIQUE(account_id,kind,value),UNIQUE(id,account_id,comparison_value),CHECK(comparison_value=value));
  CREATE TABLE t11b5_login_bindings(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,comparison_value text COLLATE "C" NOT NULL UNIQUE,
   account_id bigint NOT NULL,identifier_id bigint NOT NULL,
   status text NOT NULL DEFAULT 'PREPARED' CHECK(status='PREPARED'),
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),recorded_by bigint REFERENCES user_accounts(id),
   FOREIGN KEY(identifier_id,account_id,comparison_value) REFERENCES t11b5_login_identifiers(id,account_id,comparison_value));
  CREATE TABLE t11b5_identifier_conflicts(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,identifier_id bigint NOT NULL REFERENCES t11b5_login_identifiers(id),
   other_account_id bigint NOT NULL REFERENCES user_accounts(id),other_kind text NOT NULL CHECK(other_kind IN ('USERNAME','EMAIL')),
   other_value text COLLATE "C" NOT NULL,reason text NOT NULL CHECK(reason IN ('EXACT','REVIEW_ASCII')),
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   UNIQUE(identifier_id,other_account_id,other_kind,other_value));
  CREATE TABLE t11b5_academic_codes(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,institution_id bigint NOT NULL REFERENCES institutions(id),
   membership_id bigint NOT NULL UNIQUE,account_id bigint NOT NULL REFERENCES user_accounts(id),
   code text COLLATE "C" NOT NULL CHECK(length(code)>=1 AND length(code)<=100 AND code !~ '[[:cntrl:]]' AND code=btrim(code) AND code<>''),
   evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Z0-9-]{1,80}$'),
   reserved_at timestamptz NOT NULL DEFAULT statement_timestamp(),reserved_by bigint REFERENCES user_accounts(id),
   UNIQUE(institution_id,code),UNIQUE(institution_id,account_id),
   FOREIGN KEY(membership_id,institution_id) REFERENCES institution_memberships(id,institution_id));
  CREATE TABLE t11b5_identity_events(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,target_type text NOT NULL,target_id bigint NOT NULL,
   operation text NOT NULL CHECK(operation IN ('INSERT','UPDATE')),snapshot jsonb NOT NULL,
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp());

  EXECUTE $ddl$
  CREATE FUNCTION t11b5_fixture() RETURNS void LANGUAGE plpgsql AS $fn$
  BEGIN
   IF current_database()<>'chemicallab_t01_test' OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
    OR current_schema() !~ '^t03_[a-f0-9]{32}$' THEN
    RAISE EXCEPTION 'Owned disposable fixtures only; real reconciliation disabled'; END IF;
  END $fn$ $ddl$;
  EXECUTE $ddl$
  CREATE FUNCTION t11b5_review_key(v text) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $fn$
   SELECT translate(btrim(v,chr(1)||chr(2)||chr(3)||chr(4)||chr(5)||chr(6)||chr(7)||chr(8)||chr(9)||chr(10)||chr(11)||chr(12)||chr(13)||chr(14)||chr(15)||chr(16)||chr(17)||chr(18)||chr(19)||chr(20)||chr(21)||chr(22)||chr(23)||chr(24)||chr(25)||chr(26)||chr(27)||chr(28)||chr(29)||chr(30)||chr(31)||chr(32)),
    'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz') COLLATE "C"
  $fn$ $ddl$;
  EXECUTE $ddl$
  CREATE VIEW t11b5_identifier_inventory AS
   WITH all_values AS (
    SELECT id account_id,'USERNAME'::text kind,username::text COLLATE "C" value,'LEGACY'::text origin FROM user_accounts
    UNION ALL SELECT id account_id,'EMAIL'::text kind,email::text COLLATE "C" value,'LEGACY'::text origin FROM user_accounts WHERE email IS NOT NULL
    UNION ALL SELECT account_id,kind,value,'PREPARED'::text origin FROM t11b5_login_identifiers)
   SELECT DISTINCT a.*,t11b5_review_key(a.value) review_key,
    (SELECT count(DISTINCT b.account_id) FROM all_values b WHERE b.value=a.value)>1 exact_collision,
    (SELECT count(DISTINCT b.account_id) FROM all_values b WHERE t11b5_review_key(b.value)=t11b5_review_key(a.value))>1 review_collision,
    (SELECT count(DISTINCT b.kind) FROM all_values b WHERE b.value=a.value)>1 cross_type,
    a.value<>btrim(a.value,chr(9)||chr(10)||chr(13)||chr(32)) AS surrounding_whitespace
   FROM all_values a
  $ddl$;
  EXECUTE $ddl$
  CREATE VIEW t11b5_identifier_observations AS
   SELECT i.*,EXISTS(SELECT 1 FROM user_accounts u WHERE u.id=i.account_id AND
    CASE i.kind WHEN 'USERNAME' THEN u.username ELSE u.email END COLLATE "C"=i.value) source_current,
    CASE WHEN EXISTS(SELECT 1 FROM t11b5_identifier_conflicts c WHERE c.identifier_id=i.id)
     OR EXISTS(SELECT 1 FROM t11b5_identifier_inventory v WHERE v.value=i.value AND (v.exact_collision OR v.review_collision)) THEN 'CONFLICT'
     WHEN NOT EXISTS(SELECT 1 FROM user_accounts u WHERE u.id=i.account_id AND CASE i.kind WHEN 'USERNAME' THEN u.username ELSE u.email END COLLATE "C"=i.value) THEN 'STALE'
     ELSE 'PREPARED' END status
   FROM t11b5_login_identifiers i
  $ddl$;
  EXECUTE $ddl$
  CREATE VIEW t11b5_binding_observations AS
   SELECT b.*,i.status AS reconciliation_status,i.source_current,false AS authentication_enabled
   FROM t11b5_login_bindings b JOIN t11b5_identifier_observations i ON i.id=b.identifier_id
  $ddl$;
  EXECUTE $ddl$
  CREATE VIEW t11b5_membership_timeline AS
   WITH previous AS (SELECT e.*,lag(status) OVER w previous_status FROM membership_events e
    WINDOW w AS (PARTITION BY membership_id ORDER BY access_version)),
   episodes AS (SELECT p.*,sum(CASE WHEN status='ACTIVE' AND (previous_status='ENDED' OR previous_status='PENDING') THEN 1 ELSE 0 END)
    OVER(PARTITION BY membership_id ORDER BY access_version ROWS UNBOUNDED PRECEDING) episode_number FROM previous p)
   SELECT e.*,CASE WHEN previous_status IS NULL THEN 'CREATED' WHEN status=previous_status THEN 'ROLE_CHANGED'
    WHEN previous_status='ENDED' AND status='ACTIVE' THEN 'READMITTED'
    WHEN previous_status='SUSPENDED' AND status='ACTIVE' THEN 'REACTIVATED'
    ELSE reason END transition FROM episodes e
  $ddl$;
  EXECUTE $ddl$
  CREATE FUNCTION t11b5_immutable() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN RAISE EXCEPTION 'T11-B5 immutable identity/reservation/history'; END $fn$ $ddl$;
  EXECUTE $ddl$
  CREATE FUNCTION t11b5_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE u user_accounts%ROWTYPE; m institution_memberships%ROWTYPE; p t11b5_admin_proofs%ROWTYPE;
  BEGIN
   PERFORM t11b5_fixture();
   IF TG_TABLE_NAME='t11b5_academic_codes' THEN
    SELECT * INTO STRICT m FROM institution_memberships WHERE id=NEW.membership_id FOR UPDATE;
    IF m.institution_id<>NEW.institution_id OR m.account_id<>NEW.account_id THEN RAISE EXCEPTION 'Code identity contradicts membership'; END IF;
   ELSIF TG_TABLE_NAME='t11b5_admin_proofs' THEN
    SELECT * INTO STRICT u FROM user_accounts WHERE id=NEW.account_id FOR UPDATE;
    NEW.observed_at:=statement_timestamp();
    IF u.role::text<>'ADMINISTRADOR' OR md5(to_jsonb(u)::text)<>NEW.source_checksum THEN RAISE EXCEPTION 'Explicit current legacy administrator provenance required'; END IF;
   ELSIF TG_TABLE_NAME='t11b5_platform_role_grants' THEN
    SELECT * INTO STRICT u FROM user_accounts WHERE id=NEW.account_id FOR UPDATE;
    IF TG_OP='INSERT' THEN
     NEW.recorded_at:=statement_timestamp();
     SELECT * INTO STRICT p FROM t11b5_admin_proofs WHERE id=NEW.proof_id AND account_id=NEW.account_id;
     IF u.role::text<>'ADMINISTRADOR' OR md5(to_jsonb(u)::text)<>p.source_checksum OR NEW.status<>'ACTIVE' OR NEW.valid_from<p.observed_at THEN
      RAISE EXCEPTION 'Verified explicit legacy platform privilege required'; END IF;
    ELSE
     IF OLD.status<>'ACTIVE' OR NEW.status<>'REVOKED' OR NEW.revoked_at<OLD.recorded_at
      OR (to_jsonb(NEW)-ARRAY['status','revoked_at','revoked_by','revocation_reason']) IS DISTINCT FROM
       (to_jsonb(OLD)-ARRAY['status','revoked_at','revoked_by','revocation_reason']) THEN RAISE EXCEPTION 'Only append-only revocation is allowed'; END IF;
    END IF;
   ELSIF TG_TABLE_NAME='t11b5_login_identifiers' THEN
    SELECT * INTO STRICT u FROM user_accounts WHERE id=NEW.account_id FOR UPDATE;
    IF NEW.value IS DISTINCT FROM (CASE NEW.kind WHEN 'USERNAME' THEN u.username ELSE u.email END) THEN
     RAISE EXCEPTION 'Explicit identifier map does not match legacy source'; END IF;
   ELSIF TG_TABLE_NAME='t11b5_login_bindings' THEN
    -- Table locks serialize even direct SQL with concurrent legacy account/claim writers.
    LOCK TABLE user_accounts,t11b5_login_identifiers IN SHARE ROW EXCLUSIVE MODE;
    IF NOT EXISTS(SELECT 1 FROM t11b5_identifier_observations WHERE id=NEW.identifier_id AND status='PREPARED')
     OR EXISTS(SELECT 1 FROM t11b5_identifier_inventory WHERE value=NEW.comparison_value AND (exact_collision OR review_collision)) THEN
      RAISE EXCEPTION 'Identifier collision/stale source blocks reconciliation'; END IF;
   END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$
  CREATE FUNCTION t11b5_history() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   INSERT INTO t11b5_identity_events(target_type,target_id,operation,snapshot) VALUES(TG_TABLE_NAME,NEW.id,TG_OP,to_jsonb(NEW));
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$
  CREATE FUNCTION t11b5_event_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF TG_OP<>'INSERT' OR pg_trigger_depth()<>2 THEN RAISE EXCEPTION 'T11-B5 generated immutable history only'; END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$
  CREATE FUNCTION t11b5_capture_conflicts() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   INSERT INTO t11b5_identifier_conflicts(identifier_id,other_account_id,other_kind,other_value,reason)
    SELECT DISTINCT NEW.id,v.account_id,v.kind,v.value,CASE WHEN v.value=NEW.value THEN 'EXACT' ELSE 'REVIEW_ASCII' END
    FROM t11b5_identifier_inventory v WHERE v.account_id<>NEW.account_id AND v.review_key=t11b5_review_key(NEW.value);
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$
  CREATE FUNCTION t11b5_charge() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   UPDATE t11b5_usage SET rows_used=rows_used+CASE WHEN TG_OP='INSERT' THEN 1 ELSE 0 END,
    bytes_used=bytes_used+octet_length(to_jsonb(NEW)::text)-CASE WHEN TG_OP='INSERT' THEN 0 ELSE octet_length(to_jsonb(OLD)::text) END WHERE id=1;
   IF NOT FOUND THEN RAISE EXCEPTION 'T11-B5 quota state missing'; END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  FOREACH tab IN ARRAY ARRAY['t11b5_admin_proofs','t11b5_login_identifiers','t11b5_login_bindings','t11b5_academic_codes','t11b5_platform_role_grants'] LOOP
   EXECUTE format('CREATE TRIGGER t11b5_guard BEFORE INSERT %s ON %I FOR EACH ROW EXECUTE FUNCTION t11b5_guard()',CASE WHEN tab='t11b5_platform_role_grants' THEN 'OR UPDATE' ELSE '' END,tab);
   EXECUTE format('CREATE TRIGGER t11b5_immutable BEFORE %s DELETE ON %I FOR EACH ROW EXECUTE FUNCTION t11b5_immutable()',CASE WHEN tab='t11b5_platform_role_grants' THEN '' ELSE 'UPDATE OR' END,tab);
   EXECUTE format('CREATE TRIGGER t11b5_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b5_immutable()',tab);
   EXECUTE format('CREATE TRIGGER t11b5_history AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION t11b5_history()',tab);
   EXECUTE format('CREATE TRIGGER t11b5_charge AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION t11b5_charge()',tab);
  END LOOP;
  CREATE TRIGGER t11b5_event_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b5_identity_events FOR EACH ROW EXECUTE FUNCTION t11b5_event_guard();
  CREATE TRIGGER t11b5_no_truncate BEFORE TRUNCATE ON t11b5_identity_events FOR EACH STATEMENT EXECUTE FUNCTION t11b5_immutable();
  CREATE TRIGGER t11b5_charge AFTER INSERT ON t11b5_identity_events FOR EACH ROW EXECUTE FUNCTION t11b5_charge();
  CREATE TRIGGER t11b5_capture_conflicts AFTER INSERT ON t11b5_login_identifiers FOR EACH ROW EXECUTE FUNCTION t11b5_capture_conflicts();
  CREATE TRIGGER t11b5_generated BEFORE INSERT OR UPDATE OR DELETE ON t11b5_identifier_conflicts FOR EACH ROW EXECUTE FUNCTION t11b5_event_guard();
  CREATE TRIGGER t11b5_no_truncate BEFORE TRUNCATE ON t11b5_identifier_conflicts FOR EACH STATEMENT EXECUTE FUNCTION t11b5_immutable();
  CREATE TRIGGER t11b5_charge AFTER INSERT ON t11b5_identifier_conflicts FOR EACH ROW EXECUTE FUNCTION t11b5_charge();
 END IF;
 SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
  SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
   FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
   WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b5_%' AND a.attnum>0 AND NOT a.attisdropped
  UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b5_%'
  UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b5_%'
  UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b5_%'
  UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b5_%' AND NOT t.tgisinternal
  UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b5_%'
  UNION ALL SELECT 'view:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b5_%' AND c.relkind='v'
  UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b5_%'
 ) definitions;
 IF installed THEN
  SELECT fingerprint INTO expected FROM t11b5_schema_version WHERE version=1 AND revision='t11b5-v1'
   AND predecessor_fingerprint=(SELECT fingerprint FROM t11b4_apply_version WHERE version=2 AND revision='t11b4-v2');
  IF expected IS DISTINCT FROM actual OR (SELECT count(*) FROM t11b5_schema_version)<>1 THEN RAISE EXCEPTION 'T11-B5 divergence; no repair'; END IF;
 ELSE
  INSERT INTO t11b5_schema_version(version,revision,predecessor_fingerprint,fingerprint)
   SELECT 1,'t11b5-v1',fingerprint,actual FROM t11b4_apply_version WHERE version=2;
 END IF;
END $b5$;

DO $b6$
DECLARE installed boolean; actual text; expected text; tab text;
BEGIN
 PERFORM pg_advisory_xact_lock(110011,1);
 installed:=to_regclass(current_schema()||'.t11b6_schema_version') IS NOT NULL;
 IF NOT installed THEN
  IF EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace AND relname LIKE 't11b6_%')
   OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b6_%') THEN
   RAISE EXCEPTION 'T11-B6 unversioned objects; no repair'; END IF;
  LOCK TABLE teaching_assignments IN ACCESS EXCLUSIVE MODE;
  CREATE TABLE t11b6_schema_version(version integer PRIMARY KEY CHECK(version=1),revision text NOT NULL,
   predecessor_fingerprint text NOT NULL,fingerprint text NOT NULL,installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
  CREATE TABLE t11b6_usage(id integer PRIMARY KEY CHECK(id=1),rows_used bigint NOT NULL CHECK(rows_used BETWEEN 0 AND 100000),
   bytes_used bigint NOT NULL CHECK(bytes_used BETWEEN 0 AND 67108864));
  INSERT INTO t11b6_usage VALUES(1,0,0);
          CREATE TABLE t11b6_co_assignments(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            institution_id bigint NOT NULL REFERENCES institutions(id),
            class_id bigint NOT NULL, membership_id bigint NOT NULL,
            status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status::text IN ('PLANNED','ACTIVE','ENDED','CANCELLED')),
            valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL,
            activated_at timestamptz, ended_at timestamptz,
            function varchar(16) NOT NULL DEFAULT 'CO_DOCENTE' CHECK(function::text='CO_DOCENTE'),
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
            EXCLUDE USING gist(class_id WITH =, membership_id WITH =,
                (tstzrange(activated_at,least(valid_until,ended_at),'[)')) WITH &&) WHERE(activated_at IS NOT NULL));
        CREATE TABLE t11b6_co_events(
            id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            entity_id bigint NOT NULL, institution_id bigint NOT NULL, version bigint NOT NULL,
            effective_at timestamptz NOT NULL, changed_by bigint REFERENCES user_accounts(id),
            reason varchar(32) NOT NULL, snapshot jsonb NOT NULL,
            recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
            UNIQUE(entity_id,version),
            FOREIGN KEY(entity_id,institution_id) REFERENCES t11b6_co_assignments(id,institution_id));
EXECUTE $ddl$
CREATE FUNCTION t11b6_co_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        DECLARE m institution_memberships%ROWTYPE; c academic_classes%ROWTYPE; p academic_periods%ROWTYPE;
            role_name text; initial_state text; terminal boolean; inst_status text;
        BEGIN
            IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B6 relation history cannot be deleted'; END IF;
            -- Same internal lock order as service: membership, institution, period, class, relation.
            -- B1 grant/revoke/ending also locks membership, so activation reads the committed grant state.
            SELECT * INTO m FROM institution_memberships WHERE id=NEW.membership_id AND institution_id=NEW.institution_id FOR UPDATE;
            IF NOT FOUND THEN RAISE EXCEPTION 'T11-B6 inconsistent membership institution'; END IF;
            SELECT status INTO inst_status FROM institutions WHERE id=NEW.institution_id FOR UPDATE;
            SELECT * INTO c FROM academic_classes WHERE id=NEW.class_id AND institution_id=NEW.institution_id;
            IF NOT FOUND THEN RAISE EXCEPTION 'T11-B6 inconsistent class institution'; END IF;
            SELECT * INTO p FROM academic_periods WHERE id=c.period_id AND institution_id=NEW.institution_id FOR UPDATE;
            SELECT * INTO c FROM academic_classes WHERE id=NEW.class_id AND institution_id=NEW.institution_id FOR UPDATE;
            IF NEW.valid_from<p.valid_from OR NEW.valid_until>p.valid_until THEN RAISE EXCEPTION 'T11-B6 relation outside period'; END IF;
            role_name:=CASE WHEN TG_TABLE_NAME='t11b6_co_assignments' THEN 'DOCENTE' ELSE 'ESTUDIANTE' END;
            initial_state:=CASE WHEN TG_TABLE_NAME='t11b6_co_assignments' THEN 'PLANNED' ELSE 'PENDING' END;
            terminal:=NEW.status IN ('ENDED','COMPLETED','WITHDRAWN','TRANSFERRED');
            IF TG_OP='INSERT' THEN
                IF NEW.status<>initial_state OR NEW.version<>0 OR NEW.change_reason<>'CREATED'
                    OR NEW.activated_at IS NOT NULL OR NEW.ended_at IS NOT NULL THEN RAISE EXCEPTION 'T11-B6 initial relation state'; END IF;
            ELSE
                IF NEW.id<>OLD.id OR NEW.institution_id<>OLD.institution_id OR NEW.class_id<>OLD.class_id
                    OR NEW.membership_id<>OLD.membership_id OR NEW.valid_from<>OLD.valid_from OR NEW.valid_until<>OLD.valid_until
                    OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'T11-B6 immutable relation identity/window'; END IF;
                IF TG_TABLE_NAME='enrollments' THEN
                    IF NEW.episode_number<>OLD.episode_number THEN RAISE EXCEPTION 'T11-B6 immutable episode'; END IF;
                ELSE
                    IF NEW.function<>OLD.function THEN RAISE EXCEPTION 'T11-B6 immutable function'; END IF;
                END IF;
                IF NEW.state_since<OLD.state_since OR (NEW.state_since=OLD.state_since
                    AND NOT (OLD.status=initial_state AND NEW.status='ACTIVE')) THEN
                    RAISE EXCEPTION 'T11-B6 non-monotonic transition';
                END IF;
                IF NOT ((OLD.status=initial_state AND NEW.status IN ('ACTIVE','CANCELLED'))
                    OR (OLD.status='ACTIVE' AND (terminal OR (TG_TABLE_NAME='enrollments' AND NEW.status='SUSPENDED')))
                    OR (TG_TABLE_NAME='enrollments' AND OLD.status='SUSPENDED' AND (terminal OR NEW.status='ACTIVE'))) THEN
                    RAISE EXCEPTION 'T11-B6 invalid relation transition';
                END IF;
                IF NEW.change_reason<>NEW.status THEN RAISE EXCEPTION 'T11-B6 invalid relation reason'; END IF;
                IF OLD.status=initial_state AND NEW.status='ACTIVE' THEN
                    IF NEW.activated_at IS DISTINCT FROM NEW.state_since THEN RAISE EXCEPTION 'T11-B6 activation instant required'; END IF;
                ELSIF NEW.activated_at IS DISTINCT FROM OLD.activated_at THEN RAISE EXCEPTION 'T11-B6 immutable activation'; END IF;
                IF terminal THEN
                    IF NEW.ended_at IS DISTINCT FROM NEW.state_since THEN RAISE EXCEPTION 'T11-B6 ending instant required'; END IF;
                ELSIF NEW.ended_at IS DISTINCT FROM OLD.ended_at THEN RAISE EXCEPTION 'T11-B6 cannot rewrite ending'; END IF;
                NEW.version:=OLD.version+1; NEW.updated_at:=statement_timestamp();
            END IF;
            IF NEW.status='ACTIVE' THEN
                IF inst_status IS DISTINCT FROM 'ACTIVE' OR p.status<>'OPEN' OR c.status<>'ACTIVE'
                    OR NEW.state_since<c.state_since OR NEW.state_since<p.state_since
                    OR NEW.state_since<NEW.valid_from OR NEW.state_since>=NEW.valid_until THEN
                    RAISE EXCEPTION 'T11-B6 activation requires active class/institution and valid window';
                END IF;
                IF m.status<>'ACTIVE' OR NEW.state_since<m.state_since OR NEW.state_since<m.valid_from
                    OR (m.valid_until IS NOT NULL AND NEW.state_since>=m.valid_until)
                    OR NOT EXISTS(SELECT 1 FROM membership_role_grants g WHERE g.membership_id=m.id AND g.institution_id=m.institution_id
                        AND g.role=role_name AND g.status='ACTIVE' AND g.valid_from<=NEW.state_since
                        AND (g.valid_until IS NULL OR NEW.state_since<g.valid_until)) THEN
                    RAISE EXCEPTION 'T11-B6 activation requires current membership and institutional role';
                END IF;
            END IF;
            RETURN NEW;
        END $fn$ $ddl$;
  CREATE TABLE t11b6_teaching_slots(
   responsible_id bigint,co_id bigint,institution_id bigint NOT NULL,class_id bigint NOT NULL,membership_id bigint NOT NULL,
   function text NOT NULL CHECK(function IN ('RESPONSABLE','CO_DOCENTE')),effective tstzrange NOT NULL,
   CHECK((responsible_id IS NOT NULL AND co_id IS NULL AND function='RESPONSABLE')
    OR (co_id IS NOT NULL AND responsible_id IS NULL AND function='CO_DOCENTE')),
   CHECK(NOT isempty(effective) AND NOT lower_inf(effective) AND NOT upper_inf(effective)
    AND isfinite(lower(effective)) AND isfinite(upper(effective)) AND lower_inc(effective) AND NOT upper_inc(effective)),
   UNIQUE(responsible_id),UNIQUE(co_id),
   FOREIGN KEY(responsible_id,institution_id) REFERENCES teaching_assignments(id,institution_id),
   FOREIGN KEY(co_id,institution_id) REFERENCES t11b6_co_assignments(id,institution_id),
   FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id),
   FOREIGN KEY(membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
   EXCLUDE USING gist(class_id WITH =,membership_id WITH =,effective WITH &&),
   EXCLUDE USING gist(class_id WITH =,effective WITH &&) WHERE(function='RESPONSABLE'));
  CREATE TABLE t11b6_resource_grants(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,institution_id bigint NOT NULL REFERENCES institutions(id),
   concept_id bigint,evaluation_id bigint,membership_id bigint NOT NULL,
   faculty text NOT NULL CHECK(faculty IN ('EDIT_DRAFT','PUBLISH','ASSIGN')),
   status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
   valid_from timestamptz NOT NULL,valid_until timestamptz NOT NULL,
   granted_at timestamptz NOT NULL,granted_by bigint NOT NULL REFERENCES user_accounts(id),grantor_membership_id bigint NOT NULL,
   revoked_at timestamptz,revoked_by bigint REFERENCES user_accounts(id),revoker_membership_id bigint,
   reason text NOT NULL CHECK(reason IN ('DESIGNATED','REMOVED')),
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   CHECK(num_nonnulls(concept_id,evaluation_id)=1),
   CHECK(isfinite(valid_from) AND isfinite(valid_until) AND isfinite(granted_at) AND valid_from>=granted_at AND valid_from<valid_until),
   CHECK((status='ACTIVE' AND num_nonnulls(revoked_at,revoked_by,revoker_membership_id)=0 AND reason='DESIGNATED')
    OR (status='REVOKED' AND num_nonnulls(revoked_at,revoked_by,revoker_membership_id)=3 AND isfinite(revoked_at) AND revoked_at>=granted_at AND reason='REMOVED')),
   UNIQUE(id,institution_id),
   FOREIGN KEY(concept_id,institution_id) REFERENCES t11b3_concept_resources(concept_id,institution_id),
   FOREIGN KEY(evaluation_id,institution_id) REFERENCES t11b3_evaluation_resources(evaluation_id,institution_id),
   FOREIGN KEY(membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
   FOREIGN KEY(grantor_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
   FOREIGN KEY(revoker_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
   EXCLUDE USING gist(concept_id WITH =,membership_id WITH =,faculty WITH =,
    (tstzrange(valid_from,greatest(valid_from,least(valid_until,revoked_at)),'[)')) WITH &&) WHERE(concept_id IS NOT NULL),
   EXCLUDE USING gist(evaluation_id WITH =,membership_id WITH =,faculty WITH =,
    (tstzrange(valid_from,greatest(valid_from,least(valid_until,revoked_at)),'[)')) WITH &&) WHERE(evaluation_id IS NOT NULL));
  CREATE TABLE t11b6_grant_events(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,entity_id bigint NOT NULL,institution_id bigint NOT NULL,
   operation text NOT NULL CHECK(operation IN ('INSERT','UPDATE')),snapshot jsonb NOT NULL,
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),UNIQUE(entity_id,operation),
   FOREIGN KEY(entity_id,institution_id) REFERENCES t11b6_resource_grants(id,institution_id));
  EXECUTE $ddl$ CREATE FUNCTION t11b6_immutable() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN RAISE EXCEPTION 'T11-B6 immutable evidence'; END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b6_charge() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   UPDATE t11b6_usage SET rows_used=rows_used+CASE WHEN TG_OP='INSERT' THEN 1 ELSE 0 END,
    bytes_used=bytes_used+octet_length(to_jsonb(NEW)::text)-CASE WHEN TG_OP='INSERT' THEN 0 ELSE octet_length(to_jsonb(OLD)::text) END WHERE id=1;
   IF NOT FOUND THEN RAISE EXCEPTION 'T11-B6 quota state missing'; END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b6_slot_sync() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF NEW.activated_at IS NOT NULL THEN
    IF TG_TABLE_NAME='teaching_assignments' THEN
     INSERT INTO t11b6_teaching_slots(responsible_id,institution_id,class_id,membership_id,function,effective)
      VALUES(NEW.id,NEW.institution_id,NEW.class_id,NEW.membership_id,'RESPONSABLE',tstzrange(NEW.activated_at,least(NEW.valid_until,NEW.ended_at),'[)'))
      ON CONFLICT(responsible_id) DO UPDATE SET effective=EXCLUDED.effective;
    ELSE
     INSERT INTO t11b6_teaching_slots(co_id,institution_id,class_id,membership_id,function,effective)
      VALUES(NEW.id,NEW.institution_id,NEW.class_id,NEW.membership_id,'CO_DOCENTE',tstzrange(NEW.activated_at,least(NEW.valid_until,NEW.ended_at),'[)'))
      ON CONFLICT(co_id) DO UPDATE SET effective=EXCLUDED.effective;
    END IF;
   END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b6_slot_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE src jsonb;
  BEGIN
   IF TG_OP='DELETE' OR pg_trigger_depth()<>2 THEN RAISE EXCEPTION 'T11-B6 generated teaching slots only'; END IF;
   IF NEW.responsible_id IS NOT NULL THEN SELECT to_jsonb(t) INTO src FROM teaching_assignments t WHERE id=NEW.responsible_id;
   ELSE SELECT to_jsonb(t) INTO src FROM t11b6_co_assignments t WHERE id=NEW.co_id; END IF;
   IF (src->>'institution_id')::bigint IS DISTINCT FROM NEW.institution_id
    OR (src->>'class_id')::bigint IS DISTINCT FROM NEW.class_id OR (src->>'membership_id')::bigint IS DISTINCT FROM NEW.membership_id
    OR src->>'function' IS DISTINCT FROM NEW.function
    OR (src->>'activated_at') IS NULL
    OR tstzrange((src->>'activated_at')::timestamptz,least((src->>'valid_until')::timestamptz,(src->>'ended_at')::timestamptz),'[)') IS DISTINCT FROM NEW.effective THEN
     RAISE EXCEPTION 'T11-B6 slot must match its typed source'; END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b6_role_valid(member bigint,inst bigint,role_name text,at_time timestamptz,account bigint DEFAULT NULL)
   RETURNS boolean LANGUAGE sql STABLE AS $fn$
    SELECT EXISTS(SELECT 1 FROM institution_memberships m JOIN membership_role_grants g ON g.membership_id=m.id AND g.institution_id=m.institution_id
     JOIN user_accounts u ON u.id=m.account_id
     WHERE m.id=member AND m.institution_id=inst AND (account IS NULL OR m.account_id=account) AND u.active
      AND m.status='ACTIVE' AND m.state_since<=at_time AND m.valid_from<=at_time AND (m.valid_until IS NULL OR at_time<m.valid_until)
      AND g.role=role_name AND g.status='ACTIVE' AND g.valid_from<=at_time AND (g.valid_until IS NULL OR at_time<g.valid_until))
  $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b6_grant_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE admin_member bigint; admin_account bigint; at_time timestamptz;
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B6 grant history cannot be deleted'; END IF;
   IF TG_OP='INSERT' THEN
    IF NEW.status<>'ACTIVE' THEN RAISE EXCEPTION 'T11-B6 initial grant state'; END IF;
    admin_member:=NEW.grantor_membership_id;admin_account:=NEW.granted_by;at_time:=NEW.granted_at;
   ELSE
    IF OLD.status<>'ACTIVE' OR NEW.status<>'REVOKED'
     OR (to_jsonb(NEW)-ARRAY['status','revoked_at','revoked_by','revoker_membership_id','reason']) IS DISTINCT FROM
        (to_jsonb(OLD)-ARRAY['status','revoked_at','revoked_by','revoker_membership_id','reason']) THEN
     RAISE EXCEPTION 'T11-B6 only revoke; identity, faculty, provenance and window immutable'; END IF;
    admin_member:=NEW.revoker_membership_id;admin_account:=NEW.revoked_by;at_time:=NEW.revoked_at;
   END IF;
   PERFORM id FROM institution_memberships WHERE id IN (NEW.membership_id,admin_member) ORDER BY id FOR UPDATE;
   PERFORM id FROM institutions WHERE id=NEW.institution_id AND status='ACTIVE' FOR UPDATE;
   IF NOT FOUND OR admin_member IS NULL OR admin_account IS NULL
     OR NOT t11b6_role_valid(admin_member,NEW.institution_id,'ADMIN_INSTITUCION',at_time,admin_account) THEN
    RAISE EXCEPTION 'T11-B6 same-school current administrative provenance required'; END IF;
   IF TG_OP='INSERT' AND NOT t11b6_role_valid(NEW.membership_id,NEW.institution_id,'DOCENTE',at_time) THEN
    RAISE EXCEPTION 'T11-B6 recipient requires current DOCENTE membership'; END IF;
   NEW.recorded_at:=CASE WHEN TG_OP='INSERT' THEN statement_timestamp() ELSE OLD.recorded_at END;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b6_grant_history() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   INSERT INTO t11b6_grant_events(entity_id,institution_id,operation,snapshot) VALUES(NEW.id,NEW.institution_id,TG_OP,to_jsonb(NEW));
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b6_event_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF TG_OP<>'INSERT' OR pg_trigger_depth()<>2 THEN RAISE EXCEPTION 'T11-B6 generated immutable history only'; END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  CREATE TRIGGER t11b6_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b6_co_assignments FOR EACH ROW EXECUTE FUNCTION t11b6_co_guard();
  CREATE TRIGGER t11b6_history AFTER INSERT OR UPDATE ON t11b6_co_assignments FOR EACH ROW EXECUTE FUNCTION t11b2_event('t11b6_co_events');
  CREATE TRIGGER t11b6_slot_sync AFTER INSERT OR UPDATE ON t11b6_co_assignments FOR EACH ROW EXECUTE FUNCTION t11b6_slot_sync();
  -- Seed only derived reservations, under the source table lock. Source/history/IDs remain intact.
  INSERT INTO t11b6_teaching_slots(responsible_id,institution_id,class_id,membership_id,function,effective)
   SELECT id,institution_id,class_id,membership_id,'RESPONSABLE',tstzrange(activated_at,least(valid_until,ended_at),'[)')
    FROM teaching_assignments WHERE activated_at IS NOT NULL;
  UPDATE t11b6_usage SET rows_used=(SELECT count(*) FROM t11b6_teaching_slots),
   bytes_used=(SELECT coalesce(sum(octet_length(to_jsonb(t)::text)),0) FROM t11b6_teaching_slots t) WHERE id=1;
  CREATE TRIGGER t11b6_slot_sync AFTER INSERT OR UPDATE ON teaching_assignments FOR EACH ROW EXECUTE FUNCTION t11b6_slot_sync();
  CREATE TRIGGER t11b6_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b6_teaching_slots FOR EACH ROW EXECUTE FUNCTION t11b6_slot_guard();
  CREATE TRIGGER t11b6_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b6_resource_grants FOR EACH ROW EXECUTE FUNCTION t11b6_grant_guard();
  CREATE TRIGGER t11b6_history AFTER INSERT OR UPDATE ON t11b6_resource_grants FOR EACH ROW EXECUTE FUNCTION t11b6_grant_history();
  CREATE TRIGGER t11b6_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b6_co_events FOR EACH ROW EXECUTE FUNCTION t11b6_event_guard();
  CREATE TRIGGER t11b6_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b6_grant_events FOR EACH ROW EXECUTE FUNCTION t11b6_event_guard();
  FOREACH tab IN ARRAY ARRAY['t11b6_co_assignments','t11b6_co_events','t11b6_teaching_slots','t11b6_resource_grants','t11b6_grant_events'] LOOP
   EXECUTE format('CREATE TRIGGER t11b6_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b6_immutable()',tab);
   EXECUTE format('CREATE TRIGGER t11b6_charge AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION t11b6_charge()',tab);
  END LOOP;
 END IF;
  SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
  SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
   FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
   WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b6_%' AND a.attnum>0 AND NOT a.attisdropped
  UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b6_%'
  UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b6_%'
  UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b6_%'
  UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b6_%' AND NOT t.tgisinternal
  UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b6_%'
  UNION ALL SELECT 'view:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b6_%' AND c.relkind='v'
  UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b6_%'
 UNION ALL SELECT 'source-trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname='teaching_assignments' AND t.tgname='t11b6_slot_sync'
 ) definitions;

 IF installed THEN
  SELECT fingerprint INTO expected FROM t11b6_schema_version WHERE version=1 AND revision='t11b6-v1'
   AND predecessor_fingerprint=(SELECT fingerprint FROM t11b5_schema_version WHERE version=1 AND revision='t11b5-v1');
  IF expected IS DISTINCT FROM actual OR (SELECT count(*) FROM t11b6_schema_version)<>1 THEN RAISE EXCEPTION 'T11-B6 divergence; no repair'; END IF;
 ELSE
  INSERT INTO t11b6_schema_version(version,revision,predecessor_fingerprint,fingerprint)
   SELECT 1,'t11b6-v1',fingerprint,actual FROM t11b5_schema_version WHERE version=1;
 END IF;
END $b6$;

-- Body appended to the frozen B6 certifier by the development generator.
DO $b7$
DECLARE installed boolean; expected text; actual text; tab text;
BEGIN
 PERFORM pg_advisory_xact_lock(110011,1);
 installed:=to_regclass(current_schema()||'.t11b7_schema_version') IS NOT NULL;
 IF NOT installed THEN
  IF EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace AND relname LIKE 't11b7_%')
   OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b7_%') THEN
   RAISE EXCEPTION 'T11-B7 objects without ledger; no repair'; END IF;
  CREATE TABLE t11b7_schema_version(version integer PRIMARY KEY CHECK(version=1),revision text NOT NULL UNIQUE CHECK(revision='t11b7-v1'),
   predecessor_fingerprint text NOT NULL,fingerprint text NOT NULL,installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
  CREATE TABLE t11b7_usage(id integer PRIMARY KEY CHECK(id=1),rows_used bigint NOT NULL CHECK(rows_used BETWEEN 0 AND 100000),
   bytes_used bigint NOT NULL CHECK(bytes_used BETWEEN 0 AND 67108864));
  INSERT INTO t11b7_usage VALUES(1,0,0);
  CREATE TABLE t11b7_identifiers(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,account_id bigint NOT NULL REFERENCES user_accounts(id),
   kind text NOT NULL CHECK(kind IN ('USERNAME','EMAIL')),value text COLLATE "C" NOT NULL CHECK(length(value) BETWEEN 1 AND 255),
   comparison_value text COLLATE "C" NOT NULL CHECK(comparison_value=value),comparison_policy text NOT NULL DEFAULT 'LEGACY_EXACT_V1' CHECK(comparison_policy='LEGACY_EXACT_V1'),
   origin text NOT NULL CHECK(origin IN ('LEGACY','CHANGE','LINK')),candidate_id bigint REFERENCES t11b5_login_identifiers(id),
   operation_id uuid NOT NULL UNIQUE,evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Za-z0-9_-]{1,100}$'),
   status text NOT NULL DEFAULT 'PREPARED' CHECK(status IN ('PREPARED','ACTIVE','RESERVED','REVOKED')),
   version bigint NOT NULL DEFAULT 1 CHECK(version>0),created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   changed_at timestamptz NOT NULL DEFAULT statement_timestamp(),activated_at timestamptz,terminated_at timestamptz,
   recorded_by bigint REFERENCES user_accounts(id),changed_by bigint REFERENCES user_accounts(id),proof_id bigint,
   replaces_id bigint REFERENCES t11b7_identifiers(id),replaces_version bigint,
   CHECK(num_nonnulls(replaces_id,replaces_version) IN (0,2)),
   CHECK(replaces_id IS NULL OR (kind='USERNAME' AND origin='CHANGE' AND replaces_id<>id AND replaces_version>1)),
   UNIQUE(id,account_id),CHECK((status='PREPARED' AND activated_at IS NULL AND terminated_at IS NULL AND proof_id IS NULL)
    OR (status='ACTIVE' AND activated_at IS NOT NULL AND terminated_at IS NULL AND (kind='USERNAME' OR proof_id IS NOT NULL))
    OR (status='RESERVED' AND kind='USERNAME' AND activated_at IS NOT NULL AND terminated_at>=activated_at)
    OR (status='REVOKED' AND kind='EMAIL' AND terminated_at IS NOT NULL)),
   CHECK((kind='USERNAME' AND proof_id IS NULL AND origin IN ('LEGACY','CHANGE')) OR (kind='EMAIL' AND origin IN ('LEGACY','LINK'))));
  CREATE UNIQUE INDEX t11b7_primary_username ON t11b7_identifiers(account_id) WHERE kind='USERNAME' AND status='ACTIVE';
  CREATE UNIQUE INDEX t11b7_active_value ON t11b7_identifiers(account_id,kind,value) WHERE status='ACTIVE';
  CREATE TABLE t11b7_proofs(
   id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,identifier_id bigint NOT NULL,account_id bigint NOT NULL,
   identifier_version bigint NOT NULL,operation_id uuid NOT NULL,receipt_digest text NOT NULL CHECK(receipt_digest ~ '^[a-f0-9]{64}$'),
   provider text NOT NULL CHECK(provider='FIXTURE_INTERNAL_V1'),evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Za-z0-9_-]{1,100}$'),
   status text NOT NULL DEFAULT 'ISSUED' CHECK(status IN ('ISSUED','VERIFIED','CONSUMED','REVOKED')),
   issued_at timestamptz NOT NULL DEFAULT statement_timestamp(),expires_at timestamptz NOT NULL,
   verified_at timestamptz,consumed_at timestamptz,revoked_at timestamptz,
   FOREIGN KEY(identifier_id,account_id) REFERENCES t11b7_identifiers(id,account_id),UNIQUE(id,identifier_id),UNIQUE(identifier_id,evidence),
   CHECK(isfinite(expires_at) AND expires_at>issued_at),
   CHECK((status='ISSUED' AND num_nonnulls(verified_at,consumed_at,revoked_at)=0)
    OR (status='VERIFIED' AND verified_at IS NOT NULL AND num_nonnulls(consumed_at,revoked_at)=0)
    OR (status='CONSUMED' AND num_nonnulls(verified_at,consumed_at)=2 AND revoked_at IS NULL)
    OR (status='REVOKED' AND revoked_at IS NOT NULL AND consumed_at IS NULL)));
  ALTER TABLE t11b7_identifiers ADD FOREIGN KEY(proof_id,id) REFERENCES t11b7_proofs(id,identifier_id);
  CREATE TABLE t11b7_attestations(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,proof_id bigint NOT NULL UNIQUE REFERENCES t11b7_proofs(id),
   receipt_digest text NOT NULL CHECK(receipt_digest ~ '^[a-f0-9]{64}$'),recorded_at timestamptz NOT NULL DEFAULT statement_timestamp());
  CREATE TABLE t11b7_claims(comparison_value text COLLATE "C" PRIMARY KEY,account_id bigint NOT NULL REFERENCES user_accounts(id));
  CREATE TABLE t11b7_conflicts(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,identifier_id bigint NOT NULL REFERENCES t11b7_identifiers(id),
   observed jsonb NOT NULL,fingerprint text NOT NULL,recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),UNIQUE(identifier_id));
  CREATE TABLE t11b7_reviews(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,identifier_id bigint NOT NULL REFERENCES t11b7_identifiers(id),
   identifier_version bigint NOT NULL,fingerprint text NOT NULL,observed jsonb NOT NULL,
   decision text NOT NULL CHECK(decision='APPROVE'),evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Za-z0-9_-]{1,100}$'),
   reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),reviewed_by bigint REFERENCES user_accounts(id),UNIQUE(identifier_id,identifier_version,fingerprint));
  CREATE TABLE t11b7_events(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,target_type text NOT NULL CHECK(target_type IN ('t11b7_identifiers','t11b7_proofs','t11b7_reviews')),
   target_id bigint NOT NULL,operation text NOT NULL CHECK(operation IN ('INSERT','UPDATE')),snapshot jsonb NOT NULL,
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp());
  EXECUTE $ddl$ CREATE FUNCTION t11b7_lock() RETURNS void LANGUAGE plpgsql AS $fn$
  BEGIN
   PERFORM t11b5_fixture();
   -- All internal writers take these locks before touching a row; legacy writes cannot race the inventory.
   LOCK TABLE user_accounts,t11b5_login_identifiers,t11b7_identifiers IN SHARE ROW EXCLUSIVE MODE;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_statement_lock() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN PERFORM t11b7_lock(); RETURN NULL; END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_inventory(identifier bigint) RETURNS jsonb LANGUAGE sql STABLE AS $fn$
   SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.source,x.owner,x.source_id,x.kind),'[]'::jsonb) FROM (
    SELECT 'LEGACY' source,u.id owner,u.id source_id,'USERNAME' kind,u.username value,'CURRENT' state
     FROM user_accounts u JOIN t11b7_identifiers i ON i.id=identifier AND u.id<>i.account_id AND u.username COLLATE "C"=i.value
    UNION ALL SELECT 'LEGACY',u.id,u.id,'EMAIL',u.email,'CURRENT' FROM user_accounts u JOIN t11b7_identifiers i
     ON i.id=identifier AND u.id<>i.account_id AND u.email COLLATE "C"=i.value
    UNION ALL SELECT 'B5',b.account_id,b.id,b.kind,b.value,'PREPARED' FROM t11b5_login_identifiers b JOIN t11b7_identifiers i
     ON i.id=identifier AND b.account_id<>i.account_id AND b.value=i.value
    UNION ALL SELECT 'B7',b.account_id,b.id,b.kind,b.value,b.status||':'||b.version FROM t11b7_identifiers b JOIN t11b7_identifiers i
     ON i.id=identifier AND b.account_id<>i.account_id AND b.value=i.value
   ) x
  $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_available(identifier bigint) RETURNS boolean LANGUAGE sql STABLE AS $fn$
   SELECT NOT EXISTS(SELECT 1 FROM t11b7_claims c WHERE c.comparison_value=i.value AND c.account_id<>i.account_id)
    AND NOT EXISTS(SELECT 1 FROM user_accounts u WHERE u.id<>i.account_id AND (u.username COLLATE "C"=i.value OR
     (u.email COLLATE "C"=i.value AND NOT EXISTS(SELECT 1 FROM t11b7_identifiers old WHERE old.account_id=u.id AND old.kind='EMAIL'
       AND old.value=i.value AND old.origin='LEGACY' AND old.status='REVOKED'))))
    FROM t11b7_identifiers i WHERE i.id=identifier
  $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_immutable() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN RAISE EXCEPTION 'T11-B7 append-only evidence'; END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_generated() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF TG_OP<>'INSERT' OR pg_trigger_depth() NOT IN (2,3) THEN RAISE EXCEPTION 'T11-B7 generated history only'; END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_charge() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   UPDATE t11b7_usage SET rows_used=rows_used+CASE TG_OP WHEN 'INSERT' THEN 1 WHEN 'DELETE' THEN -1 ELSE 0 END,
    bytes_used=bytes_used+CASE WHEN TG_OP='DELETE' THEN 0 ELSE octet_length(to_jsonb(NEW)::text) END
     -CASE WHEN TG_OP='INSERT' THEN 0 ELSE octet_length(to_jsonb(OLD)::text) END WHERE id=1;
   IF NOT FOUND THEN RAISE EXCEPTION 'T11-B7 quota state missing'; END IF;
   RETURN coalesce(NEW,OLD);
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_history() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   INSERT INTO t11b7_events(target_type,target_id,operation,snapshot) VALUES(TG_TABLE_NAME,NEW.id,TG_OP,to_jsonb(NEW));
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_identifier_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE p t11b7_proofs; current_observation jsonb;
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B7 identifier history retained'; END IF;
   IF NEW.changed_by IS NOT NULL AND NEW.changed_by<>NEW.account_id THEN RAISE EXCEPTION 'T11-B7 global identity requires self or controlled internal fixture'; END IF;
   IF TG_OP='INSERT' THEN
    IF NEW.status<>'PREPARED' OR NEW.version<>1 OR NEW.replaces_id IS NOT NULL OR NEW.recorded_by IS DISTINCT FROM NEW.changed_by THEN RAISE EXCEPTION 'T11-B7 prepared initial state'; END IF;
    IF NEW.origin='LEGACY' AND NOT EXISTS(SELECT 1 FROM user_accounts u WHERE u.id=NEW.account_id AND
     CASE NEW.kind WHEN 'USERNAME' THEN u.username COLLATE "C"=NEW.value ELSE u.email COLLATE "C"=NEW.value END) THEN RAISE EXCEPTION 'T11-B7 explicit current legacy origin required'; END IF;
    IF NEW.candidate_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM t11b5_login_identifiers b WHERE b.id=NEW.candidate_id AND b.account_id=NEW.account_id AND b.kind=NEW.kind AND b.value=NEW.value) THEN RAISE EXCEPTION 'T11-B7 wrong B5 provenance'; END IF;
    NEW.created_at:=statement_timestamp();NEW.changed_at:=NEW.created_at;
   ELSE
    IF NEW.version<>OLD.version+1 OR (to_jsonb(NEW)-ARRAY['status','version','changed_at','activated_at','terminated_at','changed_by','proof_id','replaces_id','replaces_version']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['status','version','changed_at','activated_at','terminated_at','changed_by','proof_id','replaces_id','replaces_version']) THEN RAISE EXCEPTION 'T11-B7 immutable identity or stale version'; END IF;
    IF OLD.status<>'PREPARED' AND (NEW.replaces_id IS DISTINCT FROM OLD.replaces_id OR NEW.replaces_version IS DISTINCT FROM OLD.replaces_version) THEN RAISE EXCEPTION 'T11-B7 replacement history immutable'; END IF;
    NEW.changed_at:=statement_timestamp();
    IF OLD.status='PREPARED' AND NEW.status='ACTIVE' THEN
     IF NEW.origin='LEGACY' AND NOT EXISTS(SELECT 1 FROM user_accounts u WHERE u.id=NEW.account_id AND
      CASE NEW.kind WHEN 'USERNAME' THEN u.username COLLATE "C"=NEW.value ELSE u.email COLLATE "C"=NEW.value END) THEN RAISE EXCEPTION 'T11-B7 stale legacy origin'; END IF;
     IF NOT t11b7_available(NEW.id) THEN RAISE EXCEPTION 'T11-B7 global owner collision' USING ERRCODE='23505'; END IF;
     current_observation:=t11b7_inventory(NEW.id);
     IF NOT EXISTS(SELECT 1 FROM t11b7_reviews v WHERE v.identifier_id=NEW.id AND v.identifier_version=OLD.version AND v.fingerprint=md5(current_observation::text)) THEN
      RAISE EXCEPTION 'T11-B7 explicit fresh conflict review required'; END IF;
     IF NEW.kind='USERNAME' AND NEW.origin='CHANGE' AND NOT EXISTS(SELECT 1 FROM t11b7_identifiers prior_identifier
      WHERE prior_identifier.id=NEW.replaces_id AND prior_identifier.version=NEW.replaces_version AND prior_identifier.account_id=NEW.account_id
       AND prior_identifier.kind='USERNAME' AND prior_identifier.status='RESERVED') THEN RAISE EXCEPTION 'T11-B7 change requires exact original reserved username/version'; END IF;
     IF NEW.kind='EMAIL' THEN
      SELECT * INTO p FROM t11b7_proofs WHERE id=NEW.proof_id FOR UPDATE;
      IF p.id IS NULL OR p.identifier_id<>NEW.id OR p.account_id<>NEW.account_id OR p.identifier_version<>OLD.version OR p.operation_id<>NEW.operation_id
       OR p.status<>'VERIFIED' OR clock_timestamp()>=p.expires_at THEN RAISE EXCEPTION 'T11-B7 fresh verified evidence required'; END IF;
      UPDATE t11b7_proofs SET status='CONSUMED',consumed_at=statement_timestamp() WHERE id=p.id;
     END IF;
     NEW.activated_at:=statement_timestamp();NEW.terminated_at:=NULL;
    ELSIF OLD.status='ACTIVE' AND NEW.status='RESERVED' AND NEW.kind='USERNAME' THEN
     NEW.activated_at:=OLD.activated_at;NEW.terminated_at:=statement_timestamp();NEW.proof_id:=OLD.proof_id;
    ELSIF OLD.status IN ('PREPARED','ACTIVE') AND NEW.status='REVOKED' AND NEW.kind='EMAIL' THEN
     NEW.activated_at:=OLD.activated_at;NEW.terminated_at:=statement_timestamp();NEW.proof_id:=OLD.proof_id;
    ELSE RAISE EXCEPTION 'T11-B7 invalid/terminal transition'; END IF;
   END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_sync() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE observed jsonb;
  BEGIN
   IF TG_OP='INSERT' THEN
    observed:=t11b7_inventory(NEW.id);
    INSERT INTO t11b7_conflicts(identifier_id,observed,fingerprint) VALUES(NEW.id,observed,md5(observed::text));
   END IF;
   IF NEW.status IN ('ACTIVE','RESERVED') THEN
    INSERT INTO t11b7_claims(comparison_value,account_id) VALUES(NEW.value,NEW.account_id)
     ON CONFLICT(comparison_value) DO UPDATE SET account_id=EXCLUDED.account_id;
   ELSIF NOT EXISTS(SELECT 1 FROM t11b7_identifiers i WHERE i.value=NEW.value AND i.status IN ('ACTIVE','RESERVED')) THEN
    DELETE FROM t11b7_claims WHERE comparison_value=NEW.value;
   END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_claim_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF pg_trigger_depth()<>2 THEN RAISE EXCEPTION 'T11-B7 generated global claims only'; END IF;
   IF TG_OP='DELETE' THEN
    IF EXISTS(SELECT 1 FROM t11b7_identifiers WHERE value=OLD.comparison_value AND status IN ('ACTIVE','RESERVED')) THEN RAISE EXCEPTION 'T11-B7 reserved global key'; END IF;
    RETURN OLD;
   END IF;
   IF (TG_OP='UPDATE' AND NEW.account_id<>OLD.account_id) OR EXISTS(SELECT 1 FROM t11b7_identifiers WHERE value=NEW.comparison_value AND status IN ('ACTIVE','RESERVED') AND account_id<>NEW.account_id)
    OR NOT EXISTS(SELECT 1 FROM t11b7_identifiers WHERE value=NEW.comparison_value AND status IN ('ACTIVE','RESERVED') AND account_id=NEW.account_id) THEN
    RAISE EXCEPTION 'T11-B7 claim owner collision' USING ERRCODE='23505'; END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_username_complete() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF EXISTS(SELECT 1 FROM t11b7_identifiers WHERE account_id=NEW.account_id AND kind='USERNAME' AND activated_at IS NOT NULL)
    AND (SELECT count(*) FROM t11b7_identifiers WHERE account_id=NEW.account_id AND kind='USERNAME' AND status='ACTIVE')<>1 THEN
    RAISE EXCEPTION 'T11-B7 exactly one primary username required at commit'; END IF;
   RETURN NULL;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_proof_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE i t11b7_identifiers;
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B7 proof history retained'; END IF;
   SELECT * INTO i FROM t11b7_identifiers WHERE id=NEW.identifier_id;
   IF TG_OP='INSERT' THEN
    IF i.kind<>'EMAIL' OR i.status<>'PREPARED' OR i.version<>NEW.identifier_version OR i.operation_id<>NEW.operation_id OR NEW.status<>'ISSUED' THEN RAISE EXCEPTION 'T11-B7 proof binding/version mismatch'; END IF;
    NEW.issued_at:=statement_timestamp();
   ELSE
    IF (to_jsonb(NEW)-ARRAY['status','verified_at','consumed_at','revoked_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','verified_at','consumed_at','revoked_at']) THEN RAISE EXCEPTION 'T11-B7 proof provenance immutable'; END IF;
    IF OLD.status='ISSUED' AND NEW.status='VERIFIED' AND pg_trigger_depth()=2
     AND EXISTS(SELECT 1 FROM t11b7_attestations a WHERE a.proof_id=OLD.id AND a.receipt_digest=OLD.receipt_digest) THEN
     IF i.status<>'PREPARED' OR i.version<>NEW.identifier_version OR clock_timestamp()>=NEW.expires_at THEN RAISE EXCEPTION 'T11-B7 expired/stale evidence'; END IF;
     NEW.verified_at:=statement_timestamp();NEW.consumed_at:=NULL;NEW.revoked_at:=NULL;
    ELSIF OLD.status='VERIFIED' AND NEW.status='CONSUMED' AND pg_trigger_depth()=2 THEN
     NEW.verified_at:=OLD.verified_at;NEW.consumed_at:=statement_timestamp();NEW.revoked_at:=NULL;
    ELSIF OLD.status IN ('ISSUED','VERIFIED') AND NEW.status='REVOKED' THEN
     NEW.verified_at:=OLD.verified_at;NEW.consumed_at:=NULL;NEW.revoked_at:=statement_timestamp();
    ELSE RAISE EXCEPTION 'T11-B7 invalid proof transition'; END IF;
   END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_attest_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE p t11b7_proofs;
  BEGIN
   PERFORM t11b7_lock();
   IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'T11-B7 attestation append-only'; END IF;
   SELECT * INTO p FROM t11b7_proofs WHERE id=NEW.proof_id FOR UPDATE;
   IF p.status IS DISTINCT FROM 'ISSUED' OR p.receipt_digest IS DISTINCT FROM NEW.receipt_digest OR clock_timestamp()>=p.expires_at THEN RAISE EXCEPTION 'T11-B7 invalid receipt'; END IF;
   NEW.recorded_at:=statement_timestamp();RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_attest_sync() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN UPDATE t11b7_proofs SET status='VERIFIED',verified_at=statement_timestamp() WHERE id=NEW.proof_id;RETURN NEW; END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b7_review_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE i t11b7_identifiers;
  BEGIN
   IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'T11-B7 conflict review append-only'; END IF;
   SELECT * INTO i FROM t11b7_identifiers WHERE id=NEW.identifier_id;
   IF i.status<>'PREPARED' OR NEW.identifier_version<>i.version OR NEW.reviewed_by IS NOT NULL OR NOT t11b7_available(i.id) THEN
    RAISE EXCEPTION 'T11-B7 controlled review of available prepared binding required'; END IF;
   NEW.observed:=t11b7_inventory(i.id);NEW.fingerprint:=md5(NEW.observed::text);NEW.reviewed_at:=statement_timestamp();
   RETURN NEW;
  END $fn$ $ddl$;
  CREATE TRIGGER t11b7_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b7_identifiers FOR EACH ROW EXECUTE FUNCTION t11b7_identifier_guard();
  CREATE TRIGGER t11b7_sync AFTER INSERT OR UPDATE ON t11b7_identifiers FOR EACH ROW EXECUTE FUNCTION t11b7_sync();
  CREATE CONSTRAINT TRIGGER t11b7_primary_complete AFTER INSERT OR UPDATE ON t11b7_identifiers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION t11b7_username_complete();
  CREATE TRIGGER t11b7_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b7_proofs FOR EACH ROW EXECUTE FUNCTION t11b7_proof_guard();
  CREATE TRIGGER t11b7_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b7_attestations FOR EACH ROW EXECUTE FUNCTION t11b7_attest_guard();
  CREATE TRIGGER t11b7_sync AFTER INSERT ON t11b7_attestations FOR EACH ROW EXECUTE FUNCTION t11b7_attest_sync();
  CREATE TRIGGER t11b7_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b7_reviews FOR EACH ROW EXECUTE FUNCTION t11b7_review_guard();
  CREATE TRIGGER t11b7_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b7_claims FOR EACH ROW EXECUTE FUNCTION t11b7_claim_guard();
  CREATE TRIGGER t11b7_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b7_events FOR EACH ROW EXECUTE FUNCTION t11b7_generated();
  CREATE TRIGGER t11b7_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b7_conflicts FOR EACH ROW EXECUTE FUNCTION t11b7_generated();
  FOREACH tab IN ARRAY ARRAY['t11b7_identifiers','t11b7_proofs','t11b7_reviews'] LOOP
   EXECUTE format('CREATE TRIGGER t11b7_lock BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b7_statement_lock()',tab);
   EXECUTE format('CREATE TRIGGER t11b7_history AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION t11b7_history()',tab);
  END LOOP;
  FOREACH tab IN ARRAY ARRAY['t11b7_identifiers','t11b7_proofs','t11b7_attestations','t11b7_reviews','t11b7_conflicts','t11b7_events','t11b7_claims'] LOOP
   EXECUTE format('CREATE TRIGGER t11b7_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b7_immutable()',tab);
   EXECUTE format('CREATE TRIGGER t11b7_charge AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION t11b7_charge()',tab);
  END LOOP;
 END IF;
  SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
  SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
   FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
   WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b7_%' AND a.attnum>0 AND NOT a.attisdropped
  UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b7_%'
  UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b7_%'
  UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b7_%'
  UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b7_%' AND NOT t.tgisinternal
  UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b7_%'
  UNION ALL SELECT 'view:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b7_%' AND c.relkind='v'
  UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b7_%'
 ) definitions;

 IF installed THEN
  SELECT fingerprint INTO expected FROM t11b7_schema_version WHERE version=1 AND revision='t11b7-v1'
   AND predecessor_fingerprint=(SELECT fingerprint FROM t11b6_schema_version WHERE version=1 AND revision='t11b6-v1');
  IF expected IS DISTINCT FROM actual OR (SELECT count(*) FROM t11b7_schema_version)<>1 THEN RAISE EXCEPTION 'T11-B7 divergence; no repair'; END IF;
 ELSE
  INSERT INTO t11b7_schema_version(version,revision,predecessor_fingerprint,fingerprint) SELECT 1,'t11b7-v1',fingerprint,actual FROM t11b6_schema_version WHERE version=1;
 END IF;
END $b7$;

DO $b8$
DECLARE installed boolean; actual text; expected text; tab text;
BEGIN
 PERFORM pg_advisory_xact_lock(110011,1);
 installed:=to_regclass(current_schema()||'.t11b8_schema_version') IS NOT NULL;
 IF NOT installed THEN
  IF EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace AND relname LIKE 't11b8_%')
   OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b8_%') THEN
   RAISE EXCEPTION 'T11-B8 objects without ledger; no repair'; END IF;
  CREATE TABLE t11b8_schema_version(version integer PRIMARY KEY CHECK(version=1),revision text NOT NULL,
   predecessor_fingerprint text NOT NULL,fingerprint text NOT NULL,installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
  -- Typed FKs retain both original academic identities. No synthetic source/origin input.
  CREATE TABLE t11b8_resources(
   id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
   concept_id bigint UNIQUE REFERENCES concept_contents(id),evaluation_id bigint UNIQUE REFERENCES evaluations(id),
   institution_id bigint NOT NULL REFERENCES institutions(id),author_account_id bigint NOT NULL REFERENCES user_accounts(id),
   author_membership_id bigint,creation text NOT NULL CHECK(creation IN ('LEGACY','EXPLICIT_NEW')),
   legacy_group_id bigint UNIQUE REFERENCES t11b3_opportunity_groups(id),max_attempts integer,
   availability text NOT NULL DEFAULT 'AVAILABLE' CHECK(availability IN ('AVAILABLE','WITHDRAWN','ARCHIVED')),
   opportunities_closed boolean NOT NULL DEFAULT false,correction_exposed boolean NOT NULL DEFAULT false,
   version bigint NOT NULL DEFAULT 1 CHECK(version>0),operation_id uuid NOT NULL UNIQUE,
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   CHECK(num_nonnulls(concept_id,evaluation_id)=1),
   CHECK((evaluation_id IS NULL AND max_attempts IS NULL AND legacy_group_id IS NULL AND NOT correction_exposed)
    OR (evaluation_id IS NOT NULL AND max_attempts BETWEEN 1 AND 10
     AND ((creation='LEGACY' AND legacy_group_id IS NOT NULL) OR (creation='EXPLICIT_NEW' AND legacy_group_id IS NULL)))),
   CHECK(NOT correction_exposed OR opportunities_closed),
   UNIQUE(id,institution_id),FOREIGN KEY(author_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id));
  CREATE TABLE t11b8_revisions(
   id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,resource_id bigint NOT NULL,institution_id bigint NOT NULL,
   parent_id bigint,revision_number integer NOT NULL CHECK(revision_number>0),version bigint NOT NULL DEFAULT 1 CHECK(version>0),
   status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED')),
   payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),payload_checksum text NOT NULL CHECK(payload_checksum=md5(payload::text)),
   base_concept_revision_id bigint REFERENCES t11b3_concept_revisions(id),base_evaluation_revision_id bigint REFERENCES t11b3_evaluation_revisions(id),
   operation_id uuid NOT NULL UNIQUE,published_at timestamptz,recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   CHECK((status='DRAFT' AND published_at IS NULL) OR (status='PUBLISHED' AND published_at IS NOT NULL)),
   CHECK(num_nonnulls(base_concept_revision_id,base_evaluation_revision_id)<=1),
   UNIQUE(resource_id,revision_number),UNIQUE(id,resource_id,institution_id),
   FOREIGN KEY(resource_id,institution_id) REFERENCES t11b8_resources(id,institution_id),
   FOREIGN KEY(parent_id,resource_id,institution_id) REFERENCES t11b8_revisions(id,resource_id,institution_id));
  CREATE TABLE t11b8_deliveries(
   id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,resource_id bigint NOT NULL,institution_id bigint NOT NULL,
   revision_id bigint NOT NULL,class_id bigint NOT NULL,starts_at timestamptz NOT NULL,due_at timestamptz,
   status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),version bigint NOT NULL DEFAULT 1,
   operation_id uuid NOT NULL UNIQUE,recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   CHECK(isfinite(starts_at) AND (due_at IS NULL OR (isfinite(due_at) AND starts_at<due_at))),
   UNIQUE(revision_id,class_id),UNIQUE(id,resource_id,institution_id),
   FOREIGN KEY(revision_id,resource_id,institution_id) REFERENCES t11b8_revisions(id,resource_id,institution_id),
   FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id));
  CREATE TABLE t11b8_recipients(
   id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,delivery_id bigint NOT NULL,resource_id bigint NOT NULL,institution_id bigint NOT NULL,
   account_id bigint NOT NULL REFERENCES user_accounts(id),enrollment_id bigint NOT NULL,
   status text NOT NULL DEFAULT 'ADMITTED' CHECK(status IN ('ADMITTED','WITHDRAWN')),version bigint NOT NULL DEFAULT 1,
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   UNIQUE(delivery_id,account_id),UNIQUE(id,resource_id,institution_id,account_id),
   FOREIGN KEY(delivery_id,resource_id,institution_id) REFERENCES t11b8_deliveries(id,resource_id,institution_id),
   FOREIGN KEY(enrollment_id,institution_id) REFERENCES enrollments(id,institution_id));
  -- These NEW-only budgets are a fixture contract, never a second authority for legacy.
  CREATE TABLE t11b8_budgets(
   resource_id bigint NOT NULL REFERENCES t11b8_resources(id),account_id bigint NOT NULL REFERENCES user_accounts(id),
   PRIMARY KEY(resource_id,account_id));
  CREATE TABLE t11b8_fixture_attempts(
   id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,resource_id bigint NOT NULL,institution_id bigint NOT NULL,
   account_id bigint NOT NULL,recipient_id bigint NOT NULL,revision_id bigint NOT NULL,
   attempt_number integer NOT NULL CHECK(attempt_number>0),status text NOT NULL DEFAULT 'IN_PROGRESS' CHECK(status IN ('IN_PROGRESS','FINAL')),
   version bigint NOT NULL DEFAULT 1,configuration jsonb NOT NULL,question_order jsonb NOT NULL,
   started_at timestamptz NOT NULL,deadline timestamptz,answers jsonb NOT NULL DEFAULT '{}'::jsonb,grade numeric,
   grade_closed boolean NOT NULL DEFAULT false,operation_id uuid NOT NULL UNIQUE,
   CHECK(status<>'IN_PROGRESS' OR (NOT grade_closed AND grade IS NULL AND answers='{}'::jsonb)),
   CHECK(grade IS NULL OR grade BETWEEN 0 AND 20),CHECK(NOT grade_closed OR status='FINAL'),
   UNIQUE(resource_id,account_id,attempt_number),
   FOREIGN KEY(resource_id,account_id) REFERENCES t11b8_budgets(resource_id,account_id),
   FOREIGN KEY(recipient_id,resource_id,institution_id,account_id) REFERENCES t11b8_recipients(id,resource_id,institution_id,account_id),
   FOREIGN KEY(revision_id,resource_id,institution_id) REFERENCES t11b8_revisions(id,resource_id,institution_id));
  CREATE UNIQUE INDEX t11b8_one_active ON t11b8_fixture_attempts(resource_id,account_id) WHERE status='IN_PROGRESS';
  CREATE TABLE t11b8_events(
   id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,resource_id bigint NOT NULL REFERENCES t11b8_resources(id),
   entity_table text NOT NULL,entity_id bigint NOT NULL,snapshot jsonb NOT NULL,recorded_at timestamptz NOT NULL DEFAULT statement_timestamp());
  CREATE TABLE t11b8_operations(
   operation_id uuid PRIMARY KEY,resource_id bigint NOT NULL REFERENCES t11b8_resources(id),
   action text NOT NULL,request jsonb NOT NULL,result_id bigint NOT NULL,
   recorded_at timestamptz NOT NULL DEFAULT statement_timestamp());
  CREATE TABLE t11b8_creations(
   operation_id uuid PRIMARY KEY,resource_id bigint NOT NULL UNIQUE REFERENCES t11b8_resources(id),
   kind text NOT NULL CHECK(kind IN ('CONCEPT','EVALUATION')),institution_id bigint NOT NULL REFERENCES institutions(id),
   author_account_id bigint NOT NULL REFERENCES user_accounts(id),author_membership_id bigint NOT NULL,
   payload jsonb NOT NULL,recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
   FOREIGN KEY(author_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id));
  EXECUTE $ddl$ CREATE FUNCTION t11b8_lock() RETURNS void LANGUAGE plpgsql AS $fn$
  BEGIN
   PERFORM t11b5_fixture();
   -- Shared source locks arbitrate against legacy writers; maintained order before own rows.
   LOCK TABLE evaluations,evaluation_assignments,evaluation_questions,evaluation_options,evaluation_attempts,
    evaluation_answers,evaluation_attempt_adjustments,evaluation_attempt_events,student_profiles,teacher_profiles,
    concept_contents,concept_materials,concept_assignments,concept_content_formation_steps,concept_content_key_points,
    concept_content_examples,institutions,institution_memberships,membership_role_grants,academic_classes,enrollments,
    t11b3_opportunity_groups,t11b3_evaluation_resources,t11b3_concept_resources,
    t11b8_resources,t11b8_revisions,t11b8_deliveries,t11b8_recipients,t11b8_budgets,t11b8_fixture_attempts
    IN SHARE ROW EXCLUSIVE MODE;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_statement_lock() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN PERFORM t11b8_lock();RETURN NULL;END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_immutable() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN RAISE EXCEPTION 'T11-B8 append-only evidence';END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_closed(res bigint) RETURNS boolean LANGUAGE plpgsql STABLE AS $fn$
  DECLARE r t11b8_resources%ROWTYPE; src jsonb;
  BEGIN
   SELECT * INTO STRICT r FROM t11b8_resources WHERE id=res;
   IF r.opportunities_closed OR r.correction_exposed OR r.availability<>'AVAILABLE' THEN RETURN true;END IF;
   IF r.evaluation_id IS NULL THEN RETURN false;END IF;
   IF r.creation='LEGACY' THEN
    SELECT t11b3_snapshot('EVALUATION',r.evaluation_id) INTO src;
    RETURN (SELECT opportunities_closed FROM t11b3_opportunity_groups WHERE id=r.legacy_group_id)
     OR t11b3_review_closed(src->'resource',src->'assignments',statement_timestamp() AT TIME ZONE 'America/Lima');
   END IF;
   -- NEW cannot simultaneously acquire legacy live consumers.
   IF EXISTS(SELECT 1 FROM evaluation_attempts WHERE evaluation_id=r.evaluation_id)
    OR EXISTS(SELECT 1 FROM evaluation_assignments WHERE evaluation_id=r.evaluation_id) THEN
    RAISE EXCEPTION 'T11-B8 legacy writer present; adoption disabled';END IF;
   RETURN EXISTS(SELECT 1 FROM t11b8_deliveries WHERE resource_id=res)
    AND NOT EXISTS(SELECT 1 FROM t11b8_deliveries WHERE resource_id=res AND status='OPEN' AND (due_at IS NULL OR due_at>statement_timestamp()));
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_payload(k text,p jsonb,publishing boolean) RETURNS void LANGUAGE plpgsql AS $fn$
  #variable_conflict use_column
  DECLARE x jsonb; q jsonb; n bigint; cfg jsonb;
  BEGIN
   cfg:=p->'resource';
   IF jsonb_typeof(cfg) IS DISTINCT FROM 'object' OR octet_length(p::text)>33554432
    OR coalesce(length(cfg->>'title'),0) NOT BETWEEN 1 AND 150 THEN RAISE EXCEPTION 'T11-B8 invalid bounded payload';END IF;
   IF k='EVALUATION' THEN
    IF jsonb_typeof(p->'questions') IS DISTINCT FROM 'array' OR jsonb_typeof(p->'options') IS DISTINCT FROM 'array'
     OR jsonb_array_length(p->'questions')>1000 OR jsonb_array_length(p->'options')>10000
     OR coalesce((cfg->>'max_attempts')::int,0) NOT BETWEEN 1 AND 10
     OR ((cfg->>'time_limit_minutes') IS NOT NULL AND (cfg->>'time_limit_minutes')::int NOT BETWEEN 1 AND 240)
     OR length(coalesce(cfg->>'description',''))>1000 OR length(coalesce(cfg->>'instructions',''))>2000
     OR length(coalesce(cfg->>'topic',''))>120
     OR cfg->>'question_display_mode' NOT IN ('ALL_AT_ONCE','ONE_BY_ONE') THEN RAISE EXCEPTION 'T11-B8 invalid evaluation configuration';END IF;
    IF publishing AND jsonb_array_length(p->'questions')=0 THEN RAISE EXCEPTION 'T11-B8 publish requires questions';END IF;
    IF EXISTS(SELECT 1 FROM jsonb_array_elements(p->'questions') q GROUP BY q->>'id' HAVING count(*)>1)
     OR EXISTS(SELECT 1 FROM jsonb_array_elements(p->'options') o GROUP BY o->>'id' HAVING count(*)>1) THEN RAISE EXCEPTION 'T11-B8 duplicate academic identity';END IF;
    FOR q IN SELECT value FROM jsonb_array_elements(p->'questions') LOOP
     IF q->>'id' IS NULL OR coalesce(length(q->>'question_text'),0) NOT BETWEEN 1 AND 5000
      OR coalesce((q->>'points')::int,0)<1 OR length(coalesce(q->>'expected_answer',''))>3000
      OR length(coalesce(q->>'explanation',''))>5000 OR q->>'question_type' IS NULL
      OR q->>'question_type' NOT IN ('OPEN_TEXT','MULTIPLE_CHOICE') THEN RAISE EXCEPTION 'T11-B8 invalid question';END IF;
     SELECT count(*) INTO n FROM jsonb_array_elements(p->'options') o WHERE o->>'question_id'=q->>'id';
     IF (q->>'question_type'='OPEN_TEXT' AND n<>0)
      OR (publishing AND q->>'question_type'='MULTIPLE_CHOICE' AND (n<2 OR
       (SELECT count(*) FROM jsonb_array_elements(p->'options') o WHERE o->>'question_id'=q->>'id' AND (o->>'correct')::boolean)<>1)) THEN
      RAISE EXCEPTION 'T11-B8 invalid alternatives';END IF;
    END LOOP;
    IF EXISTS(SELECT 1 FROM jsonb_array_elements(p->'options') o WHERE o->>'id' IS NULL
      OR coalesce(length(o->>'option_text'),0) NOT BETWEEN 1 AND 5000
      OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(p->'questions') q WHERE q->>'id'=o->>'question_id')) THEN RAISE EXCEPTION 'T11-B8 invalid option parent';END IF;
   ELSE
    IF coalesce(length(cfg->>'category'),0) NOT BETWEEN 1 AND 100 OR length(coalesce(cfg->>'summary',''))>500
     OR length(coalesce(cfg->>'explanation',''))>20000 OR length(coalesce(cfg->>'suggested_activity',''))>2000 THEN RAISE EXCEPTION 'T11-B8 invalid concept';END IF;
    FOREACH k IN ARRAY ARRAY['materials','steps','points','examples'] LOOP
     IF jsonb_typeof(p->k) IS DISTINCT FROM 'array' OR jsonb_array_length(p->k)>1000 THEN RAISE EXCEPTION 'T11-B8 bounded concept collections required';END IF;
    END LOOP;
    FOR x IN SELECT value FROM jsonb_array_elements(p->'materials') LOOP
     IF x->>'type'='FILE' THEN
      IF coalesce(x->>'file_data','') !~ '^\\x([0-9a-f]{2})+$' OR (length(x->>'file_data')-2)/2>10485760
       OR (x->>'file_size')::bigint IS DISTINCT FROM (length(x->>'file_data')-2)/2 THEN RAISE EXCEPTION 'T11-B8 material byte length';END IF;
     ELSIF x->>'type'='LINK' THEN
      IF coalesce(x->>'url','') !~* '^https?://' OR length(x->>'url')>2048 THEN RAISE EXCEPTION 'T11-B8 invalid material link';END IF;
     ELSE RAISE EXCEPTION 'T11-B8 invalid material type';END IF;
    END LOOP;
    IF publishing AND length(coalesce(cfg->>'explanation','')||coalesce(cfg->>'summary','')||coalesce(cfg->>'suggested_activity',''))=0
      AND p->'materials'='[]'::jsonb AND p->'steps'='[]'::jsonb AND p->'points'='[]'::jsonb AND p->'examples'='[]'::jsonb THEN
      RAISE EXCEPTION 'T11-B8 empty conceptual content';END IF;
   END IF;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_resource_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE src jsonb; legacy record; author bigint; kind text;
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B8 resource evidence immutable';END IF;
   IF TG_OP='INSERT' THEN
    IF NEW.version<>1 OR NEW.availability<>'AVAILABLE' OR NEW.opportunities_closed OR NEW.correction_exposed THEN RAISE EXCEPTION 'T11-B8 initial resource state';END IF;
    kind:=CASE WHEN NEW.evaluation_id IS NULL THEN 'CONCEPT' ELSE 'EVALUATION' END;
    src:=t11b3_snapshot(kind,coalesce(NEW.evaluation_id,NEW.concept_id));author:=(src->'author'->>'user_id')::bigint;
    IF author IS DISTINCT FROM NEW.author_account_id OR (NEW.author_membership_id IS NOT NULL AND NOT EXISTS(
     SELECT 1 FROM institution_memberships WHERE id=NEW.author_membership_id AND account_id=author AND institution_id=NEW.institution_id)) THEN RAISE EXCEPTION 'T11-B8 historical author mismatch';END IF;
    IF NEW.creation='LEGACY' THEN
     IF kind='EVALUATION' THEN
      SELECT r.institution_id,r.author_account_id,g.id,g.max_attempts INTO STRICT legacy FROM t11b3_evaluation_resources r
       JOIN t11b3_opportunity_groups g ON g.evaluation_id=r.evaluation_id WHERE r.evaluation_id=NEW.evaluation_id;
      IF NEW.legacy_group_id IS DISTINCT FROM legacy.id OR NEW.max_attempts IS DISTINCT FROM legacy.max_attempts THEN RAISE EXCEPTION 'T11-B8 legacy root immutable';END IF;
     ELSE SELECT institution_id,author_account_id INTO STRICT legacy FROM t11b3_concept_resources WHERE concept_id=NEW.concept_id;END IF;
     IF NEW.institution_id<>legacy.institution_id OR author<>legacy.author_account_id THEN RAISE EXCEPTION 'T11-B8 legacy provenance mismatch';END IF;
    ELSE
     IF pg_trigger_depth()<>2 THEN RAISE EXCEPTION 'T11-B8 explicit creation command required; existing anchors cannot register as new';END IF;
     -- Academic creation must insert the original typed anchor in THIS transaction.
     -- An existing source, even under another origin string, cannot become NEW.
     IF src->'resource'->>'status'<>'DRAFT' OR src->'assignments'<>'[]'::jsonb
      OR (kind='EVALUATION' AND (src->'attempts'<>'[]'::jsonb OR EXISTS(SELECT 1 FROM t11b3_opportunity_groups WHERE evaluation_id=NEW.evaluation_id)))
      OR (kind='CONCEPT' AND EXISTS(SELECT 1 FROM t11b3_concept_resources WHERE concept_id=NEW.concept_id)) THEN RAISE EXCEPTION 'T11-B8 explicit creation only';END IF;
     IF kind='EVALUATION' THEN
      IF NOT EXISTS(SELECT 1 FROM evaluations WHERE id=NEW.evaluation_id AND xmin=pg_current_xact_id()::text::xid) THEN RAISE EXCEPTION 'T11-B8 fresh academic anchor required';END IF;
      IF NEW.max_attempts IS DISTINCT FROM (src->'resource'->>'max_attempts')::int THEN RAISE EXCEPTION 'T11-B8 maximum mismatch';END IF;
     ELSE
      IF NOT EXISTS(SELECT 1 FROM concept_contents WHERE id=NEW.concept_id AND xmin=pg_current_xact_id()::text::xid) THEN RAISE EXCEPTION 'T11-B8 fresh academic anchor required';END IF;
     END IF;
    END IF;
    NEW.recorded_at:=statement_timestamp();
   ELSE
    IF (to_jsonb(NEW)-ARRAY['availability','opportunities_closed','correction_exposed','version']) IS DISTINCT FROM
       (to_jsonb(OLD)-ARRAY['availability','opportunities_closed','correction_exposed','version']) OR NEW.version<>OLD.version+1
      OR OLD.availability='ARCHIVED' OR (OLD.opportunities_closed AND NOT NEW.opportunities_closed)
      OR (OLD.correction_exposed AND NOT NEW.correction_exposed) THEN RAISE EXCEPTION 'T11-B8 immutable root or stale version';END IF;
    IF NEW.availability<>'AVAILABLE' AND NEW.evaluation_id IS NOT NULL THEN NEW.opportunities_closed:=true;END IF;
    IF NOT OLD.opportunities_closed AND NEW.opportunities_closed AND NEW.availability='AVAILABLE' AND NOT t11b8_closed(OLD.id) THEN RAISE EXCEPTION 'T11-B8 all delivery windows must close';END IF;
    IF NOT OLD.correction_exposed AND NEW.correction_exposed AND (NEW.creation='LEGACY' OR NOT NEW.opportunities_closed
      OR EXISTS(SELECT 1 FROM t11b8_fixture_attempts WHERE resource_id=NEW.id AND NOT grade_closed)) THEN RAISE EXCEPTION 'T11-B8 correction cannot be exposed';END IF;
   END IF;RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
   DECLARE r t11b8_resources%ROWTYPE; base jsonb; n integer; meta text;
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B8 revisions are evidence';END IF;
   SELECT * INTO STRICT r FROM t11b8_resources WHERE id=NEW.resource_id;
   IF r.availability='ARCHIVED' THEN RAISE EXCEPTION 'T11-B8 archived resource';END IF;
   IF TG_OP='INSERT' THEN
    IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.published_at IS NOT NULL THEN RAISE EXCEPTION 'T11-B8 create draft first';END IF;
    SELECT coalesce(max(revision_number),0)+1 INTO n FROM t11b8_revisions WHERE resource_id=r.id;
    IF NEW.revision_number<>n THEN RAISE EXCEPTION 'T11-B8 revision numbering';END IF;
    IF NEW.parent_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM t11b8_revisions WHERE id=NEW.parent_id AND resource_id=r.id AND status='PUBLISHED') THEN RAISE EXCEPTION 'T11-B8 published parent required';END IF;
    IF NEW.base_evaluation_revision_id IS NOT NULL THEN
     SELECT payload INTO STRICT base FROM t11b3_evaluation_revisions WHERE id=NEW.base_evaluation_revision_id AND evaluation_id=r.evaluation_id AND institution_id=r.institution_id;
    ELSIF NEW.base_concept_revision_id IS NOT NULL THEN
     SELECT payload INTO STRICT base FROM t11b3_concept_revisions WHERE id=NEW.base_concept_revision_id AND concept_id=r.concept_id AND institution_id=r.institution_id;
    END IF;
    IF NEW.parent_id IS NOT NULL AND base IS NOT NULL THEN RAISE EXCEPTION 'T11-B8 ambiguous base';END IF;
    IF NEW.parent_id IS NOT NULL THEN SELECT payload INTO base FROM t11b8_revisions WHERE id=NEW.parent_id;END IF;
    IF base IS NOT NULL AND NEW.payload IS DISTINCT FROM base THEN RAISE EXCEPTION 'T11-B8 initial copy preserves base';END IF;
    NEW.recorded_at:=statement_timestamp();
   ELSE
    IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'T11-B8 published revision immutable';END IF;
    IF NEW.payload->'author' IS DISTINCT FROM OLD.payload->'author' THEN RAISE EXCEPTION 'T11-B8 payload author immutable';END IF;
    FOREACH meta IN ARRAY ARRAY['id','created_by_teacher_id','created_at','updated_at','status','active'] LOOP
     IF NEW.payload->'resource'->meta IS DISTINCT FROM OLD.payload->'resource'->meta THEN RAISE EXCEPTION 'T11-B8 payload provenance immutable';END IF;
    END LOOP;
    IF (to_jsonb(NEW)-ARRAY['payload','payload_checksum','status','version','published_at']) IS DISTINCT FROM
       (to_jsonb(OLD)-ARRAY['payload','payload_checksum','status','version','published_at']) OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'T11-B8 stale draft version or identity';END IF;
    IF NEW.status='PUBLISHED' THEN
     IF NEW.payload IS DISTINCT FROM OLD.payload THEN RAISE EXCEPTION 'T11-B8 publication cannot edit content';END IF;
     NEW.published_at:=statement_timestamp();
    ELSIF NEW.published_at IS NOT NULL THEN RAISE EXCEPTION 'T11-B8 draft publication timestamp';END IF;
   END IF;
   PERFORM t11b8_payload(CASE WHEN r.evaluation_id IS NULL THEN 'CONCEPT' ELSE 'EVALUATION' END,NEW.payload,NEW.status='PUBLISHED');
   IF r.evaluation_id IS NOT NULL AND (NEW.payload->'resource'->>'max_attempts')::int IS DISTINCT FROM r.max_attempts THEN RAISE EXCEPTION 'T11-B8 revision cannot reset maximum';END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_delivery_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B8 delivery evidence immutable';END IF;
   IF TG_OP='INSERT' THEN
    IF NEW.status<>'OPEN' OR NEW.version<>1 OR NOT EXISTS(SELECT 1 FROM t11b8_revisions WHERE id=NEW.revision_id AND status='PUBLISHED') THEN RAISE EXCEPTION 'T11-B8 published revision required';END IF;
    IF t11b8_closed(NEW.resource_id) THEN RAISE EXCEPTION 'T11-B8 closed root';END IF;
    NEW.recorded_at:=statement_timestamp();
   ELSIF OLD.status='CLOSED' OR NEW.status<>'CLOSED' OR NEW.version<>OLD.version+1 OR
     (to_jsonb(NEW)-ARRAY['status','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version']) THEN RAISE EXCEPTION 'T11-B8 delivery cannot reopen or retarget';END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_recipient_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE d t11b8_deliveries%ROWTYPE;e enrollments%ROWTYPE;m institution_memberships%ROWTYPE;
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B8 recipient evidence immutable';END IF;
   SELECT * INTO STRICT d FROM t11b8_deliveries WHERE id=NEW.delivery_id;
   IF TG_OP='INSERT' THEN
    IF NEW.status<>'ADMITTED' OR NEW.version<>1 THEN RAISE EXCEPTION 'T11-B8 recipient initial state';END IF;
    NEW.recorded_at:=statement_timestamp();
   ELSIF NEW.version<>OLD.version+1 OR (NEW.status=OLD.status AND NEW.enrollment_id=OLD.enrollment_id)
     OR (to_jsonb(NEW)-ARRAY['enrollment_id','status','version']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['enrollment_id','status','version']) THEN RAISE EXCEPTION 'T11-B8 stale recipient or identity';END IF;
   IF NEW.status='ADMITTED' THEN
    IF d.status<>'OPEN' OR (d.due_at IS NOT NULL AND d.due_at<=statement_timestamp()) OR t11b8_closed(d.resource_id) THEN RAISE EXCEPTION 'T11-B8 closed admission';END IF;
    SELECT * INTO STRICT e FROM enrollments WHERE id=NEW.enrollment_id;
    SELECT * INTO STRICT m FROM institution_memberships WHERE id=e.membership_id;
    IF e.class_id<>d.class_id OR e.institution_id<>d.institution_id OR m.account_id<>NEW.account_id
      OR e.status<>'ACTIVE' OR m.status<>'ACTIVE' OR e.activated_at>statement_timestamp()
      OR e.valid_until<=statement_timestamp() OR e.ended_at IS NOT NULL OR m.valid_from>statement_timestamp()
      OR (m.valid_until IS NOT NULL AND m.valid_until<=statement_timestamp())
      OR NOT EXISTS(SELECT 1 FROM membership_role_grants g WHERE g.membership_id=m.id AND g.role='ESTUDIANTE'
        AND g.status='ACTIVE' AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp())) THEN RAISE EXCEPTION 'T11-B8 wrong active recipient enrollment';END IF;
   ELSIF NEW.enrollment_id<>OLD.enrollment_id THEN RAISE EXCEPTION 'T11-B8 withdrawal preserves episode';END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_budget_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF TG_OP<>'INSERT' OR NOT EXISTS(SELECT 1 FROM t11b8_resources WHERE id=NEW.resource_id AND creation='EXPLICIT_NEW' AND evaluation_id IS NOT NULL) THEN
    RAISE EXCEPTION 'T11-B8 new evaluation budget identity immutable; legacy authority retained';END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  #variable_conflict use_column
  DECLARE r t11b8_resources%ROWTYPE;rec t11b8_recipients%ROWTYPE;d t11b8_deliveries%ROWTYPE;rev t11b8_revisions%ROWTYPE;used integer;
  BEGIN
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B8 consumption cannot be returned';END IF;
   SELECT * INTO STRICT r FROM t11b8_resources WHERE id=NEW.resource_id;
   IF r.creation<>'EXPLICIT_NEW' THEN RAISE EXCEPTION 'T11-B8 legacy writers remain authority';END IF;
   IF TG_OP='INSERT' THEN
    SELECT * INTO STRICT rec FROM t11b8_recipients WHERE id=NEW.recipient_id;
    SELECT * INTO STRICT d FROM t11b8_deliveries WHERE id=rec.delivery_id;
    SELECT * INTO STRICT rev FROM t11b8_revisions WHERE id=d.revision_id;
    SELECT count(*) INTO used FROM t11b8_fixture_attempts WHERE resource_id=r.id AND account_id=NEW.account_id;
    IF t11b8_closed(r.id) OR used>=r.max_attempts OR NEW.attempt_number<>used+1 OR NEW.status<>'IN_PROGRESS' OR NEW.version<>1
      OR d.status<>'OPEN' OR d.starts_at>statement_timestamp() OR (d.due_at IS NOT NULL AND d.due_at<=statement_timestamp())
      OR rec.status<>'ADMITTED' OR NEW.revision_id<>rev.id OR rev.status<>'PUBLISHED'
      OR NEW.configuration IS DISTINCT FROM rev.payload->'resource' THEN RAISE EXCEPTION 'T11-B8 opportunity not admissible';END IF;
    -- Recheck the current episode, not just the earlier recipient observation.
    IF NOT EXISTS(SELECT 1 FROM enrollments e JOIN institution_memberships m ON m.id=e.membership_id
      WHERE e.id=rec.enrollment_id AND e.status='ACTIVE' AND m.status='ACTIVE' AND e.activated_at<=statement_timestamp()
       AND e.valid_until>statement_timestamp() AND e.ended_at IS NULL AND m.valid_from<=statement_timestamp()
       AND (m.valid_until IS NULL OR m.valid_until>statement_timestamp())
       AND EXISTS(SELECT 1 FROM membership_role_grants g WHERE g.membership_id=m.id AND g.role='ESTUDIANTE'
        AND g.status='ACTIVE' AND g.valid_from<=statement_timestamp() AND (g.valid_until IS NULL OR g.valid_until>statement_timestamp()))) THEN RAISE EXCEPTION 'T11-B8 stale enrollment';END IF;
    NEW.started_at:=statement_timestamp();
    NEW.deadline:=least(d.due_at,CASE WHEN NEW.configuration->>'time_limit_minutes' IS NOT NULL THEN NEW.started_at+make_interval(mins=>(NEW.configuration->>'time_limit_minutes')::int) END);
    NEW.question_order:=(SELECT coalesce(jsonb_agg(q->'id' ORDER BY ord),'[]'::jsonb) FROM jsonb_array_elements(rev.payload->'questions') WITH ORDINALITY q(q,ord));
   ELSE
    IF OLD.status='FINAL' OR NEW.status<>'FINAL' OR NEW.version<>OLD.version+1 OR
      (to_jsonb(NEW)-ARRAY['status','version','answers','grade','grade_closed']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','answers','grade','grade_closed']) THEN
      RAISE EXCEPTION 'T11-B8 final attempt or stale version immutable';END IF;
    IF octet_length(NEW.answers::text)>4194304 THEN RAISE EXCEPTION 'T11-B8 bounded fixture answers';END IF;
    IF OLD.deadline IS NOT NULL AND OLD.deadline<=statement_timestamp() AND NEW.answers<>'{}'::jsonb THEN RAISE EXCEPTION 'T11-B8 expired fixture cannot submit new answers';END IF;
   END IF;RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_generated() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN IF TG_OP<>'INSERT' OR pg_trigger_depth()<2 THEN RAISE EXCEPTION 'T11-B8 append-only generated history';END IF;RETURN NEW;END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_creation_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE source bigint;teacher bigint;res bigint;
  BEGIN
   IF TG_OP<>'INSERT' OR NEW.resource_id IS NOT NULL THEN RAISE EXCEPTION 'T11-B8 explicit creation is append-only and generates a new anchor';END IF;
   PERFORM t11b8_payload(NEW.kind,NEW.payload,false);
   SELECT id INTO STRICT teacher FROM teacher_profiles WHERE user_id=NEW.author_account_id;
   IF (NEW.payload->'author'->>'user_id' IS NOT NULL AND (NEW.payload->'author'->>'user_id')::bigint<>NEW.author_account_id)
    OR (NEW.payload->'resource'->>'created_by_teacher_id' IS NOT NULL AND (NEW.payload->'resource'->>'created_by_teacher_id')::bigint<>teacher) THEN RAISE EXCEPTION 'T11-B8 payload author mismatch';END IF;
   IF NEW.kind='EVALUATION' THEN
    INSERT INTO evaluations(title,created_by_teacher_id,status,active,max_attempts,time_limit_minutes,
      allow_chemical_calculator,allow_periodic_table,track_tab_exit,question_display_mode,randomize_questions,created_at,updated_at)
     VALUES(NEW.payload->'resource'->>'title',teacher,'DRAFT',true,(NEW.payload->'resource'->>'max_attempts')::int,
      NULL,false,false,false,'ALL_AT_ONCE',false,statement_timestamp() AT TIME ZONE 'America/Lima',statement_timestamp() AT TIME ZONE 'America/Lima') RETURNING id INTO source;
   ELSIF NEW.kind='CONCEPT' THEN
    INSERT INTO concept_contents(title,category,created_by_teacher_id,status,active,created_at,updated_at)
     VALUES(NEW.payload->'resource'->>'title',NEW.payload->'resource'->>'category',teacher,'DRAFT',true,
      statement_timestamp() AT TIME ZONE 'America/Lima',statement_timestamp() AT TIME ZONE 'America/Lima') RETURNING id INTO source;
   ELSE RAISE EXCEPTION 'T11-B8 typed creation required';END IF;
   INSERT INTO t11b8_resources(concept_id,evaluation_id,institution_id,author_account_id,author_membership_id,creation,max_attempts,operation_id)
    VALUES(CASE WHEN NEW.kind='CONCEPT' THEN source END,CASE WHEN NEW.kind='EVALUATION' THEN source END,
     NEW.institution_id,NEW.author_account_id,NEW.author_membership_id,'EXPLICIT_NEW',
     CASE WHEN NEW.kind='EVALUATION' THEN (NEW.payload->'resource'->>'max_attempts')::int END,NEW.operation_id) RETURNING id INTO res;
   INSERT INTO t11b8_revisions(resource_id,institution_id,revision_number,payload,payload_checksum,operation_id)
    VALUES(res,NEW.institution_id,1,NEW.payload,md5(NEW.payload::text),gen_random_uuid());
   NEW.resource_id:=res;NEW.recorded_at:=statement_timestamp();RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_history() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE res bigint;
  BEGIN
   res:=CASE WHEN TG_TABLE_NAME='t11b8_resources' THEN (to_jsonb(NEW)->>'id')::bigint ELSE (to_jsonb(NEW)->>'resource_id')::bigint END;
   INSERT INTO t11b8_events(resource_id,entity_table,entity_id,snapshot) VALUES(res,TG_TABLE_NAME,coalesce((to_jsonb(NEW)->>'id')::bigint,res),to_jsonb(NEW));
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b8_charge() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE owner bigint;res bigint;oldbytes bigint:=0;
  BEGIN
   res:=CASE WHEN TG_TABLE_NAME='t11b8_resources' THEN (to_jsonb(NEW)->>'id')::bigint ELSE (to_jsonb(NEW)->>'resource_id')::bigint END;
   SELECT author_account_id INTO STRICT owner FROM t11b8_resources WHERE id=res;
   IF TG_OP='UPDATE' THEN oldbytes:=octet_length(to_jsonb(OLD)::text);END IF;
   PERFORM storage_delta('ACCOUNT',owner,'REVISION_COUNT',CASE WHEN TG_OP='INSERT' THEN 1 ELSE 0 END);
   PERFORM storage_delta('ACCOUNT',owner,'REVISION_BYTES',octet_length(to_jsonb(NEW)::text)-oldbytes);
   RETURN NEW;
  END $fn$ $ddl$;
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_resources FOR EACH ROW EXECUTE FUNCTION t11b8_resource_guard();
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_revisions FOR EACH ROW EXECUTE FUNCTION t11b8_revision_guard();
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_deliveries FOR EACH ROW EXECUTE FUNCTION t11b8_delivery_guard();
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_recipients FOR EACH ROW EXECUTE FUNCTION t11b8_recipient_guard();
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_budgets FOR EACH ROW EXECUTE FUNCTION t11b8_budget_guard();
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_fixture_attempts FOR EACH ROW EXECUTE FUNCTION t11b8_attempt_guard();
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_events FOR EACH ROW EXECUTE FUNCTION t11b8_generated();
  CREATE TRIGGER t11b8_guard BEFORE UPDATE OR DELETE ON t11b8_operations FOR EACH ROW EXECUTE FUNCTION t11b8_immutable();
  CREATE TRIGGER t11b8_lock BEFORE INSERT ON t11b8_operations FOR EACH STATEMENT EXECUTE FUNCTION t11b8_statement_lock();
  CREATE TRIGGER t11b8_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b8_creations FOR EACH ROW EXECUTE FUNCTION t11b8_creation_guard();
  FOREACH tab IN ARRAY ARRAY['t11b8_resources','t11b8_revisions','t11b8_deliveries','t11b8_recipients','t11b8_budgets','t11b8_fixture_attempts','t11b8_creations'] LOOP
   EXECUTE format('CREATE TRIGGER t11b8_lock BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b8_statement_lock()',tab);
   EXECUTE format('CREATE TRIGGER t11b8_history AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION t11b8_history()',tab);
  END LOOP;
  FOREACH tab IN ARRAY ARRAY['t11b8_resources','t11b8_revisions','t11b8_deliveries','t11b8_recipients','t11b8_budgets','t11b8_fixture_attempts','t11b8_events','t11b8_operations','t11b8_creations'] LOOP
   EXECUTE format('CREATE TRIGGER t11b8_charge AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION t11b8_charge()',tab);
   EXECUTE format('CREATE TRIGGER t11b8_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b8_immutable()',tab);
  END LOOP;
 END IF;
   SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
  SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
   FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
   WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b8_%' AND a.attnum>0 AND NOT a.attisdropped
  UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b8_%'
  UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b8_%'
  UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b8_%'
  UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b8_%' AND NOT t.tgisinternal
  UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b8_%'
  UNION ALL SELECT 'view:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b8_%' AND c.relkind='v'
  UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b8_%'
 ) definitions;


 IF installed THEN
  SELECT fingerprint INTO expected FROM t11b8_schema_version WHERE version=1 AND revision='t11b8-v1'
   AND predecessor_fingerprint=(SELECT fingerprint FROM t11b7_schema_version WHERE version=1 AND revision='t11b7-v1');
  IF expected IS DISTINCT FROM actual OR (SELECT count(*) FROM t11b8_schema_version)<>1 THEN RAISE EXCEPTION 'T11-B8 divergence; no repair';END IF;
 ELSE INSERT INTO t11b8_schema_version SELECT 1,'t11b8-v1',fingerprint,actual,statement_timestamp() FROM t11b7_schema_version WHERE version=1;END IF;
END $b8$;

-- Additive P04 preparation; no predecessor/source trigger is added or replaced.
DO $b9$
DECLARE installed boolean; actual text; expected text; tab text; keys text[];
BEGIN
 PERFORM pg_advisory_xact_lock(110011,1);
 installed:=to_regclass(current_schema()||'.t11b9_schema_version') IS NOT NULL;
 IF NOT installed THEN
  IF EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace AND relname LIKE 't11b9_%')
   OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b9_%') THEN
   RAISE EXCEPTION 'T11-B9 unversioned objects; no repair'; END IF;
  CREATE TABLE t11b9_schema_version(version integer PRIMARY KEY CHECK(version=1),revision text NOT NULL,
   predecessor_fingerprint text NOT NULL,fingerprint text NOT NULL,installed_at timestamptz NOT NULL);
  CREATE TABLE t11b9_contract(source_table text PRIMARY KEY,pk_columns text[] NOT NULL,
   category text NOT NULL CHECK(category IN ('ORIGINAL','MATERIAL','CAPTURE','REVISION','AUXILIARY','GLOBAL_IDENTITY')),
   version text NOT NULL CHECK(version='ROW_JSON_UTF8_V1'));
  FOREACH tab IN ARRAY ARRAY['concept_assignments','concept_content_examples','concept_content_formation_steps','concept_content_key_points','concept_contents','concept_materials','evaluation_answers','evaluation_assignments','evaluation_attempt_adjustments','evaluation_attempt_events','evaluation_attempts','evaluation_options','evaluation_questions','evaluations','system_logs','t11b3_account_attempt_budgets','t11b3_attempt_consumptions','t11b3_attempt_contexts','t11b3_concept_deliveries','t11b3_concept_recipients','t11b3_concept_resources','t11b3_concept_revisions','t11b3_evaluation_deliveries','t11b3_evaluation_recipients','t11b3_evaluation_resources','t11b3_evaluation_revisions','t11b3_opportunity_groups','t11b3_reconciliation_events','t11b3_reconciliations','t11b4_audit_contexts','t11b4_board_contexts','t11b4_participant_contexts','t11b4_reconciliation_events','t11b4_reconciliations','t11b4_usage_contexts','t11b5_academic_codes','t11b5_admin_proofs','t11b5_identifier_conflicts','t11b5_identity_events','t11b5_login_bindings','t11b5_login_identifiers','t11b5_platform_role_grants','t11b6_co_assignments','t11b6_co_events','t11b6_grant_events','t11b6_resource_grants','t11b6_teaching_slots','t11b7_attestations','t11b7_claims','t11b7_conflicts','t11b7_events','t11b7_identifiers','t11b7_proofs','t11b7_reviews','t11b8_budgets','t11b8_creations','t11b8_deliveries','t11b8_events','t11b8_fixture_attempts','t11b8_operations','t11b8_recipients','t11b8_resources','t11b8_revisions','usage_events','whiteboard_object_ownership','whiteboard_participants','whiteboard_sessions'] LOOP
   SELECT array_agg(a.attname::text ORDER BY k.ord) INTO keys FROM pg_constraint c
    CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(num,ord)
    JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num
    WHERE c.conrelid=to_regclass(current_schema()||'.'||tab) AND c.contype='p';
   IF tab='t11b6_teaching_slots' THEN keys:=ARRAY['responsible_id','co_id']; END IF;
   IF tab='concept_content_formation_steps' THEN keys:=ARRAY['concept_content_id','step_order']; END IF;
   IF tab='concept_content_key_points' THEN keys:=ARRAY['concept_content_id','key_point_order']; END IF;
   IF tab='concept_content_examples' THEN keys:=ARRAY['concept_content_id','example_order']; END IF;
   IF keys IS NULL THEN RAISE EXCEPTION 'T11-B9 typed source without stable key: %',tab; END IF;
   INSERT INTO t11b9_contract VALUES(tab,keys,CASE
    WHEN (tab LIKE 't11b5_%' AND tab<>'t11b5_academic_codes') OR tab LIKE 't11b7_%' THEN 'GLOBAL_IDENTITY'
    WHEN tab='concept_materials' THEN 'MATERIAL'
    WHEN tab='whiteboard_sessions' THEN 'CAPTURE'
    WHEN tab LIKE '%revisions' THEN 'REVISION'
    WHEN tab LIKE 't11b%' OR tab IN ('usage_events','system_logs','whiteboard_object_ownership','evaluation_attempt_events','evaluation_attempt_adjustments') THEN 'AUXILIARY'
    ELSE 'ORIGINAL' END,'ROW_JSON_UTF8_V1');
  END LOOP;
  CREATE TABLE t11b9_limits(institution_id bigint NOT NULL REFERENCES institutions(id),category text NOT NULL,
   maximum_rows bigint NOT NULL CHECK(maximum_rows>0),maximum_bytes bigint NOT NULL CHECK(maximum_bytes>0),
   PRIMARY KEY(institution_id,category),CHECK(category IN ('ORIGINAL','MATERIAL','CAPTURE','REVISION','AUXILIARY','GLOBAL_IDENTITY')));
  CREATE TABLE t11b9_counters(scope text NOT NULL CHECK(scope IN ('INSTITUTION','GLOBAL','UNRESOLVED','CONFLICT')),
   owner_id bigint NOT NULL CHECK((scope='INSTITUTION' AND owner_id>0) OR (scope<>'INSTITUTION' AND owner_id=0)),
   category text NOT NULL,rows_used bigint NOT NULL CHECK(rows_used>=0),bytes_used bigint NOT NULL CHECK(bytes_used>=0),
   PRIMARY KEY(scope,owner_id,category));
  CREATE TABLE t11b9_commands(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,operation_id uuid NOT NULL UNIQUE,
   kind text NOT NULL CHECK(kind IN ('BASELINE','SYNC','VERIFY','LIMIT')),request jsonb NOT NULL,
   expected_checksum text,result jsonb,recorded_at timestamptz NOT NULL DEFAULT statement_timestamp());
  CREATE UNIQUE INDEX t11b9_one_baseline ON t11b9_commands(kind) WHERE kind='BASELINE';
  CREATE TABLE t11b9_items(source_table text NOT NULL REFERENCES t11b9_contract(source_table),source_pk jsonb NOT NULL,
   scope text NOT NULL,owner_id bigint NOT NULL,category text NOT NULL,rows_used bigint NOT NULL CHECK(rows_used BETWEEN 0 AND 1),
   bytes_used bigint NOT NULL CHECK(bytes_used>=0),source_checksum text,provenance jsonb NOT NULL,
   command_id bigint NOT NULL REFERENCES t11b9_commands(id) DEFERRABLE INITIALLY DEFERRED,
   PRIMARY KEY(source_table,source_pk),FOREIGN KEY(scope,owner_id,category) REFERENCES t11b9_counters(scope,owner_id,category) DEFERRABLE INITIALLY DEFERRED);
  CREATE TABLE t11b9_deltas(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,command_id bigint NOT NULL REFERENCES t11b9_commands(id) DEFERRABLE INITIALLY DEFERRED,
   source_table text NOT NULL,source_pk jsonb NOT NULL,before_measurement jsonb,after_measurement jsonb NOT NULL,
   rows_delta bigint NOT NULL,bytes_delta bigint NOT NULL,UNIQUE(command_id,source_table,source_pk));
  CREATE TABLE t11b9_control_usage(id integer PRIMARY KEY CHECK(id=1),rows_used bigint NOT NULL CHECK(rows_used BETWEEN 0 AND 1000000),
   bytes_used bigint NOT NULL CHECK(bytes_used BETWEEN 0 AND 268435456));
  INSERT INTO t11b9_control_usage VALUES(1,0,0);

  EXECUTE $ddl$ CREATE FUNCTION t11b9_resource(kind text,pk bigint) RETURNS jsonb LANGUAGE plpgsql STABLE AS $fn$
  DECLARE ids bigint[]; bad boolean;
  BEGIN
   IF kind='CONCEPT' THEN SELECT array_agg(DISTINCT institution_id) INTO ids FROM
    (SELECT institution_id FROM t11b3_concept_resources WHERE concept_id=pk UNION ALL SELECT institution_id FROM t11b8_resources WHERE concept_id=pk) a;
   ELSIF kind='EVALUATION' THEN SELECT array_agg(DISTINCT institution_id) INTO ids FROM
    (SELECT institution_id FROM t11b3_evaluation_resources WHERE evaluation_id=pk UNION ALL SELECT institution_id FROM t11b8_resources WHERE evaluation_id=pk) a;
   ELSE SELECT array_agg(institution_id) INTO ids FROM t11b4_board_contexts WHERE board_id=pk; END IF;
   IF cardinality(ids)>1 THEN RETURN jsonb_build_object('scope','CONFLICT','owner',0,'relation',kind,'pk',pk); END IF;
   IF cardinality(ids)=1 THEN RETURN jsonb_build_object('scope','INSTITUTION','owner',ids[1],'relation',kind,'pk',pk); END IF;
   SELECT EXISTS(SELECT 1 FROM t11b3_reconciliations WHERE source_kind=kind AND source_pk=pk AND status='CONFLICT'
    UNION ALL SELECT 1 FROM t11b4_reconciliations WHERE source_kind=kind AND source_pk=pk AND status='CONFLICT') INTO bad;
   RETURN jsonb_build_object('scope',CASE WHEN bad THEN 'CONFLICT' ELSE 'UNRESOLVED' END,'owner',0,'relation',kind,'pk',pk);
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_provenance(tab text,r jsonb) RETURNS jsonb LANGUAGE plpgsql STABLE AS $fn$
  DECLARE p jsonb; k text; n bigint;
  BEGIN
   -- Global identities are never charged to any current account membership.
   IF tab LIKE 't11b7_%' OR (tab LIKE 't11b5_%' AND tab<>'t11b5_academic_codes') THEN
    RETURN '{"scope":"GLOBAL","owner":0,"relation":"GLOBAL_IDENTITY"}'::jsonb; END IF;
   IF r->>'institution_id' IS NOT NULL THEN
    RETURN jsonb_build_object('scope','INSTITUTION','owner',(r->>'institution_id')::bigint,'relation',tab); END IF;
   IF tab LIKE 't11b8_%' THEN
    SELECT to_jsonb(x) INTO p FROM t11b8_resources x WHERE id=(r->>'resource_id')::bigint;
    RETURN t11b9_provenance('t11b8_resources',p); END IF;
   IF tab LIKE 't11b3_%' OR tab LIKE 't11b4_%' THEN
    IF tab IN ('t11b3_reconciliations','t11b4_reconciliations') THEN p:=r;
    ELSIF tab LIKE 't11b3_%' THEN SELECT to_jsonb(x) INTO p FROM t11b3_reconciliations x WHERE id=(r->>'reconciliation_id')::bigint;
    ELSE SELECT to_jsonb(x) INTO p FROM t11b4_reconciliations x WHERE id=(r->>'reconciliation_id')::bigint; END IF;
    IF p->>'status'='CONFLICT' THEN RETURN '{"scope":"CONFLICT","owner":0,"relation":"RECONCILIATION"}'::jsonb; END IF;
    IF p->>'source_kind' IN ('CONCEPT','EVALUATION','BOARD') THEN RETURN t11b9_resource(p->>'source_kind',(p->>'source_pk')::bigint); END IF;
    IF p->>'source_kind' IN ('USAGE','AUDIT') THEN
     IF p->>'source_kind'='USAGE' THEN SELECT to_jsonb(x) INTO p FROM t11b4_usage_contexts x WHERE event_id=(p->>'source_pk')::bigint;
     ELSE SELECT to_jsonb(x) INTO p FROM t11b4_audit_contexts x WHERE event_id=(p->>'source_pk')::bigint; END IF;
     IF p IS NOT NULL THEN RETURN jsonb_build_object('scope',p->>'scope','owner',coalesce((p->>'institution_id')::bigint,0),'relation','B4_EVENT_CONTEXT'); END IF;
    END IF;
   END IF;
   IF tab='concept_contents' THEN RETURN t11b9_resource('CONCEPT',(r->>'id')::bigint); END IF;
   IF r->>'concept_content_id' IS NOT NULL THEN RETURN t11b9_resource('CONCEPT',(r->>'concept_content_id')::bigint); END IF;
   IF tab='evaluations' THEN RETURN t11b9_resource('EVALUATION',(r->>'id')::bigint); END IF;
   IF r->>'evaluation_id' IS NOT NULL THEN RETURN t11b9_resource('EVALUATION',(r->>'evaluation_id')::bigint); END IF;
   IF tab='evaluation_options' THEN SELECT evaluation_id INTO n FROM evaluation_questions WHERE id=(r->>'question_id')::bigint;
    RETURN t11b9_resource('EVALUATION',n); END IF;
   IF r->>'attempt_id' IS NOT NULL THEN SELECT evaluation_id INTO n FROM evaluation_attempts WHERE id=(r->>'attempt_id')::bigint;
    RETURN t11b9_resource('EVALUATION',n); END IF;
   IF tab='whiteboard_sessions' THEN RETURN t11b9_resource('BOARD',(r->>'id')::bigint); END IF;
   IF tab='whiteboard_participants' THEN RETURN t11b9_resource('BOARD',(r->>'session_id')::bigint); END IF;
   IF r->>'board_id' IS NOT NULL THEN RETURN t11b9_resource('BOARD',(r->>'board_id')::bigint); END IF;
   IF tab IN ('usage_events','system_logs') THEN
    IF tab='usage_events' THEN SELECT to_jsonb(x) INTO p FROM t11b4_usage_contexts x WHERE event_id=(r->>'id')::bigint;
    ELSE SELECT to_jsonb(x) INTO p FROM t11b4_audit_contexts x WHERE event_id=(r->>'id')::bigint; END IF;
    IF p IS NOT NULL THEN RETURN jsonb_build_object('scope',p->>'scope','owner',coalesce((p->>'institution_id')::bigint,0),'relation','B4_EVENT_CONTEXT'); END IF;
    IF (tab='usage_events' AND r->>'module'='SYSTEM_STATUS') OR (tab='system_logs' AND r->>'event_type' IN
     ('LOGIN_SUCCESS','LOGIN_FAILED','LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','SESSION_REVOKED','SESSIONS_REVOKED','USER_CREATED','USER_UPDATED','USER_DEACTIVATED','USER_REACTIVATED','PERMISSIONS_CHANGED','SYSTEM_HEALTH_CHECK','TECHNICAL_RETENTION_EXECUTED')) THEN
     RETURN '{"scope":"GLOBAL","owner":0,"relation":"GLOBAL_FACT"}'::jsonb; END IF;
   END IF;
   RETURN '{"scope":"UNRESOLVED","owner":0,"relation":"NO_DEMONSTRATED_PROVENANCE"}'::jsonb;
  END $fn$ $ddl$;

  EXECUTE $ddl$ CREATE FUNCTION t11b9_measure() RETURNS TABLE(source_table text,source_pk jsonb,scope text,owner_id bigint,category text,rows_used bigint,bytes_used bigint,source_checksum text,provenance jsonb)
   LANGUAGE plpgsql STABLE SET timezone='UTC' SET bytea_output='hex' SET datestyle='ISO, YMD' AS $fn$
  DECLARE c record;r jsonb; pk jsonb; p jsonb;
  BEGIN
   FOR c IN SELECT * FROM t11b9_contract ORDER BY source_table LOOP
    FOR r IN EXECUTE format('SELECT to_jsonb(x) FROM %I x %s',c.source_table,CASE WHEN c.source_table='system_logs' THEN 'WHERE target_type IS DISTINCT FROM ''StorageMeasurement''' ELSE '' END) LOOP
     SELECT jsonb_object_agg(k,r->k) INTO pk FROM unnest(c.pk_columns) k;
     p:=t11b9_provenance(c.source_table,r);
     source_table:=c.source_table;source_pk:=pk;scope:=p->>'scope';owner_id:=(p->>'owner')::bigint;
     category:=c.category;rows_used:=1;bytes_used:=octet_length(convert_to(r::text,'UTF8'));source_checksum:=md5(r::text);provenance:=p;RETURN NEXT;
    END LOOP;
   END LOOP;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_checksum() RETURNS text LANGUAGE sql STABLE AS $fn$
   SELECT md5(coalesce(string_agg(to_jsonb(m)::text,E'\n' ORDER BY source_table,source_pk::text),'')) FROM t11b9_measure() m
  $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_counter_differences() RETURNS jsonb LANGUAGE sql STABLE AS $fn$
   SELECT coalesce(jsonb_agg(jsonb_build_object('scope',coalesce(c.scope,m.scope),'owner',coalesce(c.owner_id,m.owner_id),'category',coalesce(c.category,m.category),
    'storedRows',coalesce(c.rows_used,0),'measuredRows',coalesce(m.rows_used,0),'storedBytes',coalesce(c.bytes_used,0),'measuredBytes',coalesce(m.bytes_used,0))),'[]'::jsonb)
   FROM t11b9_counters c FULL JOIN (SELECT scope,owner_id,category,sum(rows_used) rows_used,sum(bytes_used) bytes_used FROM t11b9_measure() GROUP BY 1,2,3) m USING(scope,owner_id,category)
   WHERE coalesce(c.rows_used,0)<>coalesce(m.rows_used,0) OR coalesce(c.bytes_used,0)<>coalesce(m.bytes_used,0)
  $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_differences() RETURNS jsonb LANGUAGE sql STABLE AS $fn$
   SELECT coalesce(jsonb_agg(jsonb_build_object('source',coalesce(m.source_table,i.source_table),'pk',coalesce(m.source_pk,i.source_pk),
    'stored',to_jsonb(i)-'command_id','measured',to_jsonb(m)) ORDER BY coalesce(m.source_table,i.source_table),coalesce(m.source_pk,i.source_pk)::text),'[]'::jsonb)
   FROM t11b9_measure() m FULL JOIN t11b9_items i USING(source_table,source_pk)
   WHERE (i.rows_used IS DISTINCT FROM coalesce(m.rows_used,0)) OR (i.bytes_used IS DISTINCT FROM coalesce(m.bytes_used,0))
    OR (i.source_checksum IS DISTINCT FROM m.source_checksum) OR (m.source_table IS NOT NULL AND (i.provenance IS DISTINCT FROM m.provenance OR i.category IS DISTINCT FROM m.category))
  $fn$ $ddl$;
  EXECUTE $ddl$ CREATE VIEW t11b9_observation AS SELECT
   EXISTS(SELECT 1 FROM t11b9_commands WHERE kind='BASELINE') AS prepared,
   EXISTS(SELECT 1 FROM t11b9_commands WHERE kind='BASELINE') AND t11b9_differences()='[]'::jsonb AND NOT EXISTS(
    SELECT 1 FROM t11b9_counters c FULL JOIN (SELECT scope,owner_id,category,sum(rows_used) rows_used,sum(bytes_used) bytes_used FROM t11b9_items GROUP BY 1,2,3) i USING(scope,owner_id,category)
     WHERE coalesce(c.rows_used,0)<>coalesce(i.rows_used,0) OR coalesce(c.bytes_used,0)<>coalesce(i.bytes_used,0)) AS source_current,
   false AS live_authoritative,'ROW_JSON_UTF8_V1'::text AS version $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_control_measure() RETURNS TABLE(scope text,category text,rows_used bigint,bytes_used numeric)
   LANGUAGE sql STABLE SET timezone='UTC' SET bytea_output='hex' SET datestyle='ISO, YMD' AS $fn$
   SELECT 'GLOBAL'::text scope,'CONTROL'::text category,count(*) rows_used,coalesce(sum(octet_length(convert_to(r::text,'UTF8'))),0) bytes_used FROM (
    SELECT to_jsonb(x) r FROM t11b9_contract x UNION ALL SELECT to_jsonb(x) FROM t11b9_schema_version x
    UNION ALL SELECT to_jsonb(x) FROM t11b9_limits x UNION ALL SELECT to_jsonb(x) FROM t11b9_counters x
    UNION ALL SELECT to_jsonb(x) FROM t11b9_commands x UNION ALL SELECT to_jsonb(x) FROM t11b9_items x
    UNION ALL SELECT to_jsonb(x) FROM t11b9_deltas x UNION ALL SELECT to_jsonb(x) FROM t11b9_control_usage x
    UNION ALL SELECT to_jsonb(x) FROM system_logs x WHERE target_type='StorageMeasurement') control $fn$ $ddl$;
  EXECUTE $ddl$ CREATE VIEW t11b9_control_observation AS SELECT * FROM t11b9_control_measure() $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_lock() RETURNS void LANGUAGE plpgsql AS $fn$
  DECLARE c record;
  BEGIN
   PERFORM t11b8_lock();
   FOR c IN SELECT source_table FROM t11b9_contract ORDER BY source_table LOOP EXECUTE format('LOCK TABLE %I IN SHARE ROW EXCLUSIVE MODE',c.source_table); END LOOP;
   LOCK TABLE t11b9_commands,t11b9_items,t11b9_counters,t11b9_limits IN SHARE ROW EXCLUSIVE MODE;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN
   IF pg_trigger_depth()<2 OR TG_OP='TRUNCATE' OR TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B9 generated measurement only; no manual counters or release'; END IF;
   IF TG_TABLE_NAME='t11b9_items' AND TG_OP='UPDATE' THEN
    IF (to_jsonb(NEW)-ARRAY['rows_used','bytes_used','source_checksum','command_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['rows_used','bytes_used','source_checksum','command_id']) THEN
     RAISE EXCEPTION 'T11-B9 immutable attribution; corrections belong to P05'; END IF;
   END IF;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_immutable() RETURNS trigger LANGUAGE plpgsql AS $fn$
  BEGIN RAISE EXCEPTION 'T11-B9 append-only contract/evidence'; END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_charge() RETURNS trigger LANGUAGE plpgsql SET timezone='UTC' SET bytea_output='hex' SET datestyle='ISO, YMD' AS $fn$
  DECLARE delta bigint;
  BEGIN
   delta:=octet_length(convert_to(to_jsonb(NEW)::text,'UTF8'));
   IF TG_OP='UPDATE' THEN delta:=delta-octet_length(convert_to(to_jsonb(OLD)::text,'UTF8')); END IF;
   UPDATE t11b9_control_usage SET rows_used=rows_used+CASE WHEN TG_OP='INSERT' THEN 1 ELSE 0 END,bytes_used=bytes_used+delta WHERE id=1;
   RETURN NEW;
  END $fn$ $ddl$;
  EXECUTE $ddl$ CREATE FUNCTION t11b9_command() RETURNS trigger LANGUAGE plpgsql AS $fn$
  DECLARE diffs jsonb; r record; before_row jsonb; checksum text; baseline boolean; target bigint;
  BEGIN
   PERFORM t11b9_lock();
   SELECT EXISTS(SELECT 1 FROM t11b9_commands WHERE kind='BASELINE') INTO baseline;
   checksum:=t11b9_checksum();
   IF NEW.kind='LIMIT' THEN
    target:=(NEW.request->>'institution')::bigint;
    INSERT INTO t11b9_limits VALUES(target,NEW.request->>'category',(NEW.request->>'rows')::bigint,(NEW.request->>'bytes')::bigint)
     ON CONFLICT(institution_id,category) DO UPDATE SET maximum_rows=excluded.maximum_rows,maximum_bytes=excluded.maximum_bytes;
    NEW.result:=NEW.request;RETURN NEW;
   END IF;
   IF NEW.kind='BASELINE' AND (baseline OR NEW.expected_checksum IS DISTINCT FROM checksum OR NEW.request->>'evidence' !~ '^FIXTURE-[A-Z0-9_-]{1,80}$' OR NEW.request->>'evidence' IS NULL) THEN
    RAISE EXCEPTION 'T11-B9 one explicitly approved exact historical baseline required'; END IF;
   IF NEW.kind<>'BASELINE' AND NOT baseline THEN RAISE EXCEPTION 'T11-B9 baseline required'; END IF;
   diffs:=t11b9_differences();
   IF NEW.kind='VERIFY' THEN
    NEW.result:=jsonb_build_object('version','ROW_JSON_UTF8_V1','checksum',checksum,'differences',diffs,'counterDifferences',t11b9_counter_differences(),'observation',(SELECT to_jsonb(o) FROM t11b9_observation o));RETURN NEW;
   END IF;
   IF NEW.kind='SYNC' AND EXISTS(SELECT 1 FROM t11b9_counters c FULL JOIN
    (SELECT scope,owner_id,category,sum(rows_used) rows_used,sum(bytes_used) bytes_used FROM t11b9_items GROUP BY 1,2,3) i USING(scope,owner_id,category)
    WHERE coalesce(c.rows_used,0)<>coalesce(i.rows_used,0) OR coalesce(c.bytes_used,0)<>coalesce(i.bytes_used,0)) THEN RAISE EXCEPTION 'T11-B9 counter divergence; no repair'; END IF;
   -- Do not silently adopt a provenance correction, even if the sum would be unchanged.
   IF EXISTS(SELECT 1 FROM t11b9_items i JOIN t11b9_measure() m USING(source_table,source_pk) WHERE i.provenance IS DISTINCT FROM m.provenance OR i.category IS DISTINCT FROM m.category) THEN
    RAISE EXCEPTION 'T11-B9 provenance divergence; P05 required'; END IF;
   FOR r IN SELECT coalesce(m.scope,i.scope) scope,coalesce(m.owner_id,i.owner_id) owner_id,coalesce(m.category,i.category) category,
    sum(coalesce(m.rows_used,0)-coalesce(i.rows_used,0)) rd,sum(coalesce(m.bytes_used,0)-coalesce(i.bytes_used,0)) bd
    FROM t11b9_measure() m FULL JOIN t11b9_items i USING(source_table,source_pk) GROUP BY 1,2,3 ORDER BY 1,2,3 LOOP
    INSERT INTO t11b9_counters VALUES(r.scope,r.owner_id,r.category,0,0) ON CONFLICT DO NOTHING;
    IF NEW.kind='SYNC' AND r.scope='INSTITUTION' THEN
     IF NOT EXISTS(SELECT 1 FROM t11b9_limits WHERE institution_id=r.owner_id AND category=r.category) AND (r.rd>0 OR r.bd>0) THEN RAISE EXCEPTION 'T11-B9 explicit fixture limit required'; END IF;
     IF EXISTS(SELECT 1 FROM t11b9_counters c JOIN t11b9_limits l ON l.institution_id=c.owner_id AND l.category=c.category
      WHERE c.scope=r.scope AND c.owner_id=r.owner_id AND c.category=r.category AND ((r.rd>0 AND c.rows_used+r.rd>l.maximum_rows) OR (r.bd>0 AND c.bytes_used+r.bd>l.maximum_bytes))) THEN
      RAISE EXCEPTION 'T11-B9 institution quota exceeded' USING ERRCODE='P7501'; END IF;
    END IF;
    IF NEW.kind='SYNC' AND r.scope<>'INSTITUTION' AND EXISTS(SELECT 1 FROM t11b9_counters c WHERE c.scope=r.scope AND c.owner_id=r.owner_id AND c.category=r.category
     AND ((r.rd>0 AND c.rows_used+r.rd>1000000) OR (r.bd>0 AND c.bytes_used+r.bd>268435456))) THEN
     RAISE EXCEPTION 'T11-B9 unattributed technical quota exceeded' USING ERRCODE='P7501'; END IF;
    UPDATE t11b9_counters SET rows_used=rows_used+r.rd,bytes_used=bytes_used+r.bd WHERE scope=r.scope AND owner_id=r.owner_id AND category=r.category;
   END LOOP;
   FOR r IN SELECT * FROM jsonb_array_elements(diffs) LOOP
    before_row:=r.value->'stored';
    INSERT INTO t11b9_deltas(command_id,source_table,source_pk,before_measurement,after_measurement,rows_delta,bytes_delta)
     VALUES(NEW.id,r.value->>'source',r.value->'pk',before_row,coalesce(nullif(r.value->'measured','null'::jsonb),'{}'::jsonb),
      coalesce((r.value->'measured'->>'rows_used')::bigint,0)-coalesce((before_row->>'rows_used')::bigint,0),
      coalesce((r.value->'measured'->>'bytes_used')::bigint,0)-coalesce((before_row->>'bytes_used')::bigint,0));
   END LOOP;
   INSERT INTO t11b9_items SELECT m.*,NEW.id FROM t11b9_measure() m
    ON CONFLICT(source_table,source_pk) DO UPDATE SET rows_used=excluded.rows_used,bytes_used=excluded.bytes_used,source_checksum=excluded.source_checksum,command_id=excluded.command_id;
   UPDATE t11b9_items i SET rows_used=0,bytes_used=0,source_checksum=NULL,command_id=NEW.id WHERE rows_used<>0 AND NOT EXISTS(SELECT 1 FROM t11b9_measure() m WHERE m.source_table=i.source_table AND m.source_pk=i.source_pk);
   NEW.result:=jsonb_build_object('version','ROW_JSON_UTF8_V1','checksum',checksum,'deltas',jsonb_array_length(diffs),'historical',NEW.kind='BASELINE');RETURN NEW;
  END $fn$ $ddl$;
  CREATE TRIGGER t11b9_command BEFORE INSERT ON t11b9_commands FOR EACH ROW EXECUTE FUNCTION t11b9_command();
  FOREACH tab IN ARRAY ARRAY['t11b9_contract','t11b9_schema_version','t11b9_commands','t11b9_deltas'] LOOP
   EXECUTE format('CREATE TRIGGER t11b9_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION t11b9_immutable()',tab);
   EXECUTE format('CREATE TRIGGER t11b9_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b9_immutable()',tab);
  END LOOP;
  CREATE TRIGGER t11b9_contract_insert BEFORE INSERT ON t11b9_contract FOR EACH ROW EXECUTE FUNCTION t11b9_immutable();
  CREATE TRIGGER t11b9_delta_insert BEFORE INSERT ON t11b9_deltas FOR EACH ROW EXECUTE FUNCTION t11b9_guard();
  FOREACH tab IN ARRAY ARRAY['t11b9_items','t11b9_counters','t11b9_limits','t11b9_control_usage'] LOOP
   EXECUTE format('CREATE TRIGGER t11b9_guard BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION t11b9_guard()',tab);
   EXECUTE format('CREATE TRIGGER t11b9_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION t11b9_immutable()',tab);
  END LOOP;
  FOREACH tab IN ARRAY ARRAY['t11b9_items','t11b9_commands','t11b9_deltas','t11b9_limits'] LOOP
   EXECUTE format('CREATE TRIGGER t11b9_charge AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION t11b9_charge()',tab);
  END LOOP;
 END IF;
    SELECT md5(string_agg(definition,E'\n' ORDER BY definition)) INTO actual FROM (
  SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||a.attgenerated::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') definition
   FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
   WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b9_%' AND a.attnum>0 AND NOT a.attisdropped
  UNION ALL SELECT 'constraint:'||r.relname||':'||c.conname||':'||pg_get_constraintdef(c.oid)||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b9_%'
  UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename LIKE 't11b9_%'
  UNION ALL SELECT 'indexstate:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b9_%'
  UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid WHERE r.relnamespace=current_schema()::regnamespace AND r.relname LIKE 't11b9_%' AND NOT t.tgisinternal
  UNION ALL SELECT 'function:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b9_%'
  UNION ALL SELECT 'view:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b9_%' AND c.relkind='v'
  UNION ALL SELECT 'sequence:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmin||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 't11b9_%'
 ) definitions;



 -- Freeze source descriptors separately; limits and measurements remain configurable data.
 SELECT md5(actual||coalesce(string_agg(to_jsonb(c)::text,E'\n' ORDER BY source_table),'')) INTO actual FROM t11b9_contract c;
 IF installed THEN
  SELECT fingerprint INTO expected FROM t11b9_schema_version WHERE version=1 AND revision='t11b9-v1'
   AND predecessor_fingerprint=(SELECT fingerprint FROM t11b8_schema_version WHERE version=1);
  IF expected IS DISTINCT FROM actual OR (SELECT count(*) FROM t11b9_schema_version)<>1 THEN RAISE EXCEPTION 'T11-B9 divergence; no repair'; END IF;
 ELSE INSERT INTO t11b9_schema_version SELECT 1,'t11b9-v1',fingerprint,actual,statement_timestamp() FROM t11b8_schema_version WHERE version=1; END IF;
END $b9$;

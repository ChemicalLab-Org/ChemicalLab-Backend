-- T11-B3 v1. Explicit expansion only; apply in one transaction with ON_ERROR_STOP.
-- Predecessor: B1 v1 -> B2 v1 -> B2 v2. Current certifiers: B1 v1 + B3 v1.
DO $migration$
DECLARE installed boolean; expected text; actual text; predecessor text; successor text;
        audit_check record; tab text; event_column smallint;
BEGIN
    PERFORM pg_advisory_xact_lock(110011,1);
    IF to_regclass(current_schema()||'.t11b2_audit_version') IS NULL
       OR to_regprocedure(current_schema()||'.storage_delta(character varying,bigint,character varying,bigint)') IS NULL THEN
        RAISE EXCEPTION 'T11-B3 requires certified B1/B2 v2 and T07-B storage';
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

    IF actual IS DISTINCT FROM (SELECT fingerprint FROM t11b1_schema_version WHERE revision='t11b1-v1') THEN
        RAISE EXCEPTION 'T11-B3 B1 prerequisite divergence'; END IF;
    SELECT fingerprint INTO predecessor FROM t11b2_audit_version WHERE revision='t11b2-v2' AND version=2
        AND predecessor_fingerprint=(SELECT fingerprint FROM t11b2_schema_version WHERE revision='t11b2-v1');
    IF predecessor IS NULL OR (SELECT count(*) FROM t11b2_audit_version)<>1 THEN
        RAISE EXCEPTION 'T11-B3 unknown B2 predecessor'; END IF;
    installed:=to_regclass(current_schema()||'.t11b3_schema_version') IS NOT NULL;
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
        SELECT fingerprint,b2_successor_fingerprint INTO expected,successor FROM t11b3_schema_version
          WHERE revision='t11b3-v1' AND version=1 AND predecessor_fingerprint=predecessor;
        IF expected IS NULL OR actual IS DISTINCT FROM successor OR (SELECT count(*) FROM t11b3_schema_version)<>1 THEN
            RAISE EXCEPTION 'T11-B3 successor or predecessor divergence'; END IF;
    ELSE
        IF actual IS DISTINCT FROM predecessor THEN RAISE EXCEPTION 'T11-B3 B2 predecessor divergence'; END IF;
        IF EXISTS(SELECT 1 FROM pg_class WHERE relnamespace=current_schema()::regnamespace AND relname LIKE 't11b3_%')
          OR EXISTS(SELECT 1 FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND proname LIKE 't11b3_%') THEN
          RAISE EXCEPTION 'T11-B3 partial unversioned expansion'; END IF;
        LOCK TABLE system_logs IN ACCESS EXCLUSIVE MODE;
        SELECT attnum INTO event_column FROM pg_attribute WHERE attrelid='system_logs'::regclass AND attname='event_type';
        IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='system_logs'::regclass AND contype='c' AND event_column=ANY(conkey)) THEN
          RAISE EXCEPTION 'T11-B3 requires closed audit event CHECK'; END IF;
        FOR audit_check IN SELECT conname,conkey,convalidated,pg_get_expr(conbin,conrelid) expression FROM pg_constraint
          WHERE conrelid='system_logs'::regclass AND contype='c' AND event_column=ANY(conkey) ORDER BY conname LOOP
          IF audit_check.conkey<>ARRAY[event_column] OR NOT audit_check.convalidated THEN RAISE EXCEPTION 'Unsupported audit CHECK'; END IF;
          EXECUTE format('ALTER TABLE system_logs DROP CONSTRAINT %I',audit_check.conname);
          EXECUTE format('ALTER TABLE system_logs ADD CONSTRAINT %I CHECK ((%s) OR event_type::text IN
            (''RESOURCE_RECONCILIATION_PROPOSED'',''RESOURCE_RECONCILIATION_APPROVED'',''RESOURCE_RECONCILIATION_APPLIED'',
             ''RESOURCE_RECONCILIATION_CONFLICT'',''RESOURCE_RECONCILIATION_STALE'',''RESOURCE_REVISION_COPIED''))',audit_check.conname,
             replace(audit_check.expression,'::character varying','::text'));
        END LOOP;
        CREATE TABLE t11b3_schema_version(version integer PRIMARY KEY CHECK(version=1),revision text NOT NULL,
          predecessor_fingerprint text NOT NULL,b2_successor_fingerprint text NOT NULL,fingerprint text NOT NULL,
          installed_at timestamptz NOT NULL DEFAULT statement_timestamp());
        -- Explicit additional quota category; no transfer or reset of original usage/caps.
        INSERT INTO storage_limits VALUES('REVISION_BYTES',268435456),('REVISION_COUNT',1000);
        CREATE TABLE t11b3_reconciliations(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
          origin text NOT NULL CHECK(origin='FICTITIOUS_LEGACY'),
          source_kind text NOT NULL CHECK(source_kind IN ('CONCEPT','EVALUATION')),source_pk bigint NOT NULL,
          source_version text NOT NULL CHECK(source_version='jsonb-v1'),
          source_snapshot jsonb NOT NULL,source_checksum text NOT NULL CHECK(source_checksum=md5(source_snapshot::text)),
          proposed jsonb NOT NULL CHECK(jsonb_typeof(proposed)='object'),proposal_checksum text NOT NULL CHECK(proposal_checksum=md5(proposed::text)),
          status text NOT NULL DEFAULT 'PROPOSED' CHECK(status IN ('PROPOSED','APPROVED','APPLIED','CONFLICT','STALE')),
          approval_evidence text,approved_checksum text,approved_by bigint REFERENCES user_accounts(id),
          changed_by bigint REFERENCES user_accounts(id),recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
          CHECK(status='PROPOSED' OR (approval_evidence IS NOT NULL AND approved_checksum IS NOT NULL AND approval_evidence ~ '^FIXTURE-[A-Z0-9_-]{1,80}$'
            AND approved_checksum=md5(source_checksum||proposal_checksum))),
          UNIQUE(origin,source_kind,source_pk,source_checksum,proposal_checksum));
        CREATE TABLE t11b3_reconciliation_events(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          status text NOT NULL,actor_account_id bigint REFERENCES user_accounts(id),snapshot jsonb NOT NULL,
          recorded_at timestamptz NOT NULL DEFAULT statement_timestamp());
        CREATE TABLE t11b3_concept_resources(
          concept_id bigint PRIMARY KEY REFERENCES concept_contents(id),institution_id bigint NOT NULL REFERENCES institutions(id),
          author_account_id bigint NOT NULL REFERENCES user_accounts(id),author_membership_id bigint,
          reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),UNIQUE(concept_id,institution_id),
          FOREIGN KEY(author_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id));
        CREATE TABLE t11b3_evaluation_resources(
          evaluation_id bigint PRIMARY KEY REFERENCES evaluations(id),institution_id bigint NOT NULL REFERENCES institutions(id),
          author_account_id bigint NOT NULL REFERENCES user_accounts(id),author_membership_id bigint,
          reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),UNIQUE(evaluation_id,institution_id),
          FOREIGN KEY(author_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id));
        CREATE TABLE t11b3_opportunity_groups(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,origin text NOT NULL CHECK(origin='FICTITIOUS_LEGACY'),
          evaluation_id bigint NOT NULL REFERENCES evaluations(id),max_attempts integer NOT NULL CHECK(max_attempts>0),
          configuration jsonb NOT NULL,assignments jsonb NOT NULL,opportunities_closed boolean NOT NULL,evaluated_at_local timestamp NOT NULL CHECK(isfinite(evaluated_at_local)),reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          UNIQUE(origin,evaluation_id),UNIQUE(evaluation_id),UNIQUE(id,evaluation_id));
        ALTER TABLE t11b3_evaluation_resources ADD CONSTRAINT t11b3_resource_root FOREIGN KEY(evaluation_id) REFERENCES t11b3_opportunity_groups(evaluation_id);
        CREATE TABLE t11b3_account_attempt_budgets(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,group_id bigint NOT NULL REFERENCES t11b3_opportunity_groups(id),
          account_id bigint NOT NULL REFERENCES user_accounts(id),used_count bigint NOT NULL CHECK(used_count>=0),
          next_number bigint,active_attempt_id bigint REFERENCES evaluation_attempts(id),
          status text NOT NULL CHECK(status IN ('RECONCILED','CONFLICT')),reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          UNIQUE(group_id,account_id),UNIQUE(id,group_id,account_id),
          CHECK((status='RECONCILED' AND next_number IS NOT NULL AND next_number=used_count+1) OR (status='CONFLICT' AND next_number IS NULL AND active_attempt_id IS NULL)));
        CREATE TABLE t11b3_attempt_consumptions(
          attempt_id bigint PRIMARY KEY REFERENCES evaluation_attempts(id),budget_id bigint NOT NULL,group_id bigint NOT NULL,account_id bigint NOT NULL,
          attempt_number integer NOT NULL,status_at_reconciliation text NOT NULL,verified boolean NOT NULL,
          source_snapshot jsonb NOT NULL,reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          FOREIGN KEY(budget_id,group_id,account_id) REFERENCES t11b3_account_attempt_budgets(id,group_id,account_id));
        CREATE UNIQUE INDEX t11b3_verified_number ON t11b3_attempt_consumptions(budget_id,attempt_number) WHERE verified;
        CREATE UNIQUE INDEX t11b3_verified_active ON t11b3_attempt_consumptions(budget_id) WHERE verified AND status_at_reconciliation='IN_PROGRESS';

        CREATE TABLE t11b3_concept_revisions(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,concept_id bigint NOT NULL,institution_id bigint NOT NULL,
          revision_number integer NOT NULL CHECK(revision_number>0),payload jsonb NOT NULL,payload_checksum text NOT NULL CHECK(payload_checksum=md5(payload::text)),
          reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          UNIQUE(concept_id,revision_number),UNIQUE(id,institution_id),UNIQUE(id,institution_id,concept_id),
          FOREIGN KEY(concept_id,institution_id) REFERENCES t11b3_concept_resources(concept_id,institution_id));
        CREATE TABLE t11b3_concept_deliveries(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,institution_id bigint NOT NULL,class_id bigint NOT NULL,
          concept_id bigint NOT NULL,revision_id bigint NOT NULL,legacy_assignment_id bigint NOT NULL UNIQUE REFERENCES concept_assignments(id),
          assignment_snapshot jsonb NOT NULL,reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          UNIQUE(revision_id,class_id),UNIQUE(id,institution_id,class_id),UNIQUE(id,institution_id,revision_id),
          FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id),
          FOREIGN KEY(revision_id,institution_id,concept_id) REFERENCES t11b3_concept_revisions(id,institution_id,concept_id));
        CREATE TABLE t11b3_concept_recipients(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,delivery_id bigint NOT NULL,institution_id bigint NOT NULL,class_id bigint NOT NULL,
          account_id bigint NOT NULL REFERENCES user_accounts(id),enrollment_id bigint,
          evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Z0-9_-]{1,80}$'),
          UNIQUE(delivery_id,account_id),UNIQUE(id,delivery_id,institution_id,account_id),
          FOREIGN KEY(delivery_id,institution_id,class_id) REFERENCES t11b3_concept_deliveries(id,institution_id,class_id),
          FOREIGN KEY(enrollment_id,institution_id) REFERENCES enrollments(id,institution_id));

        CREATE TABLE t11b3_evaluation_revisions(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,evaluation_id bigint NOT NULL,institution_id bigint NOT NULL,
          revision_number integer NOT NULL CHECK(revision_number>0),payload jsonb NOT NULL,payload_checksum text NOT NULL CHECK(payload_checksum=md5(payload::text)),
          reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          UNIQUE(evaluation_id,revision_number),UNIQUE(id,institution_id),UNIQUE(id,institution_id,evaluation_id),
          FOREIGN KEY(evaluation_id,institution_id) REFERENCES t11b3_evaluation_resources(evaluation_id,institution_id));
        CREATE TABLE t11b3_evaluation_deliveries(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,institution_id bigint NOT NULL,class_id bigint NOT NULL,
          evaluation_id bigint NOT NULL,revision_id bigint NOT NULL,legacy_assignment_id bigint NOT NULL UNIQUE REFERENCES evaluation_assignments(id),
          assignment_snapshot jsonb NOT NULL,reconciliation_id bigint NOT NULL REFERENCES t11b3_reconciliations(id),
          UNIQUE(revision_id,class_id),UNIQUE(id,institution_id,class_id),UNIQUE(id,institution_id,revision_id),
          FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id),
          FOREIGN KEY(revision_id,institution_id,evaluation_id) REFERENCES t11b3_evaluation_revisions(id,institution_id,evaluation_id));
        CREATE TABLE t11b3_evaluation_recipients(
          id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,delivery_id bigint NOT NULL,institution_id bigint NOT NULL,class_id bigint NOT NULL,
          account_id bigint NOT NULL REFERENCES user_accounts(id),enrollment_id bigint,
          evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Z0-9_-]{1,80}$'),
          UNIQUE(delivery_id,account_id),UNIQUE(id,delivery_id,institution_id,account_id),
          FOREIGN KEY(delivery_id,institution_id,class_id) REFERENCES t11b3_evaluation_deliveries(id,institution_id,class_id),
          FOREIGN KEY(enrollment_id,institution_id) REFERENCES enrollments(id,institution_id));

        CREATE TABLE t11b3_attempt_contexts(
          attempt_id bigint PRIMARY KEY REFERENCES t11b3_attempt_consumptions(attempt_id),institution_id bigint NOT NULL,
          delivery_id bigint NOT NULL,revision_id bigint NOT NULL,recipient_id bigint NOT NULL,account_id bigint NOT NULL,
          enrollment_id bigint NOT NULL REFERENCES enrollments(id),started_at timestamp NOT NULL,
          source_timezone text NOT NULL CHECK(source_timezone='America/Lima'),evidence text NOT NULL CHECK(evidence ~ '^FIXTURE-[A-Z0-9_-]{1,80}$'),
          FOREIGN KEY(recipient_id,delivery_id,institution_id,account_id) REFERENCES t11b3_evaluation_recipients(id,delivery_id,institution_id,account_id),
          FOREIGN KEY(delivery_id,institution_id,revision_id) REFERENCES t11b3_evaluation_deliveries(id,institution_id,revision_id));
        EXECUTE $ddl$
        CREATE FUNCTION t11b3_snapshot(k text,pk bigint) RETURNS jsonb LANGUAGE plpgsql STABLE AS $fn$
        DECLARE result jsonb;
        BEGIN
          IF k='EVALUATION' THEN
            SELECT jsonb_build_object('resource',to_jsonb(e),'questions',coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.id)
              FROM evaluation_questions q WHERE q.evaluation_id=pk),'[]'::jsonb),
              'options',coalesce((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM evaluation_options o JOIN evaluation_questions q ON q.id=o.question_id WHERE q.evaluation_id=pk),'[]'::jsonb),
              'assignments',coalesce((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM evaluation_assignments a WHERE a.evaluation_id=pk),'[]'::jsonb),
              'attempts',coalesce((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM evaluation_attempts a WHERE a.evaluation_id=pk),'[]'::jsonb),
              'profiles',coalesce((SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM student_profiles s WHERE s.user_id IN
                (SELECT p.user_id FROM student_profiles p JOIN evaluation_attempts a ON a.student_id=p.id WHERE a.evaluation_id=pk)),'[]'::jsonb),
              'answers',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM evaluation_answers x JOIN evaluation_attempts a ON a.id=x.attempt_id WHERE a.evaluation_id=pk),'[]'::jsonb),
              'adjustments',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM evaluation_attempt_adjustments x JOIN evaluation_attempts a ON a.id=x.attempt_id WHERE a.evaluation_id=pk),'[]'::jsonb),
              'events',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM evaluation_attempt_events x JOIN evaluation_attempts a ON a.id=x.attempt_id WHERE a.evaluation_id=pk),'[]'::jsonb),
              'author',to_jsonb(t)) INTO result FROM evaluations e JOIN teacher_profiles t ON t.id=e.created_by_teacher_id WHERE e.id=pk;
          ELSIF k='CONCEPT' THEN
            SELECT jsonb_build_object('resource',to_jsonb(c),'materials',coalesce((SELECT jsonb_agg(to_jsonb(m) ORDER BY m.id) FROM concept_materials m WHERE m.concept_content_id=pk),'[]'::jsonb),
              'steps',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.step_order) FROM concept_content_formation_steps x WHERE x.concept_content_id=pk),'[]'::jsonb),
              'points',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.key_point_order) FROM concept_content_key_points x WHERE x.concept_content_id=pk),'[]'::jsonb),
              'examples',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.example_order) FROM concept_content_examples x WHERE x.concept_content_id=pk),'[]'::jsonb),
              'assignments',coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY x.id) FROM concept_assignments x WHERE x.concept_content_id=pk),'[]'::jsonb),
              'author',to_jsonb(t)) INTO result FROM concept_contents c JOIN teacher_profiles t ON t.id=c.created_by_teacher_id WHERE c.id=pk;
          ELSE RAISE EXCEPTION 'Unsupported source kind'; END IF;
          IF result IS NULL THEN RAISE EXCEPTION 'Missing legacy source'; END IF;
          RETURN result;
        END $fn$ $ddl$;
        EXECUTE $ddl$
        CREATE FUNCTION t11b3_map_snapshot(m jsonb) RETURNS jsonb LANGUAGE sql STABLE AS $fn$
          SELECT jsonb_build_object(
            'legacy_profiles',coalesce((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM student_profiles p WHERE p.user_id IN (SELECT (y->>'accountId')::bigint FROM jsonb_array_elements(m->'deliveries') x CROSS JOIN LATERAL jsonb_array_elements(x->'recipients') y UNION SELECT (x->>'accountId')::bigint FROM jsonb_array_elements(m->'contexts') x)),'[]'::jsonb),
            'institution',(SELECT to_jsonb(i) FROM institutions i WHERE id=(m->>'institutionId')::bigint),
            'author_membership',(SELECT to_jsonb(i) FROM institution_memberships i WHERE id=(m->>'authorMembershipId')::bigint),
            'classes',coalesce((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM academic_classes c WHERE c.id IN
              (SELECT (x->>'classId')::bigint FROM jsonb_array_elements(m->'deliveries') x)),'[]'::jsonb),
            'enrollments',coalesce((SELECT jsonb_agg(to_jsonb(e) ORDER BY e.id) FROM enrollments e WHERE e.id IN
              (SELECT (y->>'enrollmentId')::bigint FROM jsonb_array_elements(m->'deliveries') x CROSS JOIN LATERAL jsonb_array_elements(x->'recipients') y
               UNION SELECT (x->>'enrollmentId')::bigint FROM jsonb_array_elements(m->'contexts') x)),'[]'::jsonb),
            'memberships',coalesce((SELECT jsonb_agg(to_jsonb(i) ORDER BY i.id) FROM institution_memberships i WHERE id IN
              (SELECT e.membership_id FROM enrollments e WHERE e.id IN
                (SELECT (y->>'enrollmentId')::bigint FROM jsonb_array_elements(m->'deliveries') x CROSS JOIN LATERAL jsonb_array_elements(x->'recipients') y
                 UNION SELECT (x->>'enrollmentId')::bigint FROM jsonb_array_elements(m->'contexts') x))),'[]'::jsonb));
        $fn$ $ddl$;
        EXECUTE $ddl$
        CREATE FUNCTION t11b3_immutable() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN RAISE EXCEPTION 'T11-B3 history is immutable'; END $fn$ $ddl$;
        EXECUTE $ddl$
        CREATE FUNCTION t11b3_reconciliation_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
          IF TG_OP='DELETE' THEN RAISE EXCEPTION 'T11-B3 ledger is persistent'; END IF;
          IF TG_OP='INSERT' AND NEW.status<>'PROPOSED' THEN RAISE EXCEPTION 'Proposal required'; END IF;
          IF TG_OP='UPDATE' THEN
            IF (to_jsonb(NEW)-ARRAY['status','approval_evidence','approved_checksum','approved_by','changed_by']) IS DISTINCT FROM
               (to_jsonb(OLD)-ARRAY['status','approval_evidence','approved_checksum','approved_by','changed_by']) OR
               NOT ((OLD.status='PROPOSED' AND NEW.status='APPROVED') OR (OLD.status='APPROVED' AND NEW.status IN ('APPLIED','CONFLICT','STALE')) OR (OLD.status IN ('APPLIED','CONFLICT') AND NEW.status='STALE'))
               OR (OLD.status<>'PROPOSED' AND (NEW.approval_evidence IS DISTINCT FROM OLD.approval_evidence OR NEW.approved_checksum IS DISTINCT FROM OLD.approved_checksum OR NEW.approved_by IS DISTINCT FROM OLD.approved_by)) THEN
              RAISE EXCEPTION 'T11-B3 invalid ledger transition'; END IF;
          END IF;
          RETURN NEW;
        END $fn$ $ddl$;
        EXECUTE $ddl$
        CREATE FUNCTION t11b3_ledger_event() RETURNS trigger LANGUAGE plpgsql AS $fn$
        BEGIN
          INSERT INTO t11b3_reconciliation_events(reconciliation_id,status,actor_account_id,snapshot)
            VALUES(NEW.id,NEW.status,NEW.changed_by,to_jsonb(NEW)-'source_snapshot');
          RETURN NEW;
        END $fn$ $ddl$;
        EXECUTE $ddl$
        CREATE FUNCTION t11b3_copy_quota() RETURNS trigger LANGUAGE plpgsql AS $fn$
        DECLARE owner bigint; entry bigint;
        BEGIN
          IF TG_TABLE_NAME='t11b3_reconciliations' THEN owner:=(NEW.source_snapshot->'author'->>'user_id')::bigint;
          ELSE
            IF TG_TABLE_NAME IN ('t11b3_concept_recipients','t11b3_evaluation_recipients') THEN
              EXECUTE format('SELECT reconciliation_id FROM %I WHERE id=$1',replace(TG_TABLE_NAME,'recipients','deliveries')) INTO STRICT entry USING NEW.delivery_id;
            ELSIF TG_TABLE_NAME='t11b3_attempt_contexts' THEN SELECT reconciliation_id INTO STRICT entry FROM t11b3_attempt_consumptions WHERE attempt_id=NEW.attempt_id;
            ELSE entry:=NEW.reconciliation_id; END IF;
            SELECT (source_snapshot->'author'->>'user_id')::bigint INTO STRICT owner FROM t11b3_reconciliations WHERE id=entry;
          END IF;
          IF TG_OP='UPDATE' THEN
            PERFORM storage_delta('ACCOUNT',owner,'REVISION_BYTES',(octet_length(to_jsonb(NEW)::text)-octet_length(to_jsonb(OLD)::text))::bigint); RETURN NEW;
          END IF;
          PERFORM storage_delta('ACCOUNT',owner,'REVISION_COUNT',1);
          -- Logical serialized bytes conservatively include bytea's hex expansion. No compression discount.
          PERFORM storage_delta('ACCOUNT',owner,'REVISION_BYTES',octet_length(to_jsonb(NEW)::text)::bigint);
          RETURN NEW;
        END $fn$ $ddl$;
        EXECUTE $ddl$
        CREATE FUNCTION t11b3_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $fn$
        #variable_conflict use_column
        DECLARE k text; resource bigint; owner bigint; s jsonb; m jsonb; e enrollments%ROWTYPE; a evaluation_attempts%ROWTYPE; rec record; g record;
        BEGIN
          IF TG_TABLE_NAME IN ('t11b3_concept_resources','t11b3_evaluation_resources') THEN
            k:=CASE WHEN TG_TABLE_NAME='t11b3_concept_resources' THEN 'CONCEPT' ELSE 'EVALUATION' END;
            resource:=coalesce((to_jsonb(NEW)->>'concept_id')::bigint,(to_jsonb(NEW)->>'evaluation_id')::bigint);
            SELECT source_snapshot,proposed INTO STRICT s,m FROM t11b3_reconciliations WHERE id=NEW.reconciliation_id AND source_kind=k AND source_pk=resource AND status='APPROVED';
            owner:=(s->'author'->>'user_id')::bigint;
            IF NEW.institution_id IS DISTINCT FROM (m->>'institutionId')::bigint OR NEW.author_membership_id IS DISTINCT FROM (m->>'authorMembershipId')::bigint OR NEW.author_account_id<>owner OR (NEW.author_membership_id IS NOT NULL AND NOT EXISTS
                (SELECT 1 FROM institution_memberships WHERE id=NEW.author_membership_id AND institution_id=NEW.institution_id AND account_id=owner)) THEN
              RAISE EXCEPTION 'Unproven resource author'; END IF;
          ELSIF TG_TABLE_NAME='t11b3_opportunity_groups' THEN
            SELECT source_snapshot INTO STRICT s FROM t11b3_reconciliations WHERE id=NEW.reconciliation_id AND source_kind='EVALUATION' AND source_pk=NEW.evaluation_id AND status='APPROVED';
            IF NEW.configuration IS DISTINCT FROM s->'resource' OR NEW.assignments IS DISTINCT FROM s->'assignments' OR NEW.max_attempts IS DISTINCT FROM (s->'resource'->>'max_attempts')::int OR NEW.opportunities_closed IS DISTINCT FROM t11b3_review_closed(NEW.configuration,NEW.assignments,NEW.evaluated_at_local) THEN RAISE EXCEPTION 'Root must conserve original configuration'; END IF;
          ELSIF TG_TABLE_NAME='t11b3_account_attempt_budgets' THEN
            SELECT * INTO STRICT g FROM t11b3_opportunity_groups WHERE id=NEW.group_id;
            IF NEW.reconciliation_id<>g.reconciliation_id OR NEW.used_count<>(SELECT count(*) FROM evaluation_attempts a JOIN student_profiles p ON p.id=a.student_id WHERE a.evaluation_id=g.evaluation_id AND p.user_id=NEW.account_id)
              OR (NEW.status='RECONCILED' AND ((SELECT count(*) FROM student_profiles WHERE user_id=NEW.account_id)<>1
                OR EXISTS(SELECT 1 FROM evaluation_attempts a JOIN student_profiles p ON p.id=a.student_id WHERE a.evaluation_id=g.evaluation_id AND p.user_id=NEW.account_id GROUP BY p.user_id
                 HAVING count(DISTINCT attempt_number)<>count(*) OR min(attempt_number)<>1 OR max(attempt_number)<>count(*) OR count(*) FILTER(WHERE status='IN_PROGRESS')>1 OR count(*)>g.max_attempts)
                OR NEW.active_attempt_id IS DISTINCT FROM (SELECT min(a.id) FROM evaluation_attempts a JOIN student_profiles p ON p.id=a.student_id WHERE a.evaluation_id=g.evaluation_id AND p.user_id=NEW.account_id AND a.status='IN_PROGRESS'))) THEN RAISE EXCEPTION 'Budget must reserve complete original consumption'; END IF;
          ELSIF TG_TABLE_NAME IN ('t11b3_concept_revisions' ,'t11b3_evaluation_revisions') THEN
            SELECT source_snapshot INTO STRICT s FROM t11b3_reconciliations WHERE id=NEW.reconciliation_id AND status IN ('APPROVED','APPLIED') AND source_kind=CASE WHEN TG_TABLE_NAME='t11b3_concept_revisions' THEN 'CONCEPT' ELSE 'EVALUATION' END AND source_pk=coalesce((to_jsonb(NEW)->>'concept_id')::bigint,(to_jsonb(NEW)->>'evaluation_id')::bigint) AND (proposed->>'institutionId')::bigint=NEW.institution_id;
            IF NEW.payload IS DISTINCT FROM (s-ARRAY['attempts','profiles','answers','adjustments','events','map_context']) THEN RAISE EXCEPTION 'Revision must freeze approved source content'; END IF;
          ELSIF TG_TABLE_NAME IN ('t11b3_concept_deliveries' ,'t11b3_evaluation_deliveries') THEN
            SELECT proposed INTO STRICT m FROM t11b3_reconciliations WHERE id=NEW.reconciliation_id AND status IN ('APPROVED','APPLIED') AND source_kind=CASE WHEN TG_TABLE_NAME='t11b3_concept_deliveries' THEN 'CONCEPT' ELSE 'EVALUATION' END AND source_pk=coalesce((to_jsonb(NEW)->>'concept_id')::bigint,(to_jsonb(NEW)->>'evaluation_id')::bigint);
            IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(m->'deliveries') x WHERE (x->>'assignmentId')::bigint=NEW.legacy_assignment_id AND (x->>'classId')::bigint=NEW.class_id) THEN RAISE EXCEPTION 'Delivery requires approved explicit class map'; END IF;
            IF TG_TABLE_NAME='t11b3_evaluation_deliveries' THEN
              IF NOT EXISTS(SELECT 1 FROM evaluation_assignments WHERE id=NEW.legacy_assignment_id AND evaluation_id=NEW.evaluation_id AND to_jsonb(evaluation_assignments)=NEW.assignment_snapshot) THEN RAISE EXCEPTION 'Wrong evaluation assignment'; END IF;
            ELSE
              IF NOT EXISTS(SELECT 1 FROM concept_assignments WHERE id=NEW.legacy_assignment_id AND concept_content_id=NEW.concept_id AND to_jsonb(concept_assignments)=NEW.assignment_snapshot) THEN RAISE EXCEPTION 'Wrong concept assignment'; END IF;
            END IF;
          ELSIF TG_TABLE_NAME IN ('t11b3_concept_recipients','t11b3_evaluation_recipients') THEN
            EXECUTE format('SELECT r.proposed,d.legacy_assignment_id FROM %I d JOIN t11b3_reconciliations r ON r.id=d.reconciliation_id WHERE d.id=$1',replace(TG_TABLE_NAME,'recipients','deliveries')) INTO STRICT rec USING NEW.delivery_id;
            IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(rec.proposed->'deliveries') x CROSS JOIN LATERAL jsonb_array_elements(x->'recipients') y
              WHERE (x->>'assignmentId')::bigint=rec.legacy_assignment_id AND (x->>'classId')::bigint=NEW.class_id AND (y->>'accountId')::bigint=NEW.account_id
              AND (y->>'enrollmentId')::bigint IS NOT DISTINCT FROM NEW.enrollment_id AND y->>'evidence'=NEW.evidence) THEN RAISE EXCEPTION 'Recipient requires approved explicit roster'; END IF;
            IF NEW.enrollment_id IS NOT NULL THEN
              SELECT * INTO STRICT e FROM enrollments WHERE id=NEW.enrollment_id;
              IF e.class_id<>NEW.class_id OR e.institution_id<>NEW.institution_id OR NOT EXISTS
                (SELECT 1 FROM institution_memberships WHERE id=e.membership_id AND account_id=NEW.account_id) THEN RAISE EXCEPTION 'Wrong historical enrollment'; END IF;
            END IF;
          ELSIF TG_TABLE_NAME='t11b3_attempt_consumptions' THEN
            SELECT * INTO STRICT a FROM evaluation_attempts WHERE id=NEW.attempt_id;
            SELECT * INTO STRICT g FROM t11b3_opportunity_groups WHERE id=NEW.group_id;
            IF NEW.verified IS DISTINCT FROM (SELECT status='RECONCILED' FROM t11b3_account_attempt_budgets WHERE id=NEW.budget_id) OR NEW.reconciliation_id<>g.reconciliation_id OR a.evaluation_id<>g.evaluation_id OR to_jsonb(a)<>NEW.source_snapshot OR a.attempt_number<>NEW.attempt_number OR a.status<>NEW.status_at_reconciliation
              OR NOT EXISTS(SELECT 1 FROM student_profiles WHERE id=a.student_id AND user_id=NEW.account_id) THEN RAISE EXCEPTION 'Wrong original attempt consumption'; END IF;
          ELSIF TG_TABLE_NAME='t11b3_attempt_contexts' THEN
            SELECT r.proposed INTO STRICT m FROM t11b3_reconciliations r JOIN t11b3_attempt_consumptions c ON c.reconciliation_id=r.id WHERE c.attempt_id=NEW.attempt_id;
            IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(m->'contexts') x WHERE (x->>'attemptId')::bigint=NEW.attempt_id AND (x->>'accountId')::bigint=NEW.account_id AND (x->>'enrollmentId')::bigint=NEW.enrollment_id AND x->>'evidence'=NEW.evidence AND x->>'sourceTimezone'=NEW.source_timezone) THEN RAISE EXCEPTION 'Context requires approved historical proof'; END IF;
            SELECT * INTO STRICT a FROM evaluation_attempts WHERE id=NEW.attempt_id;
            SELECT r.*,d.legacy_assignment_id INTO STRICT rec FROM t11b3_evaluation_recipients r JOIN t11b3_evaluation_deliveries d ON d.id=r.delivery_id WHERE r.id=NEW.recipient_id;
            SELECT * INTO STRICT e FROM enrollments WHERE id=NEW.enrollment_id;
            IF a.assignment_id IS NULL OR a.assignment_id<>rec.legacy_assignment_id OR NEW.started_at IS DISTINCT FROM a.started_at
              OR e.class_id<>rec.class_id OR e.institution_id<>NEW.institution_id
              OR NOT EXISTS(SELECT 1 FROM institution_memberships WHERE id=e.membership_id AND account_id=NEW.account_id)
              OR NOT EXISTS(SELECT 1 FROM t11b3_attempt_consumptions WHERE attempt_id=a.id AND account_id=NEW.account_id)
              OR e.activated_at IS NULL OR NOT ((a.started_at AT TIME ZONE NEW.source_timezone)>=e.activated_at
                   AND (a.started_at AT TIME ZONE NEW.source_timezone)<least(e.valid_until,coalesce(e.ended_at,e.valid_until))) THEN
               RAISE EXCEPTION 'Unproven attempt episode at start'; END IF;
          END IF;
          RETURN NEW;
        END $fn$ $ddl$;
        CREATE TRIGGER t11b3_guard BEFORE INSERT OR UPDATE OR DELETE ON t11b3_reconciliations FOR EACH ROW EXECUTE FUNCTION t11b3_reconciliation_guard();
        CREATE TRIGGER t11b3_event AFTER INSERT OR UPDATE ON t11b3_reconciliations FOR EACH ROW EXECUTE FUNCTION t11b3_ledger_event();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_reconciliations FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_quota BEFORE INSERT OR UPDATE ON t11b3_reconciliations FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_reconciliation_events FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_reconciliation_events FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_reconciliation_events FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_concept_resources FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_concept_resources FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_concept_resources FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_concept_resources FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_evaluation_resources FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_evaluation_resources FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_evaluation_resources FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_evaluation_resources FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_opportunity_groups FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_opportunity_groups FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_opportunity_groups FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_opportunity_groups FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_account_attempt_budgets FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_account_attempt_budgets FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_account_attempt_budgets FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_account_attempt_budgets FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_attempt_consumptions FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_attempt_consumptions FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_attempt_consumptions FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_attempt_consumptions FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_concept_revisions FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_concept_revisions FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_concept_revisions FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_concept_revisions FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_evaluation_revisions FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_evaluation_revisions FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_evaluation_revisions FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_evaluation_revisions FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_concept_deliveries FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_concept_deliveries FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_concept_deliveries FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_concept_deliveries FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_evaluation_deliveries FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_evaluation_deliveries FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_evaluation_deliveries FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_evaluation_deliveries FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_concept_recipients FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_concept_recipients FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_concept_recipients FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_concept_recipients FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_evaluation_recipients FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_evaluation_recipients FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_evaluation_recipients FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_evaluation_recipients FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();
        CREATE TRIGGER t11b3_immutable BEFORE UPDATE OR DELETE ON t11b3_attempt_contexts FOR EACH ROW EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_truncate BEFORE TRUNCATE ON t11b3_attempt_contexts FOR EACH STATEMENT EXECUTE FUNCTION t11b3_immutable();
        CREATE TRIGGER t11b3_reference BEFORE INSERT ON t11b3_attempt_contexts FOR EACH ROW EXECUTE FUNCTION t11b3_reference_guard();
        CREATE TRIGGER t11b3_quota BEFORE INSERT  ON t11b3_attempt_contexts FOR EACH ROW EXECUTE FUNCTION t11b3_copy_quota();

        EXECUTE $ddl$
        CREATE FUNCTION t11b3_review_closed(config jsonb,assignments jsonb,at_local timestamp) RETURNS boolean LANGUAGE sql IMMUTABLE AS $fn$
          SELECT (config->>'active')::boolean IS NOT TRUE OR config->>'status'<>'PUBLISHED'
           OR (jsonb_array_length(assignments)>0 AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(assignments) a WHERE (a->>'active')::boolean))
           OR (EXISTS(SELECT 1 FROM jsonb_array_elements(assignments) a WHERE (a->>'active')::boolean)
             AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(assignments) a WHERE (a->>'active')::boolean
                AND ((a->>'due_at') IS NULL OR (a->>'due_at')::timestamp>at_local)));
        $fn$ $ddl$;
        CREATE VIEW t11b3_budget_observations AS
          SELECT b.*,g.evaluation_id,g.max_attempts,g.opportunities_closed,
            md5((t11b3_snapshot('EVALUATION',g.evaluation_id)||jsonb_build_object('map_context',t11b3_map_snapshot(r.proposed)))::text)=r.source_checksum AS source_current,r.status AS reconciliation_status,
            false AS admission_enabled,false AS correction_exposure_enabled,
            greatest(g.max_attempts-b.used_count,0) AS reserved_remaining
          FROM t11b3_account_attempt_budgets b JOIN t11b3_opportunity_groups g ON g.id=b.group_id
          JOIN t11b3_reconciliations r ON r.id=g.reconciliation_id;

        EXECUTE $ddl$
        CREATE FUNCTION t11b3_apply(entry bigint,actor bigint DEFAULT NULL) RETURNS text LANGUAGE plpgsql AS $fn$
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
          src:=t11b3_snapshot(r.source_kind,r.source_pk)||jsonb_build_object('map_context',t11b3_map_snapshot(r.proposed));
          IF md5(src::text)<>r.source_checksum THEN
            IF r.status IN ('APPROVED','APPLIED','CONFLICT') THEN UPDATE t11b3_reconciliations SET status='STALE',changed_by=actor WHERE id=entry; END IF;
            RETURN 'STALE';
          END IF;
          IF r.status IN ('APPLIED','CONFLICT','STALE') THEN RETURN r.status; END IF;
          IF r.status<>'APPROVED' THEN RAISE EXCEPTION 'Explicit fixture approval required'; END IF;
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
    successor:=actual;
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
    IF installed THEN
      IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'T11-B3 schema divergence; no repair attempted'; END IF;
    ELSE
      INSERT INTO t11b3_schema_version(version,revision,predecessor_fingerprint,b2_successor_fingerprint,fingerprint) VALUES(1,'t11b3-v1',predecessor,successor,actual);
    END IF;
END $migration$;

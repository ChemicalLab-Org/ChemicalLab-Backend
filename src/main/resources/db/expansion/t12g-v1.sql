-- Fixture-only authoring successor. Certify t12f-v1.sql before installing this file.
DO $t12g$
DECLARE ctl text := 't12g_'||substr(current_schema(),5);
        execution text := 't12f_'||substr(current_schema(),5);
        predecessor text; actual text; expected text; installed boolean;
BEGIN
 PERFORM t11b5_fixture();
 PERFORM pg_advisory_xact_lock(110011,1);
 EXECUTE format('SELECT fingerprint FROM %I.schema_version WHERE version=1 AND revision=''t12f-v1''',execution)
   INTO STRICT predecessor;
 installed := to_regnamespace(ctl) IS NOT NULL;
 IF NOT installed THEN
  EXECUTE format('CREATE SCHEMA %I',ctl);
  EXECUTE replace(replace($ddl$
   CREATE TABLE @g@.schema_version(
    version integer PRIMARY KEY CHECK(version=1), revision text NOT NULL,
    predecessor_fingerprint text NOT NULL, fingerprint text NOT NULL);
   CREATE TABLE @g@.grants(
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    resource_id bigint NOT NULL, institution_id bigint NOT NULL, membership_id bigint NOT NULL,
    faculty text NOT NULL CHECK(faculty IN ('EDIT_DRAFT','PUBLISH','ASSIGN')),
    valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL, granted_at timestamptz NOT NULL,
    granted_by bigint NOT NULL REFERENCES user_accounts(id), grantor_membership_id bigint NOT NULL,
    revoked_at timestamptz, revoked_by bigint REFERENCES user_accounts(id), revoker_membership_id bigint,
    status text NOT NULL DEFAULT 'ACTIVE', reason text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    FOREIGN KEY(resource_id,institution_id) REFERENCES t11b8_resources(id,institution_id),
    FOREIGN KEY(membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
    FOREIGN KEY(grantor_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
    FOREIGN KEY(revoker_membership_id,institution_id) REFERENCES institution_memberships(id,institution_id),
    CHECK(isfinite(granted_at) AND isfinite(valid_from) AND isfinite(valid_until) AND granted_at<=valid_from AND valid_from<valid_until),
    CHECK((status='ACTIVE' AND num_nonnulls(revoked_at,revoked_by,revoker_membership_id)=0 AND reason='DESIGNATED')
       OR (status='REVOKED' AND num_nonnulls(revoked_at,revoked_by,revoker_membership_id)=3
           AND isfinite(revoked_at) AND revoked_at>=granted_at AND reason='REMOVED')),
    EXCLUDE USING gist(resource_id WITH =,membership_id WITH =,faculty WITH =,
      (tstzrange(valid_from,greatest(valid_from,least(valid_until,revoked_at)),'[)')) WITH &&));
   CREATE TABLE @g@.grant_history(
    grant_id bigint NOT NULL REFERENCES @g@.grants(id), event text NOT NULL CHECK(event IN ('INSERT','UPDATE')),
    snapshot jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(), PRIMARY KEY(grant_id,event));
   CREATE TABLE @g@.receipts(
    ordinal bigint GENERATED ALWAYS AS IDENTITY UNIQUE, operation_id uuid PRIMARY KEY,
    action text NOT NULL CHECK(action IN ('CREATE','EDIT','PUBLISH','SUCCESSOR')),
    actor_id bigint NOT NULL REFERENCES user_accounts(id), institution_id bigint NOT NULL,
    class_id bigint NOT NULL, evaluation_id bigint NOT NULL REFERENCES evaluations(id),
    resource_id bigint NOT NULL, revision_id bigint NOT NULL, request jsonb NOT NULL, result jsonb NOT NULL,
    root_version bigint REFERENCES t11b10_versions(id),
    before_hash text NOT NULL, after_hash text NOT NULL, heads_hash text NOT NULL, references_hash text,
    audit_id bigint NOT NULL UNIQUE REFERENCES system_logs(id),
    xid text NOT NULL DEFAULT pg_current_xact_id()::text, recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id),
    FOREIGN KEY(revision_id,resource_id,institution_id) REFERENCES t11b8_revisions(id,resource_id,institution_id),
    CHECK((root_version IS NULL)=(references_hash IS NULL)));
   CREATE INDEX receipt_evaluation ON @g@.receipts(evaluation_id,ordinal DESC);
   CREATE UNIQUE INDEX creation_per_evaluation ON @g@.receipts(evaluation_id) WHERE action='CREATE';
   CREATE VIEW @g@.receipt_commands AS
    SELECT NULL::uuid operation_id, NULL::text action, NULL::bigint actor_id, NULL::bigint institution_id,
      NULL::bigint class_id, NULL::bigint evaluation_id, NULL::bigint resource_id, NULL::bigint revision_id,
      NULL::bigint root_version, NULL::jsonb before_manifest, NULL::jsonb request, NULL::jsonb result,
      NULL::bigint audit_id WHERE false;

   CREATE FUNCTION @g@.immutable() RETURNS trigger LANGUAGE plpgsql AS $fn$
   BEGIN RAISE EXCEPTION 'T12-G immutable history';END $fn$;
   CREATE FUNCTION @g@.fixture_write() RETURNS trigger LANGUAGE plpgsql AS $fn$
   BEGIN
    PERFORM t11b5_fixture();
    LOCK TABLE evaluations IN EXCLUSIVE MODE;
    RETURN NULL;
   END $fn$;
   CREATE FUNCTION @g@.grant_target() RETURNS trigger LANGUAGE plpgsql AS $fn$
   BEGIN
    IF NOT EXISTS(SELECT 1 FROM t11b8_resources WHERE id=NEW.resource_id AND institution_id=NEW.institution_id
      AND evaluation_id IS NOT NULL AND creation='EXPLICIT_NEW') THEN RAISE EXCEPTION 'T12-G exact NEW grant required';END IF;
    RETURN NEW;
   END $fn$;
   CREATE FUNCTION @g@.grant_event() RETURNS trigger LANGUAGE plpgsql AS $fn$
   BEGIN
    INSERT INTO @g@.grant_history(grant_id,event,snapshot) VALUES(NEW.id,TG_OP,to_jsonb(NEW));
    PERFORM @g@.control_quota();
    RETURN NEW;
   END $fn$;
   CREATE FUNCTION @g@.history_insert() RETURNS trigger LANGUAGE plpgsql AS $fn$
   BEGIN
    IF pg_trigger_depth()<>2 THEN RAISE EXCEPTION 'T12-G generated history only'; END IF;
    RETURN NEW;
   END $fn$;
   CREATE TRIGGER fixture_write BEFORE INSERT OR UPDATE ON @g@.grants FOR EACH STATEMENT EXECUTE FUNCTION @g@.fixture_write();
   CREATE TRIGGER grant_guard BEFORE INSERT OR UPDATE OR DELETE ON @g@.grants FOR EACH ROW EXECUTE FUNCTION t11b6_grant_guard();
   CREATE TRIGGER grant_target BEFORE INSERT OR UPDATE ON @g@.grants FOR EACH ROW EXECUTE FUNCTION @g@.grant_target();
   CREATE TRIGGER grant_event AFTER INSERT OR UPDATE ON @g@.grants FOR EACH ROW EXECUTE FUNCTION @g@.grant_event();
   CREATE TRIGGER generated BEFORE INSERT ON @g@.grant_history FOR EACH ROW EXECUTE FUNCTION @g@.history_insert();
   CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON @g@.grant_history FOR EACH ROW EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER generated BEFORE INSERT ON @g@.receipts FOR EACH ROW EXECUTE FUNCTION @g@.history_insert();
   CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON @g@.receipts FOR EACH ROW EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON @g@.schema_version FOR EACH ROW EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER no_truncate BEFORE TRUNCATE ON @g@.grant_history FOR EACH STATEMENT EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER no_truncate BEFORE TRUNCATE ON @g@.grants FOR EACH STATEMENT EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER no_truncate BEFORE TRUNCATE ON @g@.receipts FOR EACH STATEMENT EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER no_truncate BEFORE TRUNCATE ON @g@.schema_version FOR EACH STATEMENT EXECUTE FUNCTION @g@.immutable();

   CREATE FUNCTION @g@.control_quota() RETURNS void LANGUAGE plpgsql AS $fn$
   BEGIN
    IF (SELECT count(*)>10000 OR coalesce(sum(bytes),0)>67108864 FROM (
      SELECT octet_length(to_jsonb(r)::text) bytes FROM @g@.receipts r
      UNION ALL SELECT octet_length(to_jsonb(g)::text) FROM @g@.grants g
      UNION ALL SELECT octet_length(to_jsonb(h)::text) FROM @g@.grant_history h) rows)
    THEN RAISE EXCEPTION 'T12-G control quota exceeded' USING ERRCODE='P7501';END IF;
   END $fn$;

   CREATE FUNCTION @g@.anchor(evaluation bigint) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $fn$
    SELECT jsonb_build_object('source','evaluations','pk',jsonb_build_object('id',evaluation))
   $fn$;
   CREATE FUNCTION @g@.digest(value jsonb) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $fn$
    SELECT encode(sha256(convert_to(value::text,'UTF8')),'hex')
   $fn$;
   CREATE FUNCTION @g@.fresh(entry bigint) RETURNS boolean LANGUAGE plpgsql STABLE AS $fn$
   DECLARE p t11b10_proposals%ROWTYPE; r @g@.receipts%ROWTYPE; a record; current_manifest jsonb;
   BEGIN
    SELECT * INTO STRICT p FROM t11b10_proposals WHERE id=entry;
    IF p.root->>'source'<>'evaluations' OR t11p05_status(entry) IS DISTINCT FROM 'APPLIED' THEN RETURN false;END IF;
    SELECT * INTO r FROM @g@.receipts WHERE evaluation_id=(p.root#>>'{pk,id}')::bigint ORDER BY ordinal DESC LIMIT 1;
    IF NOT FOUND OR r.root_version IS NULL OR NOT EXISTS(
      SELECT 1 FROM t11b10_versions v WHERE v.id=r.root_version AND v.proposal_id=entry
       AND v.source_table='evaluations' AND v.source_pk=p.root->'pk'
       AND v.resolution->>'scope'='INSTITUTION' AND v.resolution->'institutionId'=to_jsonb(r.institution_id)
       AND NOT EXISTS(SELECT 1 FROM t11b10_versions n WHERE n.predecessor_id=v.id)) THEN RETURN false;END IF;
    current_manifest:=t11p05_manifest(p.root);
    IF r.after_hash IS DISTINCT FROM @g@.digest(current_manifest)
       OR r.heads_hash IS DISTINCT FROM @g@.digest(t11p05_heads(current_manifest))
       OR r.references_hash IS DISTINCT FROM @g@.digest(t11p05_references(p.proposed,p.root))
       OR (SELECT count(*) FROM t11b10_approvals WHERE proposal_id=entry)<>2 THEN RETURN false;END IF;
    FOR a IN SELECT * FROM t11b10_approvals WHERE proposal_id=entry LOOP
     BEGIN
      IF a.privilege_snapshot IS DISTINCT FROM t11p05_privilege(a.role,a.account_id,a.privilege_id,p.institution_id) THEN RETURN false;END IF;
     EXCEPTION WHEN raise_exception THEN RETURN false;END;
    END LOOP;
    RETURN true;
   END $fn$;

   -- Frozen F remains the default. Only the older context predicate needs an adapter after G.
   CREATE FUNCTION @g@.begin_execution(op uuid, req jsonb, ctx jsonb) RETURNS void LANGUAGE plpgsql AS $fn$
   DECLARE p t11b10_proposals%ROWTYPE; v t11b10_versions%ROWTYPE; current_manifest jsonb;
   BEGIN
    PERFORM t11b5_fixture();
    SELECT * INTO STRICT v FROM t11b10_versions head WHERE source_table='evaluations'
      AND source_pk=jsonb_build_object('id',(req->>'evaluation')::bigint)
      AND NOT EXISTS(SELECT 1 FROM t11b10_versions n WHERE n.predecessor_id=head.id);
    IF NOT @g@.fresh(v.proposal_id) THEN
     PERFORM @f@.begin_operation(op,req,ctx);
     RETURN;
    END IF;
    IF NOT @f@.execution_fresh((req->>'evaluation')::bigint)
      OR v.resolution->>'scope'<>'INSTITUTION' OR v.resolution->'institutionId' IS DISTINCT FROM req->'institution'
      OR req->>'engine' IS DISTINCT FROM 'NEW_V1' OR ctx->>'enrollmentId' IS NULL
      OR ctx->'accountId' IS DISTINCT FROM req->'recipient' THEN RAISE EXCEPTION 'T12-F current authority required';END IF;
    IF EXISTS(SELECT 1 FROM t11b9_counters c FULL JOIN
      (SELECT scope,owner_id,category,sum(rows_used) rows,sum(bytes_used) bytes FROM t11b9_items GROUP BY 1,2,3) i USING(scope,owner_id,category)
      WHERE coalesce(c.rows_used,0)<>coalesce(i.rows,0) OR coalesce(c.bytes_used,0)<>coalesce(i.bytes,0))
      THEN RAISE EXCEPTION 'T12-F B9 counter divergence; no repair';END IF;
    SELECT * INTO STRICT p FROM t11b10_proposals WHERE id=v.proposal_id;
    current_manifest:=t11p05_manifest(p.root);
    INSERT INTO @f@.operations(operation_id,request,evaluation_id,root_version,context,before_base,before_execution,heads,references_snapshot)
     VALUES(op,req,(req->>'evaluation')::bigint,v.id,ctx,current_manifest,@f@.manifest((req->>'evaluation')::bigint),
        t11p05_heads(current_manifest),t11p05_references(p.proposed,p.root));
   END $fn$;

   CREATE FUNCTION @g@.store_receipt() RETURNS trigger LANGUAGE plpgsql AS $fn$
   DECLARE evaluation bigint:=NEW.evaluation_id; revision bigint:=NEW.revision_id;
     before_manifest jsonb:=NEW.before_manifest; after_manifest jsonb;
     before_digest text:=@g@.digest(NEW.before_manifest); refs text;
     p t11b10_proposals%ROWTYPE; prior @g@.receipts%ROWTYPE;
   BEGIN
    PERFORM t11b5_fixture();
    LOCK TABLE evaluations IN EXCLUSIVE MODE;
    IF NOT EXISTS(SELECT 1 FROM system_logs l WHERE l.id=NEW.audit_id AND l.correlation_id=NEW.operation_id
      AND l.actor_user_id=NEW.actor_id AND l.action=NEW.action||'_NEW_AUTHORING' AND l.outcome='SUCCESS'
      AND l.target_type='ContextResolution' AND l.target_id=evaluation
      AND l.xmin=pg_current_xact_id()::text::xid) THEN RAISE EXCEPTION 'T12-G mandatory audit';END IF;
    IF NOT EXISTS(SELECT 1 FROM t11b8_resources s JOIN t11b8_revisions v ON v.resource_id=s.id
      WHERE s.id=NEW.resource_id AND s.evaluation_id=evaluation AND s.institution_id=NEW.institution_id AND s.creation='EXPLICIT_NEW'
      AND v.id=revision AND v.xmin=pg_current_xact_id()::text::xid)
     THEN RAISE EXCEPTION 'T12-G one atomic revision required';END IF;
    SELECT * INTO prior FROM @g@.receipts WHERE evaluation_id=evaluation ORDER BY ordinal DESC LIMIT 1;
    after_manifest:=t11p05_manifest(@g@.anchor(evaluation));
    IF NEW.action='CREATE' THEN
     IF before_manifest IS DISTINCT FROM '[]'::jsonb OR NEW.root_version IS NOT NULL OR prior.operation_id IS NOT NULL OR NOT EXISTS(
      SELECT 1 FROM t11b8_creations c WHERE c.operation_id=NEW.operation_id AND c.resource_id=NEW.resource_id AND c.author_account_id=NEW.actor_id
       AND c.xmin=pg_current_xact_id()::text::xid)
      THEN RAISE EXCEPTION 'T12-G explicit native creation required';END IF;
    ELSIF (SELECT coalesce(jsonb_agg(x ORDER BY ord),'[]') FROM jsonb_array_elements(before_manifest) WITH ORDINALITY m(x,ord)
       WHERE NOT ((x->>'source_table'='t11b8_revisions' AND x->'source_pk'=jsonb_build_object('id',revision))
            OR (x->>'source_table'='t11b8_events' AND x#>>'{snapshot,entity_table}'='t11b8_revisions' AND x#>'{snapshot,entity_id}'=to_jsonb(revision))))
      IS DISTINCT FROM (SELECT coalesce(jsonb_agg(x ORDER BY ord),'[]') FROM jsonb_array_elements(after_manifest) WITH ORDINALITY m(x,ord)
       WHERE NOT ((x->>'source_table'='t11b8_revisions' AND x->'source_pk'=jsonb_build_object('id',revision))
            OR (x->>'source_table'='t11b8_events' AND x#>>'{snapshot,entity_table}'='t11b8_revisions' AND x#>'{snapshot,entity_id}'=to_jsonb(revision))))
      THEN RAISE EXCEPTION 'T12-G unrelated authority changed';END IF;
    IF NEW.root_version IS NOT NULL THEN
     SELECT proposal.* INTO STRICT p FROM t11b10_proposals proposal JOIN t11b10_versions v ON v.proposal_id=proposal.id
      WHERE v.id=NEW.root_version AND v.source_table='evaluations' AND v.source_pk=jsonb_build_object('id',evaluation)
       AND v.resolution->>'scope'='INSTITUTION' AND v.resolution->'institutionId'=to_jsonb(NEW.institution_id)
       AND NOT EXISTS(SELECT 1 FROM t11b10_versions n WHERE n.predecessor_id=v.id);
     refs:=@g@.digest(t11p05_references(p.proposed,p.root));
     IF t11p05_status(p.id) IS DISTINCT FROM 'APPLIED' OR (
       (p.manifest=before_manifest AND refs=@g@.digest(p.references_snapshot))
       OR (prior.root_version=NEW.root_version AND prior.after_hash=before_digest AND prior.references_hash=refs
         AND prior.heads_hash=@g@.digest(t11p05_heads(before_manifest)))
     ) IS NOT TRUE THEN RAISE EXCEPTION 'T12-G verified predecessor required';END IF;
    ELSIF NEW.action<>'CREATE' AND (
      prior.operation_id IS NULL OR prior.root_version IS NOT NULL OR prior.after_hash IS DISTINCT FROM before_digest
      OR EXISTS(SELECT 1 FROM t11b10_versions WHERE source_table='evaluations' AND source_pk=jsonb_build_object('id',evaluation))
    ) THEN RAISE EXCEPTION 'T12-G verified native predecessor required';
    END IF;
    INSERT INTO @g@.receipts(operation_id,action,actor_id,institution_id,class_id,evaluation_id,resource_id,revision_id,
      root_version,before_hash,after_hash,heads_hash,references_hash,request,result,audit_id)
     VALUES(NEW.operation_id,NEW.action,NEW.actor_id,NEW.institution_id,NEW.class_id,evaluation,NEW.resource_id,revision,NEW.root_version,
      before_digest,@g@.digest(after_manifest),@g@.digest(t11p05_heads(after_manifest)),refs,NEW.request,NEW.result,NEW.audit_id);
    PERFORM @f@.check_quotas();
    PERFORM @g@.control_quota();
    RETURN NEW;
   END $fn$;
   CREATE TRIGGER receipt_command INSTEAD OF INSERT ON @g@.receipt_commands
    FOR EACH ROW EXECUTE FUNCTION @g@.store_receipt();

   CREATE FUNCTION @g@.record_receipt(op uuid, kind text, actor bigint, institution bigint, clazz bigint, evaluation bigint,
      resource bigint, revision bigint, anchor_version bigint, before_manifest jsonb, input jsonb, receipt jsonb, audit bigint)
    RETURNS void LANGUAGE sql AS $fn$
    INSERT INTO @g@.receipt_commands VALUES(op,kind,actor,institution,clazz,evaluation,resource,revision,anchor_version,
      before_manifest,input,receipt,audit)
   $fn$;
  $ddl$,'@g@',ctl),'@f@',execution);
 END IF;
 SELECT md5(string_agg(s,E'\n' ORDER BY s)) INTO actual FROM (
  SELECT 'c:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||a.attidentity::text||':'||coalesce(pg_get_expr(d.adbin,d.adrelid),'') s
   FROM pg_class c JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum WHERE c.relnamespace=ctl::regnamespace AND a.attnum>0 AND NOT a.attisdropped
  UNION ALL SELECT 'f:'||pg_get_functiondef(oid) FROM pg_proc WHERE pronamespace=ctl::regnamespace
  UNION ALL SELECT 'k:'||r.relname||':'||c.conname||':'||t11p05_source_constraint(pg_get_constraintdef(c.oid))||':'||c.convalidated FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid WHERE r.relnamespace=ctl::regnamespace
  UNION ALL SELECT 'i:'||indexdef FROM pg_indexes WHERE schemaname=ctl
  UNION ALL SELECT 'r:'||relname||':'||relkind::text FROM pg_class WHERE relnamespace=ctl::regnamespace
  UNION ALL SELECT 'v:'||c.relname||':'||pg_get_viewdef(c.oid) FROM pg_class c WHERE c.relnamespace=ctl::regnamespace AND c.relkind='v'
  UNION ALL SELECT 'j:'||i.relname||':'||x.indisvalid||':'||x.indisready FROM pg_index x JOIN pg_class r ON r.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid WHERE r.relnamespace=ctl::regnamespace
  UNION ALL SELECT 't:'||pg_get_triggerdef(t.oid)||':'||t.tgenabled::text FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relnamespace=ctl::regnamespace AND NOT t.tgisinternal
  UNION ALL SELECT 's:'||c.relname||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmax||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid WHERE c.relnamespace=ctl::regnamespace
 ) defs;
 IF installed THEN
  EXECUTE format('SELECT fingerprint FROM %I.schema_version WHERE version=1 AND revision=''t12g-v1'' AND predecessor_fingerprint=$1',ctl)
   INTO STRICT expected USING predecessor;
  IF expected IS DISTINCT FROM actual THEN RAISE EXCEPTION 'T12-G divergence; no repair';END IF;
 ELSE
  EXECUTE format('INSERT INTO %I.schema_version VALUES(1,''t12g-v1'',$1,$2)',ctl) USING predecessor,actual;
 END IF;
END $t12g$;

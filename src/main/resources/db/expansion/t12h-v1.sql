-- Fixture-only successor. Certify t12g-v1.sql in this schema before applying H.
DO $t12h$
DECLARE ctl text := 't12h_'||substr(current_schema(),5);
        authoring text := 't12g_'||substr(current_schema(),5);
        execution text := 't12f_'||substr(current_schema(),5);
        predecessor text; actual text; expected text; installed boolean;
BEGIN
 PERFORM t11b5_fixture();
 PERFORM pg_advisory_xact_lock(110011,1);
 EXECUTE format('SELECT fingerprint FROM %I.schema_version WHERE version=1 AND revision=''t12g-v1''',authoring)
   INTO STRICT predecessor;
 installed := to_regnamespace(ctl) IS NOT NULL;
 IF NOT installed THEN
  EXECUTE format('CREATE SCHEMA %I',ctl);
  EXECUTE replace(replace(replace($ddl$
   CREATE TABLE @h@.schema_version(
    version integer PRIMARY KEY CHECK(version=1), revision text NOT NULL,
    predecessor_fingerprint text NOT NULL, fingerprint text NOT NULL);
   CREATE TABLE @h@.receipts(
    ordinal bigint GENERATED ALWAYS AS IDENTITY UNIQUE, operation_id uuid PRIMARY KEY,
    action text NOT NULL CHECK(action IN ('ASSIGN','EDIT','PUBLISH','SUCCESSOR')),
    actor_id bigint NOT NULL REFERENCES user_accounts(id), institution_id bigint NOT NULL, class_id bigint NOT NULL,
    evaluation_id bigint NOT NULL REFERENCES evaluations(id), resource_id bigint NOT NULL, revision_id bigint NOT NULL,
    delivery_id bigint UNIQUE REFERENCES t11b8_deliveries(id),
    before_stamp jsonb NOT NULL, after_stamp jsonb NOT NULL, request jsonb NOT NULL, result jsonb NOT NULL,
    audit_id bigint NOT NULL UNIQUE REFERENCES system_logs(id),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    FOREIGN KEY(class_id,institution_id) REFERENCES academic_classes(id,institution_id),
    FOREIGN KEY(revision_id,resource_id,institution_id) REFERENCES t11b8_revisions(id,resource_id,institution_id),
    CHECK((action='ASSIGN')=(delivery_id IS NOT NULL)));
   CREATE INDEX receipt_evaluation ON @h@.receipts(evaluation_id,ordinal DESC);
   CREATE VIEW @h@.receipt_commands AS
    SELECT NULL::uuid operation_id,NULL::text action,NULL::bigint actor_id,NULL::bigint institution_id,
      NULL::bigint class_id,NULL::bigint evaluation_id,NULL::bigint resource_id,NULL::bigint revision_id,
      NULL::bigint delivery_id,NULL::jsonb before_manifest,NULL::jsonb request,NULL::jsonb result,
      NULL::bigint audit_id WHERE false;
   CREATE TRIGGER generated BEFORE INSERT ON @h@.receipts FOR EACH ROW EXECUTE FUNCTION @g@.history_insert();
   CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON @h@.receipts FOR EACH ROW EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON @h@.schema_version FOR EACH ROW EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER no_truncate BEFORE TRUNCATE ON @h@.receipts FOR EACH STATEMENT EXECUTE FUNCTION @g@.immutable();
   CREATE TRIGGER no_truncate BEFORE TRUNCATE ON @h@.schema_version FOR EACH STATEMENT EXECUTE FUNCTION @g@.immutable();

   CREATE FUNCTION @h@.root_head(evaluation bigint) RETURNS t11b10_versions LANGUAGE sql STABLE AS $fn$
    SELECT v.* FROM t11b10_versions v WHERE v.source_table='evaluations'
     AND v.source_pk=jsonb_build_object('id',evaluation)
     AND NOT EXISTS(SELECT 1 FROM t11b10_versions n WHERE n.predecessor_id=v.id)
   $fn$;

   -- The supplied manifest can be the pre-write image. Heads and references always come from the live database.
   CREATE FUNCTION @h@.stamp(evaluation bigint,institution bigint,manifest jsonb) RETURNS jsonb LANGUAGE plpgsql STABLE AS $fn$
   DECLARE v t11b10_versions%ROWTYPE; p t11b10_proposals%ROWTYPE; approval record; refs text;
   BEGIN
    v:=@h@.root_head(evaluation);
    IF v.id IS NOT NULL THEN
     IF v.resolution->>'scope' IS DISTINCT FROM 'INSTITUTION'
       OR v.resolution->'institutionId' IS DISTINCT FROM to_jsonb(institution)
       OR t11p05_status(v.proposal_id) IS DISTINCT FROM 'APPLIED'
       OR (SELECT count(DISTINCT account_id) FROM t11b10_approvals WHERE proposal_id=v.proposal_id)<>2 THEN RETURN NULL;END IF;
     SELECT * INTO STRICT p FROM t11b10_proposals WHERE id=v.proposal_id;
     FOR approval IN SELECT * FROM t11b10_approvals WHERE proposal_id=p.id LOOP
      BEGIN
       IF approval.privilege_snapshot IS DISTINCT FROM
           t11p05_privilege(approval.role,approval.account_id,approval.privilege_id,p.institution_id)
        THEN RETURN NULL;END IF;
      EXCEPTION WHEN raise_exception THEN RETURN NULL;END;
     END LOOP;
     refs:=@g@.digest(t11p05_references(p.proposed,p.root));
    END IF;
    RETURN jsonb_build_object('rootVersion',v.id,'manifest',@g@.digest(manifest),
      'heads',@g@.digest(t11p05_heads(manifest)),'references',refs);
   END $fn$;

   CREATE FUNCTION @h@.predecessor(evaluation bigint,institution bigint,manifest jsonb,stamp jsonb,base_allowed boolean)
    RETURNS boolean LANGUAGE plpgsql STABLE AS $fn$
   DECLARE v t11b10_versions%ROWTYPE; p t11b10_proposals%ROWTYPE; g @g@.receipts%ROWTYPE; h jsonb;
   BEGIN
    IF stamp IS NULL THEN RETURN false;END IF;
    SELECT after_stamp INTO h FROM @h@.receipts
     WHERE evaluation_id=evaluation AND institution_id=institution ORDER BY ordinal DESC LIMIT 1;
    IF h=stamp THEN RETURN true;END IF;
    IF NOT base_allowed THEN RETURN false;END IF;
    v:=@h@.root_head(evaluation);
    IF v.id IS NOT NULL THEN
     SELECT * INTO STRICT p FROM t11b10_proposals WHERE id=v.proposal_id;
     IF p.manifest=manifest AND @g@.digest(p.references_snapshot)=stamp->>'references'
       AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(t11p05_heads(manifest)) x
        WHERE NOT EXISTS(SELECT 1 FROM t11b10_versions current WHERE current.id=(x->>'id')::bigint
                         AND current.proposal_id=p.id)) THEN RETURN true;END IF;
    ELSE
     IF NOT EXISTS(SELECT 1 FROM @g@.receipts WHERE evaluation_id=evaluation AND institution_id=institution AND action='CREATE')
       THEN RETURN false;END IF;
    END IF;
    SELECT * INTO g FROM @g@.receipts WHERE evaluation_id=evaluation AND institution_id=institution ORDER BY ordinal DESC LIMIT 1;
    RETURN FOUND AND g.root_version IS NOT DISTINCT FROM v.id AND g.after_hash=stamp->>'manifest'
       AND g.heads_hash=stamp->>'heads' AND g.references_hash IS NOT DISTINCT FROM stamp->>'references';
   END $fn$;

   CREATE FUNCTION @h@.current(evaluation bigint,institution bigint,base_allowed boolean) RETURNS boolean LANGUAGE plpgsql STABLE AS $fn$
   DECLARE manifest jsonb:=t11p05_manifest(@g@.anchor(evaluation));
   BEGIN
    RETURN @h@.predecessor(evaluation,institution,manifest,@h@.stamp(evaluation,institution,manifest),base_allowed);
   END $fn$;

   -- Only the old applied proposal can be kept current. No new resolution or head is minted here.
   CREATE FUNCTION @h@.fresh(entry bigint) RETURNS boolean LANGUAGE sql STABLE AS $fn$
    SELECT coalesce((SELECT @h@.current((p.root#>>'{pk,id}')::bigint,p.institution_id,false)
     FROM t11b10_proposals p JOIN LATERAL @h@.root_head((p.root#>>'{pk,id}')::bigint) v ON v.proposal_id=p.id
     WHERE p.id=entry AND p.root->>'source'='evaluations'),false)
   $fn$;

   CREATE FUNCTION @h@.eligible_enrollments(institution bigint,clazz bigint,ids jsonb,observed timestamptz)
    RETURNS TABLE(enrollment_id bigint,account_id bigint) LANGUAGE sql STABLE AS $fn$
    SELECT e.id,m.account_id FROM jsonb_array_elements_text(ids) x(id)
     JOIN enrollments e ON e.id=x.id::bigint AND e.institution_id=institution AND e.class_id=clazz
     JOIN institution_memberships m ON m.id=e.membership_id AND m.institution_id=e.institution_id
     WHERE e.status='ACTIVE' AND e.state_since<=observed AND e.activated_at<=observed
       AND e.valid_from<=observed AND observed<e.valid_until AND e.ended_at IS NULL
       AND t11b6_role_valid(m.id,institution,'ESTUDIANTE',observed,m.account_id)
   $fn$;

   CREATE FUNCTION @h@.store_receipt() RETURNS trigger LANGUAGE plpgsql AS $fn$
   DECLARE before_state jsonb:=NEW.before_manifest; after_state jsonb; prev jsonb; next jsonb;
     allowed jsonb; new_rows jsonb; expected integer; xid_now xid:=pg_current_xact_id()::text::xid;
     keys jsonb; audit_action text;
   BEGIN
    PERFORM t11b5_fixture();
    LOCK TABLE evaluations IN EXCLUSIVE MODE;
    audit_action:=CASE WHEN NEW.action='ASSIGN' THEN 'ASSIGN_NEW_DELIVERY' ELSE NEW.action||'_NEW_AUTHORING' END;
    IF NOT EXISTS(SELECT 1 FROM system_logs l WHERE l.id=NEW.audit_id AND l.correlation_id=NEW.operation_id
      AND l.actor_user_id=NEW.actor_id AND l.action=audit_action AND l.outcome='SUCCESS'
      AND l.target_type='ContextResolution' AND l.target_id=NEW.evaluation_id AND l.xmin=xid_now)
     THEN RAISE EXCEPTION 'T12-H mandatory audit';END IF;
    IF NOT EXISTS(SELECT 1 FROM t11b8_resources s JOIN t11b8_revisions v ON v.resource_id=s.id AND v.institution_id=s.institution_id
      WHERE s.id=NEW.resource_id AND s.evaluation_id=NEW.evaluation_id AND s.institution_id=NEW.institution_id
       AND s.creation='EXPLICIT_NEW' AND v.id=NEW.revision_id AND (NEW.action='ASSIGN' OR v.xmin=xid_now))
     THEN RAISE EXCEPTION 'T12-H exact NEW revision required';END IF;
    prev:=@h@.stamp(NEW.evaluation_id,NEW.institution_id,before_state);
    IF NOT @h@.predecessor(NEW.evaluation_id,NEW.institution_id,before_state,prev,NEW.action='ASSIGN')
     THEN RAISE EXCEPTION 'T12-H verified predecessor required';END IF;

    IF NEW.action='ASSIGN' THEN
     IF NOT EXISTS(SELECT 1 FROM t11b8_deliveries d WHERE d.id=NEW.delivery_id AND d.resource_id=NEW.resource_id
        AND d.institution_id=NEW.institution_id AND d.class_id=NEW.class_id AND d.revision_id=NEW.revision_id
        AND d.operation_id=NEW.operation_id AND d.xmin=xid_now)
      THEN RAISE EXCEPTION 'T12-H one atomic delivery required';END IF;
     SELECT jsonb_agg(jsonb_build_object('source_table',src,'source_pk',jsonb_build_object('id',id))) INTO keys FROM (
      SELECT 't11b8_deliveries' src,NEW.delivery_id id
      UNION ALL SELECT 't11b8_recipients',r.id FROM t11b8_recipients r WHERE r.delivery_id=NEW.delivery_id AND r.xmin=xid_now
     ) created;
     IF (SELECT jsonb_agg(r.enrollment_id ORDER BY r.enrollment_id) FROM t11b8_recipients r WHERE r.delivery_id=NEW.delivery_id)
         IS DISTINCT FROM NEW.request->'enrollmentIds'
      OR jsonb_array_length(keys)<>1+jsonb_array_length(NEW.request->'enrollmentIds')
      THEN RAISE EXCEPTION 'T12-H exact initial admissions required';END IF;
    ELSE
     keys:=jsonb_build_array(jsonb_build_object('source_table','t11b8_revisions','source_pk',jsonb_build_object('id',NEW.revision_id)));
    END IF;
    SELECT coalesce(jsonb_agg(jsonb_build_object('source_table','t11b8_events','source_pk',jsonb_build_object('id',e.id))),'[]')
       INTO new_rows
     FROM t11b8_events e JOIN jsonb_array_elements(keys) k
       ON e.entity_table=k->>'source_table' AND e.entity_id=(k#>>'{source_pk,id}')::bigint
     WHERE e.resource_id=NEW.resource_id AND e.xmin=xid_now;
    IF jsonb_array_length(new_rows)<>jsonb_array_length(keys) THEN RAISE EXCEPTION 'T12-H exact B8 history required';END IF;

    allowed:=keys||new_rows;
    after_state:=t11p05_manifest(@g@.anchor(NEW.evaluation_id));
    expected:=CASE WHEN NEW.action IN ('EDIT','PUBLISH') THEN 1 ELSE 0 END;
    IF (SELECT count(*) FROM jsonb_array_elements(before_state) x WHERE allowed @> jsonb_build_array(x-ARRAY['source_version','source_checksum','snapshot','classification','reason','root']))<>expected
      OR (SELECT count(*) FROM jsonb_array_elements(after_state) x WHERE allowed @> jsonb_build_array(x-ARRAY['source_version','source_checksum','snapshot','classification','reason','root']))<>jsonb_array_length(allowed)
      OR (SELECT coalesce(jsonb_agg(x ORDER BY ord),'[]') FROM jsonb_array_elements(before_state) WITH ORDINALITY m(x,ord)
          WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(allowed) k WHERE x @> k))
         IS DISTINCT FROM
         (SELECT coalesce(jsonb_agg(x ORDER BY ord),'[]') FROM jsonb_array_elements(after_state) WITH ORDINALITY m(x,ord)
          WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(allowed) k WHERE x @> k))
      OR EXISTS(SELECT 1 FROM jsonb_array_elements(allowed) k JOIN t11b10_versions v
               ON v.source_table=k->>'source_table' AND v.source_pk=k->'source_pk' WHERE NOT
                (NEW.action IN ('EDIT','PUBLISH') AND v.source_table='t11b8_revisions'))
     THEN RAISE EXCEPTION 'T12-H unrelated manifest or context change';END IF;
    next:=@h@.stamp(NEW.evaluation_id,NEW.institution_id,after_state);
    IF next IS NULL OR next-ARRAY['manifest','heads'] IS DISTINCT FROM prev-ARRAY['manifest','heads']
     THEN RAISE EXCEPTION 'T12-H authority changed';END IF;
    INSERT INTO @h@.receipts(operation_id,action,actor_id,institution_id,class_id,evaluation_id,resource_id,revision_id,
      delivery_id,before_stamp,after_stamp,request,result,audit_id)
     VALUES(NEW.operation_id,NEW.action,NEW.actor_id,NEW.institution_id,NEW.class_id,NEW.evaluation_id,NEW.resource_id,NEW.revision_id,
      NEW.delivery_id,prev,next,NEW.request,NEW.result,NEW.audit_id);
    PERFORM @f@.check_quotas();
    IF (SELECT count(*)>10000 OR coalesce(sum(octet_length(to_jsonb(r)::text)),0)>67108864 FROM @h@.receipts r)
     THEN RAISE EXCEPTION 'T12-H control quota exceeded' USING ERRCODE='P7501';END IF;
    RETURN NEW;
   END $fn$;
   CREATE TRIGGER receipt_command INSTEAD OF INSERT ON @h@.receipt_commands FOR EACH ROW EXECUTE FUNCTION @h@.store_receipt();

   CREATE FUNCTION @h@.assign(op uuid,req jsonb,audit bigint) RETURNS jsonb LANGUAGE plpgsql AS $fn$
   DECLARE evaluation bigint:=(req->>'evaluation')::bigint; institution bigint:=(req->>'institution')::bigint;
     clazz bigint:=(req->>'class')::bigint; revision bigint:=(req->>'revision')::bigint;
     resource bigint; d t11b8_deliveries%ROWTYPE; manifest jsonb; admission_count integer; accounts integer;
     ids jsonb:=req->'enrollmentIds'; observed timestamptz; result jsonb;
     starts timestamptz:=(req->>'startsAt')::timestamptz; due timestamptz:=(req->>'dueAt')::timestamptz;
   BEGIN
    PERFORM t11b5_fixture();
    LOCK TABLE evaluations IN EXCLUSIVE MODE;
    observed:=clock_timestamp();
    SELECT s.id INTO resource FROM t11b8_resources s JOIN t11b8_revisions v
      ON v.resource_id=s.id AND v.institution_id=s.institution_id
      WHERE s.evaluation_id=evaluation AND s.institution_id=institution AND s.creation='EXPLICIT_NEW'
       AND s.availability='AVAILABLE' AND v.id=revision AND v.status='PUBLISHED' AND v.version=(req->>'version')::bigint;
    IF resource IS NULL OR t11b8_closed(resource) OR NOT coalesce(isfinite(starts) AND isfinite(due)
       AND starts<due AND due>observed,false)
       OR EXISTS(SELECT 1 FROM t11b8_deliveries WHERE revision_id=revision AND class_id=clazz)
     THEN RAISE EXCEPTION 'T12-H incompatible publication or window' USING ERRCODE='P7809';END IF;
    SELECT count(*),count(DISTINCT account_id) INTO admission_count,accounts
     FROM @h@.eligible_enrollments(institution,clazz,ids,observed);
    IF admission_count NOT BETWEEN 1 AND 100 OR accounts<>admission_count OR admission_count<>jsonb_array_length(ids)
     THEN RAISE EXCEPTION 'T12-H invalid admission' USING ERRCODE='P7803';END IF;
    manifest:=t11p05_manifest(@g@.anchor(evaluation));
    IF NOT @h@.predecessor(evaluation,institution,manifest,@h@.stamp(evaluation,institution,manifest),true)
     THEN RAISE EXCEPTION 'T12-H unauthorized context' USING ERRCODE='P7803';END IF;
    INSERT INTO t11b8_deliveries(resource_id,institution_id,revision_id,class_id,starts_at,due_at,operation_id)
      VALUES(resource,institution,revision,clazz,starts,due,op) RETURNING * INTO d;
    INSERT INTO t11b8_recipients(delivery_id,resource_id,institution_id,account_id,enrollment_id)
     SELECT d.id,resource,institution,account_id,enrollment_id FROM @h@.eligible_enrollments(institution,clazz,ids,observed)
     ORDER BY enrollment_id;
    result:=jsonb_build_object('operationId',op,'evaluationId',evaluation,'revisionId',revision,'deliveryId',d.id,
     'version',d.version,'startsAt',d.starts_at,'dueAt',d.due_at,'recipientCount',admission_count);
    INSERT INTO @h@.receipt_commands VALUES(op,'ASSIGN',(req->>'actor')::bigint,institution,clazz,evaluation,resource,revision,
      d.id,manifest,req,result,audit);
    RETURN result;
   END $fn$;

   CREATE FUNCTION @h@.begin_execution(op uuid,req jsonb,ctx jsonb) RETURNS void LANGUAGE plpgsql AS $fn$
   DECLARE p t11b10_proposals%ROWTYPE; v t11b10_versions%ROWTYPE; current_manifest jsonb;
   BEGIN
    PERFORM t11b5_fixture();
    v:=@h@.root_head((req->>'evaluation')::bigint);
    IF v.id IS NULL OR NOT @h@.fresh(v.proposal_id) THEN
     PERFORM @g@.begin_execution(op,req,ctx);
     RETURN;
    END IF;
    IF NOT @f@.execution_fresh((req->>'evaluation')::bigint)
      OR v.resolution->'institutionId' IS DISTINCT FROM req->'institution'
      OR req->>'engine' IS DISTINCT FROM 'NEW_V1' OR ctx->>'enrollmentId' IS NULL
      OR ctx->'accountId' IS DISTINCT FROM req->'recipient'
      OR NOT EXISTS(SELECT 1 FROM t11b8_deliveries d JOIN t11b8_recipients r ON r.delivery_id=d.id
       JOIN t11b10_versions dv ON dv.source_table='t11b8_deliveries' AND dv.source_pk=jsonb_build_object('id',d.id)
       JOIN t11b10_versions rv ON rv.source_table='t11b8_recipients' AND rv.source_pk=jsonb_build_object('id',r.id)
       WHERE d.id=(req->>'delivery')::bigint AND d.revision_id=(req->>'revision')::bigint
        AND d.class_id=(req->>'class')::bigint AND d.institution_id=(req->>'institution')::bigint
        AND r.account_id=(req->>'recipient')::bigint AND r.enrollment_id=(ctx->>'enrollmentId')::bigint
        AND dv.proposal_id=v.proposal_id AND rv.proposal_id=v.proposal_id AND rv.resolution=ctx
        AND dv.resolution @> jsonb_build_object('scope','INSTITUTION','classId',d.class_id,'institutionId',d.institution_id)
        AND NOT EXISTS(SELECT 1 FROM t11b10_versions n WHERE n.predecessor_id IN (dv.id,rv.id)))
      THEN RAISE EXCEPTION 'T12-F current authority required';END IF;
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
  $ddl$,'@h@',ctl),'@g@',authoring),'@f@',execution);
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
  EXECUTE format('SELECT fingerprint FROM %I.schema_version WHERE version=1 AND revision=''t12h-v1'' AND predecessor_fingerprint=$1',ctl)
   INTO STRICT expected USING predecessor;
  IF expected IS DISTINCT FROM actual THEN RAISE EXCEPTION 'T12-H divergence; no repair';END IF;
 ELSE
  EXECUTE format('INSERT INTO %I.schema_version VALUES(1,''t12h-v1'',$1,$2)',ctl) USING predecessor,actual;
 END IF;
END $t12h$;

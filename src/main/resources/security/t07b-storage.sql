-- Run once, inside a transaction and with writers stopped. Never run automatically in prod.
-- Preserves existing over-quota data; seed counts include inactive stored rows.
LOCK TABLE concept_materials,whiteboard_sessions,whiteboard_object_ownership,usage_events,evaluation_attempt_events IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE storage_limits(resource varchar(40) PRIMARY KEY,maximum bigint NOT NULL CHECK(maximum>0));
INSERT INTO storage_limits VALUES ('MATERIAL_BYTES',268435456),('MATERIAL_COUNT',1000),
 ('SNAPSHOT_BYTES',268435456),('BOARD_BYTES',67108864),('BOARD_COUNT',500),
 ('USAGE_COUNT',100000),('ATTEMPT_TELEMETRY_COUNT',20000),('ATTEMPT_EVENT_COUNT',2000),('OWNERSHIP_COUNT',10000);
CREATE TABLE storage_usage(scope varchar(16) NOT NULL,owner_id bigint NOT NULL,resource varchar(40) NOT NULL REFERENCES storage_limits,
 used bigint NOT NULL CHECK(used>=0),PRIMARY KEY(scope,owner_id,resource));
CREATE TABLE technical_retention_state(id integer PRIMARY KEY CHECK(id=1),last_cutoff timestamp,last_deleted bigint NOT NULL DEFAULT 0);
INSERT INTO technical_retention_state(id) VALUES(1);
CREATE FUNCTION storage_delta(s varchar,o bigint,r varchar,d bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE cap bigint;
BEGIN
 IF d=0 THEN RETURN; END IF;
 SELECT maximum INTO STRICT cap FROM storage_limits WHERE resource=r FOR SHARE;
 INSERT INTO storage_usage VALUES(s,o,r,0) ON CONFLICT DO NOTHING;
 UPDATE storage_usage SET used=used+d WHERE scope=s AND owner_id=o AND resource=r
   AND used+d>=0 AND (d<=0 OR used+d<=cap);
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P7501',MESSAGE='STORAGE_QUOTA_EXCEEDED',DETAIL=r; END IF;
END $$;
CREATE FUNCTION storage_row_quota() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner bigint; old_bytes bigint:=0; new_bytes bigint:=0; old_count bigint:=0;new_count bigint:=0;
 record_old jsonb;record_new jsonb; row_data jsonb; count_resource varchar; byte_resource varchar; scope_name varchar:='ACCOUNT';
BEGIN
 IF TG_OP<>'INSERT' THEN old_count:=1; END IF;
 IF TG_OP<>'DELETE' THEN new_count:=1; END IF;
 -- Do not convert bytea to JSON: count octets directly, excluding metadata claims.
 IF TG_TABLE_NAME='concept_materials' THEN
   IF TG_OP<>'INSERT' THEN old_bytes:=coalesce(octet_length(OLD.file_data),0); END IF;
   IF TG_OP<>'DELETE' THEN new_bytes:=coalesce(octet_length(NEW.file_data),0); END IF;
   IF TG_OP='UPDATE' AND NEW.concept_content_id<>OLD.concept_content_id THEN RAISE EXCEPTION 'Material owner transfer is unsupported'; END IF;
   SELECT t.user_id INTO STRICT owner FROM concept_contents c JOIN teacher_profiles t ON t.id=c.created_by_teacher_id
     WHERE c.id=CASE WHEN TG_OP='DELETE' THEN OLD.concept_content_id ELSE NEW.concept_content_id END;
   IF TG_OP<>'DELETE' THEN NEW.file_size:=new_bytes; END IF;
   count_resource:='MATERIAL_COUNT';byte_resource:='MATERIAL_BYTES';
 ELSIF TG_TABLE_NAME='whiteboard_sessions' THEN
   IF TG_OP='UPDATE' AND NEW.teacher_id<>OLD.teacher_id THEN RAISE EXCEPTION 'Board owner transfer is unsupported'; END IF;
   SELECT user_id INTO STRICT owner FROM teacher_profiles WHERE id=CASE WHEN TG_OP='DELETE' THEN OLD.teacher_id ELSE NEW.teacher_id END;
   IF TG_OP<>'DELETE' THEN NEW.final_snapshot_size:=coalesce(octet_length(NEW.final_snapshot_data),0); END IF;
   count_resource:='BOARD_COUNT';byte_resource:='BOARD_BYTES';
   IF TG_OP<>'INSERT' THEN old_bytes:=coalesce(octet_length(OLD.current_state_json),0); END IF;
   IF TG_OP<>'DELETE' THEN new_bytes:=coalesce(octet_length(NEW.current_state_json),0); END IF;
   PERFORM storage_delta('ACCOUNT',owner,'SNAPSHOT_BYTES',
     CASE WHEN TG_OP='DELETE' THEN 0 ELSE coalesce(octet_length(NEW.final_snapshot_data),0) END-
     CASE WHEN TG_OP='INSERT' THEN 0 ELSE coalesce(octet_length(OLD.final_snapshot_data),0) END);
 ELSIF TG_TABLE_NAME='whiteboard_object_ownership' THEN
   IF TG_OP='UPDATE' AND (NEW.board_id<>OLD.board_id OR NEW.object_kind<>OLD.object_kind OR NEW.object_id<>OLD.object_id OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id) THEN RAISE EXCEPTION 'Ownership identity is immutable'; END IF;
   owner:=CASE WHEN TG_OP='DELETE' THEN OLD.board_id ELSE NEW.board_id END;
   scope_name:='BOARD';count_resource:='OWNERSHIP_COUNT';
 ELSIF TG_TABLE_NAME='usage_events' THEN
   IF TG_OP='UPDATE' AND NEW.user_id<>OLD.user_id THEN RAISE EXCEPTION 'Telemetry owner is immutable'; END IF;
   owner:=CASE WHEN TG_OP='DELETE' THEN OLD.user_id ELSE NEW.user_id END;
   count_resource:='USAGE_COUNT';
 ELSIF TG_TABLE_NAME='evaluation_attempt_events' THEN
   IF TG_OP='UPDATE' AND (NEW.attempt_id<>OLD.attempt_id OR NEW.event_type<>OLD.event_type) THEN RAISE EXCEPTION 'Attempt event is immutable'; END IF;
   -- Server lifecycle events never compete with optional client telemetry or grades.
   IF (TG_OP='DELETE' AND OLD.event_type IN ('ATTEMPT_STARTED','ATTEMPT_SUBMITTED','TIME_EXPIRED','ATTEMPT_EXITED'))
      OR (TG_OP<>'DELETE' AND NEW.event_type IN ('ATTEMPT_STARTED','ATTEMPT_SUBMITTED','TIME_EXPIRED','ATTEMPT_EXITED')) THEN RETURN coalesce(NEW,OLD); END IF;
   SELECT s.user_id INTO STRICT owner FROM evaluation_attempts a JOIN student_profiles s ON s.id=a.student_id
      WHERE a.id=CASE WHEN TG_OP='DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END;
   count_resource:='ATTEMPT_TELEMETRY_COUNT';
   PERFORM storage_delta('ATTEMPT',CASE WHEN TG_OP='DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END,'ATTEMPT_EVENT_COUNT',new_count-old_count);
 END IF;
 PERFORM storage_delta(scope_name,owner,count_resource,new_count-old_count);
 IF byte_resource IS NOT NULL THEN PERFORM storage_delta(scope_name,owner,byte_resource,new_bytes-old_bytes); END IF;
 RETURN coalesce(NEW,OLD);
END $$;
-- Seed from actual stored bytes, never file_size or final_snapshot_size.
UPDATE concept_materials SET file_size=coalesce(octet_length(file_data),0);
UPDATE whiteboard_sessions SET final_snapshot_size=coalesce(octet_length(final_snapshot_data),0);
INSERT INTO storage_usage SELECT 'ACCOUNT',t.user_id,'MATERIAL_BYTES',sum(coalesce(octet_length(m.file_data),0)) FROM concept_materials m JOIN concept_contents c ON c.id=m.concept_content_id JOIN teacher_profiles t ON t.id=c.created_by_teacher_id GROUP BY t.user_id;
INSERT INTO storage_usage SELECT 'ACCOUNT',t.user_id,'MATERIAL_COUNT',count(*) FROM concept_materials m JOIN concept_contents c ON c.id=m.concept_content_id JOIN teacher_profiles t ON t.id=c.created_by_teacher_id GROUP BY t.user_id;
INSERT INTO storage_usage SELECT 'ACCOUNT',t.user_id,'SNAPSHOT_BYTES',sum(coalesce(octet_length(b.final_snapshot_data),0)) FROM whiteboard_sessions b JOIN teacher_profiles t ON t.id=b.teacher_id GROUP BY t.user_id;
INSERT INTO storage_usage SELECT 'ACCOUNT',t.user_id,'BOARD_BYTES',sum(coalesce(octet_length(b.current_state_json),0)) FROM whiteboard_sessions b JOIN teacher_profiles t ON t.id=b.teacher_id GROUP BY t.user_id;
INSERT INTO storage_usage SELECT 'ACCOUNT',t.user_id,'BOARD_COUNT',count(*) FROM whiteboard_sessions b JOIN teacher_profiles t ON t.id=b.teacher_id GROUP BY t.user_id;
INSERT INTO storage_usage SELECT 'ACCOUNT',user_id,'USAGE_COUNT',count(*) FROM usage_events GROUP BY user_id;
INSERT INTO storage_usage SELECT 'BOARD',board_id,'OWNERSHIP_COUNT',count(*) FROM whiteboard_object_ownership GROUP BY board_id;
INSERT INTO storage_usage SELECT 'ACCOUNT',s.user_id,'ATTEMPT_TELEMETRY_COUNT',count(*) FROM evaluation_attempt_events e JOIN evaluation_attempts a ON a.id=e.attempt_id JOIN student_profiles s ON s.id=a.student_id WHERE e.event_type NOT IN ('ATTEMPT_STARTED','ATTEMPT_SUBMITTED','TIME_EXPIRED','ATTEMPT_EXITED') GROUP BY s.user_id;
INSERT INTO storage_usage SELECT 'ATTEMPT',attempt_id,'ATTEMPT_EVENT_COUNT',count(*) FROM evaluation_attempt_events WHERE event_type NOT IN ('ATTEMPT_STARTED','ATTEMPT_SUBMITTED','TIME_EXPIRED','ATTEMPT_EXITED') GROUP BY attempt_id;
CREATE TRIGGER storage_quota BEFORE INSERT OR UPDATE OR DELETE ON concept_materials FOR EACH ROW EXECUTE FUNCTION storage_row_quota();
CREATE TRIGGER storage_quota BEFORE INSERT OR UPDATE OR DELETE ON whiteboard_sessions FOR EACH ROW EXECUTE FUNCTION storage_row_quota();
CREATE TRIGGER storage_quota BEFORE INSERT OR UPDATE OR DELETE ON whiteboard_object_ownership FOR EACH ROW EXECUTE FUNCTION storage_row_quota();
CREATE TRIGGER storage_quota BEFORE INSERT OR UPDATE OR DELETE ON usage_events FOR EACH ROW EXECUTE FUNCTION storage_row_quota();
CREATE TRIGGER storage_quota BEFORE INSERT OR UPDATE OR DELETE ON evaluation_attempt_events FOR EACH ROW EXECUTE FUNCTION storage_row_quota();
-- Quota ownership follows these parent keys. Transfers are not part of the domain API.
CREATE FUNCTION storage_owner_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF to_jsonb(NEW)->TG_ARGV[0] IS DISTINCT FROM to_jsonb(OLD)->TG_ARGV[0] THEN
   RAISE EXCEPTION 'Storage parent owner transfer is unsupported';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER storage_owner_guard BEFORE UPDATE OF user_id ON teacher_profiles FOR EACH ROW EXECUTE FUNCTION storage_owner_immutable('user_id');
CREATE TRIGGER storage_owner_guard BEFORE UPDATE OF user_id ON student_profiles FOR EACH ROW EXECUTE FUNCTION storage_owner_immutable('user_id');
CREATE TRIGGER storage_owner_guard BEFORE UPDATE OF created_by_teacher_id ON concept_contents FOR EACH ROW EXECUTE FUNCTION storage_owner_immutable('created_by_teacher_id');
CREATE TRIGGER storage_owner_guard BEFORE UPDATE OF student_id ON evaluation_attempts FOR EACH ROW EXECUTE FUNCTION storage_owner_immutable('student_id');
CREATE INDEX idx_usage_user_time_id ON usage_events(user_id,occurred_at,id);
CREATE INDEX idx_usage_retention ON usage_events(occurred_at,id);
CREATE INDEX idx_logs_actor_time_id ON system_logs(actor_user_id,created_at,id);
CREATE INDEX idx_attempt_student_time ON evaluation_attempts(student_id,started_at,id);
CREATE INDEX idx_attempt_events_time ON evaluation_attempt_events(attempt_id,occurred_at,id);
CREATE INDEX idx_answer_attempt_dates ON evaluation_answers(attempt_id,answered_at,reviewed_at);
CREATE INDEX idx_assignment_group_active ON evaluation_assignments(trim(grade),upper(trim(section)),evaluation_id) WHERE active;
CREATE INDEX idx_concept_owner_time ON concept_contents(created_by_teacher_id,created_at,id);
CREATE INDEX idx_evaluation_owner_time ON evaluations(created_by_teacher_id,created_at,id);
CREATE INDEX idx_concept_assignment_resource ON concept_assignments(concept_content_id,assigned_at,id);
CREATE INDEX idx_evaluation_assignment_resource ON evaluation_assignments(evaluation_id,assigned_at,id);
CREATE INDEX idx_material_resource_active ON concept_materials(concept_content_id,id) WHERE active;
CREATE INDEX idx_student_teacher_name ON student_profiles(teacher_id,lower(names||' '||last_names),id);
CREATE INDEX idx_board_teacher_time ON whiteboard_sessions(teacher_id,created_at,id);
CREATE INDEX idx_board_group_time ON whiteboard_sessions(grade,section,status,created_at,id);
CREATE TABLE storage_schema_version(version integer PRIMARY KEY);
INSERT INTO storage_schema_version VALUES(1);

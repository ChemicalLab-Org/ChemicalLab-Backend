-- Optional local dataset, executed once before starting the normal server.
BEGIN;
DO $guard$
BEGIN
  IF current_database()<>'chemicallab_t12_runtime_test' OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
      OR EXISTS(SELECT 1 FROM user_accounts) OR EXISTS(SELECT 1 FROM evaluation_runtime.institutions) THEN
    RAISE EXCEPTION 'Seed only the empty disposable local database';
  END IF;
END $guard$;
CREATE EXTENSION pgcrypto;
INSERT INTO user_accounts(username,password,role,active,temporary_password,credentials_version,created_at)
SELECT username,crypt('FictitiousRuntime-2026!',gen_salt('bf',10)),role,true,false,0,statement_timestamp()
FROM (VALUES ('runtime-teacher-a','DOCENTE'),('runtime-teacher-b','DOCENTE'),('runtime-student','ESTUDIANTE')) s(username,role);

INSERT INTO teacher_profiles(user_id,names,last_names)
SELECT id,'Docente',right(username,1) FROM user_accounts WHERE role='DOCENTE';
INSERT INTO student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section)
SELECT u.id, t.id, 'FICTICIO1', 'Estudiante','Compartido','3','A'
FROM user_accounts u CROSS JOIN teacher_profiles t JOIN user_accounts a ON a.id=t.user_id
WHERE u.username='runtime-student' AND a.username='runtime-teacher-a';

INSERT INTO evaluation_runtime.institutions(name) VALUES ('Fictitious A'),('Fictitious B');
INSERT INTO evaluation_runtime.quotas(institution_id,max_rows,max_bytes)
  SELECT id,10000,67108864 FROM evaluation_runtime.institutions;
INSERT INTO evaluation_runtime.periods(institution_id,name,valid_from,valid_until)
  SELECT id,'Periodo ficticio',clock_timestamp()-interval '1 day',clock_timestamp()+interval '7 days'
  FROM evaluation_runtime.institutions;
INSERT INTO evaluation_runtime.classes(institution_id,period_id,code,name)
  SELECT institution_id,id,'CHEM','Fictitious' FROM evaluation_runtime.periods;
INSERT INTO evaluation_runtime.classes(institution_id,period_id,code,name)
  SELECT p.institution_id,p.id,'PHYS','Fictitious' FROM evaluation_runtime.periods p
  JOIN evaluation_runtime.institutions i ON i.id=p.institution_id AND i.name='Fictitious A';
INSERT INTO evaluation_runtime.memberships(institution_id,account_id,valid_from)
SELECT i.id,u.id,clock_timestamp()-interval '1 minute'
FROM user_accounts u CROSS JOIN evaluation_runtime.institutions i
WHERE u.username='runtime-student' OR (i.name='Fictitious A' AND u.username='runtime-teacher-a')
  OR (i.name='Fictitious B' AND u.username='runtime-teacher-b');
INSERT INTO evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
  SELECT m.id,m.institution_id,u.role,clock_timestamp()-interval '1 minute'
  FROM evaluation_runtime.memberships m JOIN user_accounts u ON u.id=m.account_id;
INSERT INTO evaluation_runtime.teaching(institution_id,class_id,membership_id,faculty,valid_from,valid_until)
  SELECT m.institution_id,c.id,m.id,'RESPONSABLE',clock_timestamp()-interval '1 minute',clock_timestamp()+interval '6 days'
  FROM evaluation_runtime.memberships m JOIN user_accounts u ON u.id=m.account_id
  JOIN evaluation_runtime.classes c ON c.institution_id=m.institution_id
  WHERE u.role='DOCENTE';
INSERT INTO evaluation_runtime.enrollments(institution_id,class_id,membership_id,account_id,valid_from,valid_until)
  SELECT m.institution_id,c.id,m.id,m.account_id,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '6 days'
  FROM evaluation_runtime.memberships m JOIN user_accounts u ON u.id=m.account_id
  JOIN evaluation_runtime.classes c ON c.institution_id=m.institution_id
  WHERE u.role='ESTUDIANTE';
COMMIT;

-- Fictitious display text and authorities for the isolated runtime journey only.
BEGIN;
DO $guard$
BEGIN
  IF current_database() <> 'chemicallab_t12_runtime_test'
     OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
     OR EXISTS (SELECT 1 FROM agreement_runtime.series) THEN
    RAISE EXCEPTION 'Seed only an empty disposable agreement schema on loopback';
  END IF;
END $guard$;

INSERT INTO agreement_runtime.series(code, kind, authority, required, public_read) VALUES
  ('terminos-demo', 'TERMS', 'SELF', true, true),
  ('aviso-demo', 'NOTICE', 'SELF', false, true);
INSERT INTO agreement_runtime.series(code, kind, institution_id, authority, required)
SELECT 'acuerdo-demo', 'INSTITUTIONAL', id, 'INSTITUTION', true FROM evaluation_runtime.institutions;
INSERT INTO agreement_runtime.series(code, kind, institution_id, authority, required)
SELECT 'finalidad-demo', 'CONSENT', id, 'VERIFIED', false FROM evaluation_runtime.institutions WHERE name = 'Fictitious A';

INSERT INTO agreement_runtime.versions(series_id, version, renewal, title, body, change_summary)
SELECT id, 1, 1, CASE kind WHEN 'TERMS' THEN 'Términos de ensayo' WHEN 'NOTICE' THEN 'Aviso de ensayo'
  WHEN 'CONSENT' THEN 'Finalidad opcional de ensayo' ELSE 'Acuerdo institucional de ensayo' END,
  'Texto ficticio para verificar decisiones en un entorno desechable.', 'Primera versión de ensayo.'
FROM agreement_runtime.series ORDER BY id;

INSERT INTO agreement_runtime.authorities(series_id, actor_id, subject_id, authority, result, valid_until, verified_by, evidence_reference)
SELECT s.id, a.id, NULL, 'INSTITUTION', 'VERIFIED', clock_timestamp() + interval '1 day', verifier.id, 'DEMO-DIRECTOR'
FROM agreement_runtime.series s JOIN evaluation_runtime.institutions i ON i.id = s.institution_id
JOIN user_accounts a ON a.username = CASE i.name WHEN 'Fictitious A' THEN 'runtime-teacher-a' ELSE 'runtime-teacher-b' END
JOIN user_accounts verifier ON verifier.username = 'runtime-student' WHERE s.kind = 'INSTITUTIONAL';

INSERT INTO agreement_runtime.authorities(series_id, actor_id, subject_id, authority, result, valid_until, verified_by, evidence_reference)
SELECT s.id, a.id, subject.id, 'GUARDIAN', 'VERIFIED', clock_timestamp() + interval '1 day', verifier.id, 'DEMO-FAMILY'
FROM agreement_runtime.series s CROSS JOIN user_accounts a CROSS JOIN user_accounts subject CROSS JOIN user_accounts verifier
WHERE s.kind = 'CONSENT' AND a.username = 'runtime-teacher-a' AND subject.username = 'runtime-student'
  AND verifier.username = 'runtime-teacher-b';
COMMIT;

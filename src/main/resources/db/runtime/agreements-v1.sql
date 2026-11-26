-- Apply once after administration-v1, with application writers stopped.
BEGIN;
SET LOCAL search_path TO public, pg_catalog;
CREATE SCHEMA agreement_runtime;
CREATE TABLE agreement_runtime.contract(version integer PRIMARY KEY CHECK (version = 1));
INSERT INTO agreement_runtime.contract VALUES (1);

CREATE TABLE agreement_runtime.series (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code text NOT NULL CHECK (code ~ '^[a-z][a-z0-9-]{0,63}$'),
  kind text NOT NULL CHECK (kind IN ('TERMS','NOTICE','CONSENT','INSTITUTIONAL')),
  institution_id bigint REFERENCES evaluation_runtime.institutions(id),
  authority text NOT NULL CHECK (authority IN ('SELF','VERIFIED','INSTITUTION')),
  required boolean NOT NULL,
  public_read boolean NOT NULL DEFAULT false,
  UNIQUE NULLS NOT DISTINCT (code, institution_id),
  CHECK ((kind = 'INSTITUTIONAL') = (authority = 'INSTITUTION')),
  CHECK (kind <> 'INSTITUTIONAL' OR institution_id IS NOT NULL),
  CHECK (kind NOT IN ('NOTICE','CONSENT') OR NOT required),
  CHECK (kind <> 'CONSENT' OR authority = 'VERIFIED'),
  CHECK (kind <> 'NOTICE' OR authority = 'SELF'),
  CHECK (NOT public_read OR institution_id IS NULL)
);

CREATE TABLE agreement_runtime.versions (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  series_id bigint NOT NULL REFERENCES agreement_runtime.series(id),
  version integer NOT NULL CHECK (version > 0),
  renewal integer NOT NULL CHECK (renewal > 0),
  title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 200),
  body text NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 100000),
  body_sha256 text NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
  change_summary text NOT NULL CHECK (char_length(btrim(change_summary)) BETWEEN 1 AND 1000),
  published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (series_id, version),
  UNIQUE (series_id, id)
);

CREATE FUNCTION agreement_runtime.publish_version() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE previous agreement_runtime.versions%ROWTYPE;
DECLARE document_kind text;
BEGIN
  SELECT kind INTO document_kind FROM agreement_runtime.series WHERE id = NEW.series_id FOR UPDATE;
  SELECT * INTO previous FROM agreement_runtime.versions WHERE series_id = NEW.series_id ORDER BY version DESC LIMIT 1;
  IF NEW.version <> coalesce(previous.version, 0) + 1
     OR (previous.id IS NULL AND NEW.renewal <> 1)
     OR (previous.id IS NOT NULL AND NEW.renewal NOT BETWEEN previous.renewal AND previous.renewal + 1)
     OR (document_kind = 'NOTICE' AND NEW.renewal <> 1) THEN
    RAISE EXCEPTION 'Invalid agreement version or renewal';
  END IF;
  NEW.body_sha256 := encode(sha256(convert_to(NEW.body, 'UTF8')), 'hex');
  NEW.published_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_publish BEFORE INSERT ON agreement_runtime.versions
  FOR EACH ROW EXECUTE FUNCTION agreement_runtime.publish_version();

-- Only a designated operator can append a reviewed authorization or revoke it.
-- The authorization is scoped to one document series and one represented party.
CREATE TABLE agreement_runtime.authorities (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  series_id bigint NOT NULL REFERENCES agreement_runtime.series(id),
  actor_id bigint NOT NULL REFERENCES user_accounts(id),
  subject_id bigint REFERENCES user_accounts(id),
  authority text NOT NULL CHECK (authority IN ('REVIEWED_SELF','GUARDIAN','INSTITUTION')),
  result text NOT NULL CHECK (result IN ('VERIFIED','DENIED','REVOKED')),
  valid_until timestamptz,
  verified_by bigint NOT NULL REFERENCES user_accounts(id),
  evidence_reference text NOT NULL CHECK (evidence_reference ~ '^[A-Z0-9][A-Z0-9_-]{5,63}$'),
  request_id uuid NOT NULL DEFAULT gen_random_uuid(),
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (verified_by, request_id),
  CHECK ((authority = 'INSTITUTION') = (subject_id IS NULL)),
  CHECK (authority <> 'REVIEWED_SELF' OR actor_id = subject_id),
  CHECK (authority <> 'GUARDIAN' OR actor_id <> subject_id),
  CHECK (verified_by <> actor_id),
  CHECK ((result = 'VERIFIED') = (valid_until IS NOT NULL)),
  CHECK (valid_until IS NULL OR valid_until > recorded_at)
);
CREATE INDEX agreement_authority_latest ON agreement_runtime.authorities(series_id, actor_id, subject_id, id DESC);

CREATE FUNCTION agreement_runtime.record_authority() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE series_authority text;
BEGIN
  SELECT authority INTO series_authority FROM agreement_runtime.series WHERE id = NEW.series_id FOR UPDATE;
  IF NOT ((series_authority = 'VERIFIED' AND NEW.authority IN ('REVIEWED_SELF','GUARDIAN'))
          OR (series_authority = 'INSTITUTION' AND NEW.authority = 'INSTITUTION')) THEN
    RAISE EXCEPTION 'Authority does not apply to document series';
  END IF;
  NEW.recorded_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_record_authority BEFORE INSERT ON agreement_runtime.authorities
  FOR EACH ROW EXECUTE FUNCTION agreement_runtime.record_authority();

CREATE TABLE agreement_runtime.decisions (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  series_id bigint NOT NULL,
  version_id bigint NOT NULL,
  actor_id bigint NOT NULL REFERENCES user_accounts(id),
  subject_id bigint REFERENCES user_accounts(id),
  authority_id bigint REFERENCES agreement_runtime.authorities(id),
  action text NOT NULL CHECK (action IN ('ACCEPT','ACKNOWLEDGE','GRANT','DECLINE','WITHDRAW')),
  request_id uuid NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY (series_id, version_id) REFERENCES agreement_runtime.versions(series_id, id),
  UNIQUE (actor_id, request_id)
);
CREATE INDEX agreement_decision_latest ON agreement_runtime.decisions(series_id, subject_id, id DESC);
CREATE INDEX agreement_decision_actor ON agreement_runtime.decisions(actor_id, id DESC);
CREATE INDEX agreement_decision_subject ON agreement_runtime.decisions(subject_id, id DESC);

-- A case identifies an explicitly applicable condition for student participation at one school.
CREATE TABLE agreement_runtime.family_cases (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  institution_id bigint NOT NULL,
  membership_id bigint NOT NULL,
  purpose text NOT NULL CHECK (purpose = 'ACADEMIC_PARTICIPATION'),
  result text NOT NULL CHECK (result IN ('PENDING','VERIFIED','DENIED','WITHDRAWN','NOT_REQUIRED')),
  basis text CHECK (basis IN ('PARENT_OR_GUARDIAN','CAPABLE_ADOLESCENT')),
  valid_until timestamptz,
  recorded_by bigint NOT NULL REFERENCES user_accounts(id),
  evidence_reference text NOT NULL CHECK (evidence_reference ~ '^[A-Z0-9][A-Z0-9_-]{5,63}$'),
  request_id uuid NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (recorded_by, request_id),
  FOREIGN KEY (membership_id, institution_id) REFERENCES evaluation_runtime.memberships(id, institution_id),
  CHECK ((result = 'VERIFIED') = (basis IS NOT NULL AND valid_until IS NOT NULL)),
  CHECK (result = 'VERIFIED' OR (basis IS NULL AND valid_until IS NULL)),
  CHECK (valid_until IS NULL OR valid_until > recorded_at)
);
CREATE INDEX agreement_family_latest ON agreement_runtime.family_cases(institution_id, membership_id, purpose, id DESC);
CREATE VIEW agreement_runtime.current_family_cases AS
  SELECT DISTINCT ON (institution_id, membership_id, purpose) * FROM agreement_runtime.family_cases
  ORDER BY institution_id, membership_id, purpose, id DESC;

-- PostgreSQL preserves the catalog and the evidence ledger as append-only tables.
CREATE FUNCTION agreement_runtime.reject_mutation() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
  RAISE EXCEPTION 'Agreement history is append-only';
END $$;
DO $immutable$
DECLARE relation text;
BEGIN
  FOREACH relation IN ARRAY ARRAY['series','versions','authorities','decisions','family_cases'] LOOP
    EXECUTE format('CREATE TRIGGER agreement_immutable BEFORE UPDATE OR DELETE ON agreement_runtime.%I FOR EACH ROW EXECUTE FUNCTION agreement_runtime.reject_mutation()', relation);
    EXECUTE format('CREATE TRIGGER agreement_no_truncate BEFORE TRUNCATE ON agreement_runtime.%I FOR EACH STATEMENT EXECUTE FUNCTION agreement_runtime.reject_mutation()', relation);
  END LOOP;
END $immutable$;

CREATE VIEW agreement_runtime.current_versions AS
  SELECT DISTINCT ON (series_id) * FROM agreement_runtime.versions ORDER BY series_id, version DESC;

CREATE VIEW agreement_runtime.current_authorities AS
  SELECT * FROM (
    SELECT DISTINCT ON (series_id, actor_id, subject_id) * FROM agreement_runtime.authorities
      ORDER BY series_id, actor_id, subject_id, id DESC
  ) latest WHERE result = 'VERIFIED' AND valid_until > statement_timestamp();

CREATE VIEW agreement_runtime.valid_memberships AS
  SELECT m.id, m.institution_id, m.account_id, i.active AS institution_active
  FROM evaluation_runtime.memberships m
  JOIN evaluation_runtime.institutions i ON i.id = m.institution_id
  WHERE m.active AND m.valid_from <= statement_timestamp()
    AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp());
CREATE VIEW agreement_runtime.active_memberships AS
  SELECT * FROM agreement_runtime.valid_memberships WHERE institution_active;

CREATE FUNCTION agreement_runtime.record_decision() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE series agreement_runtime.series%ROWTYPE;
BEGIN
  SELECT * INTO series FROM agreement_runtime.series WHERE id = NEW.series_id FOR UPDATE;
  IF NOT EXISTS (SELECT 1 FROM agreement_runtime.current_versions v
                 WHERE v.series_id = series.id AND v.id = NEW.version_id)
     OR (series.kind IN ('TERMS','INSTITUTIONAL') AND NEW.action <> 'ACCEPT')
     OR (series.kind = 'NOTICE' AND NEW.action <> 'ACKNOWLEDGE')
     OR (series.kind = 'CONSENT' AND NEW.action NOT IN ('GRANT','DECLINE','WITHDRAW'))
     OR ((series.authority = 'INSTITUTION') <> (NEW.subject_id IS NULL)) THEN
    RAISE EXCEPTION 'Decision does not match the current published document';
  END IF;
  IF (series.authority = 'SELF' AND (NEW.authority_id IS NOT NULL OR NEW.subject_id <> NEW.actor_id))
     OR (series.authority <> 'SELF' AND NOT EXISTS (
         SELECT 1 FROM agreement_runtime.current_authorities a
         WHERE a.id = NEW.authority_id AND a.series_id = NEW.series_id AND a.actor_id = NEW.actor_id
           AND a.subject_id IS NOT DISTINCT FROM NEW.subject_id)) THEN
    RAISE EXCEPTION 'Decision requires matching authority';
  END IF;
  IF series.institution_id IS NOT NULL THEN
    -- Keep scope membership stable until the evidence commits.
    PERFORM 1 FROM evaluation_runtime.memberships m
      JOIN evaluation_runtime.institutions i ON i.id = m.institution_id
      WHERE m.institution_id = series.institution_id AND m.account_id = coalesce(NEW.subject_id, NEW.actor_id)
        AND (i.active OR series.kind = 'INSTITUTIONAL') AND m.active AND m.valid_from <= statement_timestamp()
        AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp()) FOR SHARE OF m, i;
    IF NOT FOUND THEN RAISE EXCEPTION 'Decision requires active membership'; END IF;
  END IF;
  NEW.recorded_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_record_decision BEFORE INSERT ON agreement_runtime.decisions
  FOR EACH ROW EXECUTE FUNCTION agreement_runtime.record_decision();
COMMIT;

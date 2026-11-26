BEGIN;
CREATE SCHEMA subscription_runtime;
CREATE TABLE subscription_runtime.plan (
  version integer PRIMARY KEY CHECK (version > 0),
  name text NOT NULL, currency char(3) NOT NULL, monthly_amount numeric(12,2) NOT NULL CHECK (monthly_amount > 0),
  active_users integer NOT NULL CHECK (active_users > 0),
  storage_bytes bigint NOT NULL CHECK (storage_bytes > 0)
);
INSERT INTO subscription_runtime.plan VALUES (1,'ChemicalLab Institucional','PEN',99.00,200,2000000000);

CREATE TABLE subscription_runtime.school (
  institution_id bigint PRIMARY KEY REFERENCES evaluation_runtime.institutions,
  plan_version integer NOT NULL REFERENCES subscription_runtime.plan,
  verified_by bigint NOT NULL REFERENCES public.user_accounts,
  verification_ref text NOT NULL UNIQUE CHECK (verification_ref ~ '^[A-Z0-9][A-Z0-9_-]{5,63}$'),
  verified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  trial_start timestamptz,
  trial_end timestamptz,
  seats integer NOT NULL CHECK (seats >= 0),
  CHECK ((trial_start IS NULL AND trial_end IS NULL) OR (trial_end > trial_start AND trial_start IS NOT NULL))
);
CREATE TABLE subscription_runtime.authority (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  institution_id bigint NOT NULL REFERENCES subscription_runtime.school,
  account_id bigint NOT NULL REFERENCES public.user_accounts,
  granted_by bigint NOT NULL REFERENCES public.user_accounts CHECK (granted_by <> account_id),
  evidence_ref text NOT NULL CHECK (evidence_ref ~ '^[A-Z0-9][A-Z0-9_-]{5,63}$'),
  valid_until timestamptz,
  at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX subscription_authority_current ON subscription_runtime.authority(institution_id,account_id,id DESC);
CREATE VIEW subscription_runtime.current_authority AS
  SELECT * FROM (SELECT DISTINCT ON (institution_id,account_id) *
    FROM subscription_runtime.authority ORDER BY institution_id,account_id,id DESC) a
  WHERE valid_until > statement_timestamp();

CREATE TABLE subscription_runtime.contract (
  ref uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  institution_id bigint NOT NULL REFERENCES subscription_runtime.school,
  plan_version integer NOT NULL REFERENCES subscription_runtime.plan,
  actor_id bigint NOT NULL REFERENCES public.user_accounts,
  authority_id bigint NOT NULL REFERENCES subscription_runtime.authority,
  provider_mode text NOT NULL CHECK (provider_mode IN ('LOCAL_TEST','MERCADO_PAGO_TEST')),
  provider_id text UNIQUE,
  provider_status text,
  provider_modified timestamptz,
  checkout_url text,
  next_charge timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  sent_at timestamptz,
  cancel_requested_at timestamptz,
  closed_at timestamptz,
  reconcile_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  attempts integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX subscription_one_open_contract ON subscription_runtime.contract(institution_id) WHERE closed_at IS NULL;
CREATE INDEX subscription_reconcile ON subscription_runtime.contract(reconcile_at);
CREATE TABLE subscription_runtime.invoice (
  id text PRIMARY KEY, contract_ref uuid NOT NULL REFERENCES subscription_runtime.contract,
  payment_id text UNIQUE, status text NOT NULL,
  starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL CHECK (ends_at > starts_at),
  observed_version timestamptz NOT NULL, approved_at timestamptz
);
CREATE INDEX subscription_paid_period ON subscription_runtime.invoice(contract_ref,ends_at) WHERE status='APPROVED';
CREATE TABLE subscription_runtime.inbox (
  kind text NOT NULL, resource text NOT NULL, request_id text NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  attempts integer NOT NULL DEFAULT 0, processed_at timestamptz,
  PRIMARY KEY (kind,resource,request_id)
);
CREATE INDEX subscription_inbox_due ON subscription_runtime.inbox(available_at) WHERE processed_at IS NULL;
CREATE TABLE subscription_runtime.audit (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  institution_id bigint NOT NULL REFERENCES subscription_runtime.school,
  actor_id bigint REFERENCES public.user_accounts,
  contract_ref uuid REFERENCES subscription_runtime.contract,
  event text NOT NULL, outcome text, recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX subscription_audit_school ON subscription_runtime.audit(institution_id,id DESC);

CREATE FUNCTION subscription_runtime.immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Subscription evidence is immutable';
END $$;
DO $$
DECLARE relation text;
BEGIN
  FOREACH relation IN ARRAY ARRAY['plan','authority','audit'] LOOP
    EXECUTE format('CREATE TRIGGER subscription_immutable BEFORE UPDATE OR DELETE ON subscription_runtime.%I FOR EACH ROW EXECUTE FUNCTION subscription_runtime.immutable()',relation);
    EXECUTE format('CREATE TRIGGER subscription_no_truncate BEFORE TRUNCATE ON subscription_runtime.%I FOR EACH STATEMENT EXECUTE FUNCTION subscription_runtime.immutable()',relation);
  END LOOP;
END $$;

CREATE FUNCTION subscription_runtime.trial_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Subscription verification is permanent'; END IF;
  IF NEW.institution_id <> OLD.institution_id OR NEW.plan_version <> OLD.plan_version
      OR NEW.verified_at <> OLD.verified_at OR NEW.verified_by <> OLD.verified_by
      OR NEW.verification_ref <> OLD.verification_ref
      OR (OLD.trial_start IS NOT NULL AND (NEW.trial_start,NEW.trial_end) IS DISTINCT FROM (OLD.trial_start,OLD.trial_end)) THEN
    RAISE EXCEPTION 'Subscription verification and trial are immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER subscription_trial_guard BEFORE UPDATE OR DELETE ON subscription_runtime.school
  FOR EACH ROW EXECUTE FUNCTION subscription_runtime.trial_guard();
CREATE TRIGGER subscription_school_no_truncate BEFORE TRUNCATE ON subscription_runtime.school
  FOR EACH STATEMENT EXECUTE FUNCTION subscription_runtime.immutable();

CREATE FUNCTION subscription_runtime.start_trial(school_id bigint, enabled_at timestamptz)
RETURNS boolean LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
  UPDATE subscription_runtime.school SET trial_start=enabled_at,
    trial_end=((enabled_at AT TIME ZONE 'America/Lima') + interval '1 month') AT TIME ZONE 'America/Lima'
  WHERE institution_id=school_id AND trial_start IS NULL;
  IF NOT FOUND THEN RETURN false; END IF;
  INSERT INTO subscription_runtime.audit(institution_id,event) VALUES (school_id,'TRIAL_STARTED');
  RETURN true;
END $$;

CREATE FUNCTION subscription_runtime.activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.active AND NOT OLD.active THEN
    PERFORM subscription_runtime.start_trial(NEW.id, clock_timestamp());
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER subscription_activation AFTER UPDATE OF active ON evaluation_runtime.institutions
  FOR EACH ROW EXECUTE FUNCTION subscription_runtime.activation();

CREATE FUNCTION subscription_runtime.seat_delta() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE scope bigint; delta integer;
BEGIN
  IF TG_OP='UPDATE' AND (NEW.institution_id,NEW.account_id) IS DISTINCT FROM (OLD.institution_id,OLD.account_id) THEN
    RAISE EXCEPTION 'Membership identity is immutable';
  END IF;
  scope := CASE WHEN TG_OP='DELETE' THEN OLD.institution_id ELSE NEW.institution_id END;
  PERFORM pg_advisory_xact_lock(evaluation_runtime.institution_lock(scope));
  delta := CASE WHEN TG_OP <> 'DELETE' AND NEW.active THEN 1 ELSE 0 END
           - CASE WHEN TG_OP <> 'INSERT' AND OLD.active THEN 1 ELSE 0 END;
  IF delta <> 0 THEN
    UPDATE subscription_runtime.school s SET seats=s.seats+delta
    FROM subscription_runtime.plan p
    WHERE s.institution_id=scope AND p.version=s.plan_version
      AND (delta < 0 OR s.seats+delta <= p.active_users);
    IF NOT FOUND AND EXISTS (SELECT 1 FROM subscription_runtime.school WHERE institution_id=scope) THEN
      RAISE EXCEPTION 'Institution active user quota exceeded' USING ERRCODE='PCL01';
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER subscription_seats AFTER INSERT OR UPDATE OR DELETE ON evaluation_runtime.memberships
  FOR EACH ROW EXECUTE FUNCTION subscription_runtime.seat_delta();

COMMIT;

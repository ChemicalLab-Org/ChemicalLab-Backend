package com.morales.chemicallab.subscription;

import com.morales.chemicallab.agreement.AgreementService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionLedger implements InitializingBean {
    public record Plan(int version, String name, String currency, BigDecimal monthlyAmount, int activeUsers,
                       long storageBytes) {}
    public record School(long id, String name) {}
    public record Options(boolean enabled, List<School> schools, Long nextAfter) {}
    public record Overview(String mode, long institutionId, String name, boolean administrativeActive,
                           boolean canContract, boolean canCancel, Plan plan, int activeUsers, long usedBytes,
                           String status, OffsetDateTime trialStart, OffsetDateTime trialEnd,
                           OffsetDateTime paidFrom, OffsetDateTime paidUntil, OffsetDateTime nextCharge,
                           String checkoutUrl) {}
    public record Operation(long id, String event, String outcome, OffsetDateTime recordedAt) {}
    record Contract(UUID ref, long institutionId, long actorId, long authorityId, String mode, String providerId,
                    String status, OffsetDateTime sentAt, OffsetDateTime cancelAt, OffsetDateTime closedAt,
                    int attempts, Plan plan) {}
    record Notice(String kind, String resource, String requestId, int attempts) {}

    private static final String COLUMNS = """
            p.version,p.name AS plan_name,p.currency,p.monthly_amount,p.active_users,p.storage_bytes
            """;
    private static final String CONTRACT = """
            SELECT c.*, %s FROM subscription_runtime.contract c JOIN subscription_runtime.plan p ON p.version=c.plan_version
            """.formatted(COLUMNS);
    private static final String AUTHORITY = """
            SELECT a.id FROM subscription_runtime.current_authority a
            JOIN agreement_runtime.valid_memberships m ON m.institution_id=a.institution_id AND m.account_id=a.account_id
            JOIN public.user_accounts u ON u.id=a.account_id AND u.active
            WHERE a.institution_id=:school AND a.account_id=:actor
            """;
    private static final String PAID = """
            SELECT b.starts_at,b.ends_at FROM subscription_runtime.invoice b
            JOIN subscription_runtime.contract c ON c.ref=b.contract_ref
            WHERE c.institution_id=%s AND c.provider_mode=:mode AND b.status='APPROVED'
              AND b.approved_at<=statement_timestamp() AND b.starts_at<=statement_timestamp()
              AND b.ends_at>statement_timestamp()
            """;
    private final JdbcClient sql;
    private final SubscriptionSettings settings;
    private final AgreementService agreements;
    private final Environment environment;

    public SubscriptionLedger(JdbcClient sql, SubscriptionSettings settings, AgreementService agreements,
                              Environment environment) {
        this.sql = sql;
        this.settings = settings;
        this.agreements = agreements;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        settings.validate(environment);
        if (settings.enabled()) sql.sql("SELECT version FROM subscription_runtime.plan ORDER BY version DESC LIMIT 1")
                .query(Integer.class).single();
    }

    public Options options(long actor, long after) {
        if (!settings.enabled()) return new Options(false, List.of(), null);
        var rows = sql.sql("""
                SELECT i.id,i.name FROM subscription_runtime.school s
                JOIN evaluation_runtime.institutions i ON i.id=s.institution_id
                WHERE s.institution_id>:after AND EXISTS (
                """ + AUTHORITY.replace("a.institution_id=:school", "a.institution_id=s.institution_id") + """
                  UNION ALL
                  SELECT 1 FROM agreement_runtime.valid_memberships m JOIN evaluation_runtime.roles r
                    ON r.membership_id=m.id AND r.institution_id=m.institution_id
                  JOIN public.user_accounts u ON u.id=m.account_id AND u.active
                  WHERE m.institution_id=s.institution_id AND m.account_id=:actor
                    AND r.role='ADMIN_INSTITUCION' AND r.revoked_at IS NULL
                    AND r.valid_from<=statement_timestamp() AND (r.valid_until IS NULL OR r.valid_until>statement_timestamp()))
                ORDER BY i.id LIMIT 21
                """).param("actor", actor).param("after", after).query(School.class).list();
        return rows.size() <= 20 ? new Options(true, rows, null)
                : new Options(true, rows.subList(0, 20), rows.get(19).id());
    }

    public Overview overview(long school, long actor) {
        checkReader(school, actor);
        boolean representative = authority(school, actor) != null;
        return sql.sql("""
                SELECT %s, i.name,i.active,s.seats,q.used_bytes,s.trial_start,s.trial_end,
                       paid.starts_at,paid.ends_at,c.next_charge,c.provider_status,c.closed_at,
                       c.cancel_requested_at,c.checkout_url,c.provider_id,c.ref,c.actor_id,c.sent_at,
                       s.trial_end>statement_timestamp() AS in_trial,
                       EXISTS (SELECT 1 FROM subscription_runtime.invoice b JOIN subscription_runtime.contract prior
                           ON prior.ref=b.contract_ref WHERE prior.institution_id=s.institution_id
                           AND prior.provider_mode=:mode AND b.status='APPROVED' AND b.ends_at>statement_timestamp()) prepaid
                FROM subscription_runtime.school s JOIN subscription_runtime.plan p ON p.version=s.plan_version
                JOIN evaluation_runtime.institutions i ON i.id=s.institution_id
                JOIN evaluation_runtime.quotas q ON q.institution_id=s.institution_id
                LEFT JOIN LATERAL (%s ORDER BY b.ends_at DESC LIMIT 1) paid ON true
                LEFT JOIN LATERAL (SELECT * FROM subscription_runtime.contract WHERE institution_id=s.institution_id
                    AND provider_mode=:mode ORDER BY created_at DESC LIMIT 1) c ON true
                WHERE s.institution_id=:school
                """.formatted(COLUMNS, PAID.formatted("s.institution_id")))
                .param("school", school).param("mode", settings.mode().name()).query((rs, index) -> {
                    var trialStart = rs.getObject("trial_start", OffsetDateTime.class);
                    var trialEnd = rs.getObject("trial_end", OffsetDateTime.class);
                    var paidFrom = rs.getObject("starts_at", OffsetDateTime.class);
                    var paidUntil = rs.getObject("ends_at", OffsetDateTime.class);
                    boolean closing = rs.getObject("cancel_requested_at") != null;
                    boolean closed = rs.getObject("closed_at") != null;
                    boolean active = rs.getBoolean("active");
                    boolean trial = rs.getBoolean("in_trial");
                    String provider = rs.getString("provider_status");
                    String status = trialStart == null ? "AWAITING_ENABLEMENT" : trial ? "TRIAL"
                            : closed ? (paidUntil == null ? "EXPIRED" : "CANCELLED_PAID")
                            : closing ? "CANCELLATION_PENDING"
                            : paidUntil != null ? "PAID"
                            : "authorized".equals(provider) ? "AUTHORIZED_UNPAID"
                            : "paused".equals(provider) ? "RENEWAL_PAUSED"
                            : "pending".equals(provider) ? "CHECKOUT_PENDING"
                            : rs.getObject("sent_at") != null ? "VERIFYING"
                            : "EXPIRED";
                    String checkout = "pending".equals(provider) && representative && active && !closing && rs.getLong("actor_id") == actor
                            ? rs.getString("checkout_url") : null;
                    boolean canContract = representative && active && trialStart != null && !trial && !rs.getBoolean("prepaid")
                            && (closed || !closing && (rs.getObject("ref") == null || rs.getLong("actor_id") == actor
                                && (provider == null || "pending".equals(provider))));
                    return new Overview(settings.mode().name(), school, rs.getString("name"), active, canContract,
                            representative && !closed && !closing && rs.getObject("ref") != null, plan(rs), rs.getInt("seats"),
                            rs.getLong("used_bytes"), status, trialStart, trialEnd, paidFrom, paidUntil,
                            closing || closed ? null : rs.getObject("next_charge", OffsetDateTime.class), checkout);
                }).single();
    }

    public List<Operation> history(long school, long actor, long before) {
        checkReader(school, actor);
        return sql.sql("""
                SELECT id,event,outcome,recorded_at FROM subscription_runtime.audit
                WHERE institution_id=:school AND id<:before ORDER BY id DESC LIMIT 20
                """).param("school", school).param("before", before).query(Operation.class).list();
    }

    @Transactional
    public void verifySchool(long school, long operator, String evidence) {
        enabled();
        lock(school);
        var inserted = sql.sql("""
                INSERT INTO subscription_runtime.school(institution_id,plan_version,verified_by,verification_ref,seats)
                SELECT i.id,p.version,:operator,:evidence,
                     (SELECT count(*) FROM evaluation_runtime.memberships m WHERE m.institution_id=i.id AND m.active)
                FROM evaluation_runtime.institutions i
                CROSS JOIN (SELECT * FROM subscription_runtime.plan ORDER BY version DESC LIMIT 1) p
                JOIN evaluation_runtime.quotas q ON q.institution_id=i.id AND q.used_bytes<=p.storage_bytes
                WHERE i.id=:school AND (SELECT count(*) FROM evaluation_runtime.memberships m
                    WHERE m.institution_id=i.id AND m.active)<=p.active_users
                ON CONFLICT DO NOTHING
                """).param("school", school).param("operator", operator).param("evidence", evidence).update();
        if (inserted == 0) throw error(HttpStatus.CONFLICT, "Institución ya verificada, inexistente o fuera de los límites del plan.");
        sql.sql("""
                UPDATE evaluation_runtime.quotas q SET max_bytes=p.storage_bytes
                FROM subscription_runtime.school s JOIN subscription_runtime.plan p ON p.version=s.plan_version
                WHERE q.institution_id=s.institution_id AND s.institution_id=:school
                """).param("school", school).update();
        audit(school, operator, null, "SCHOOL_VERIFIED", null);
        sql.sql("""
                SELECT subscription_runtime.start_trial(i.id,clock_timestamp()) FROM evaluation_runtime.institutions i
                WHERE i.id=:school AND i.active
                """).param("school", school).query(Boolean.class).optional();
    }

    @Transactional
    public void recordAuthority(long school, long actor, long operator, String evidence, OffsetDateTime until) {
        enabled();
        lock(school);
        var inserted = sql.sql("""
                INSERT INTO subscription_runtime.authority(institution_id,account_id,granted_by,evidence_ref,valid_until)
                SELECT :school,:actor,:operator,:evidence,:until
                WHERE :actor<>:operator AND EXISTS (SELECT 1 FROM subscription_runtime.school WHERE institution_id=:school)
                AND EXISTS (SELECT 1 FROM evaluation_runtime.memberships m
                    WHERE m.account_id=:actor AND m.institution_id=:school AND (CAST(:until AS timestamptz) IS NULL OR m.active))
                AND (CAST(:until AS timestamptz) IS NULL OR CAST(:until AS timestamptz)>statement_timestamp())
                """).param("school", school).param("actor", actor).param("operator", operator).param("evidence", evidence)
                .param("until", until, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
        if (inserted == 0) throw error(HttpStatus.CONFLICT, "No se puede registrar la facultad comercial indicada.");
        audit(school, operator, null, until == null ? "AUTHORITY_REVOKED" : "AUTHORITY_VERIFIED", null);
        if (until == null) {
            sql.sql("""
                    UPDATE subscription_runtime.contract SET cancel_requested_at=clock_timestamp(),reconcile_at=clock_timestamp()
                    WHERE institution_id=:school AND actor_id=:actor AND closed_at IS NULL AND cancel_requested_at IS NULL
                      AND (provider_status IS NULL OR provider_status='pending')
                    """).param("school", school).param("actor", actor).update();
        }
    }

    @Transactional
    public Contract reserve(long school, long actor) {
        enabled();
        lock(school);
        long authority = requireAuthority(school, actor);
        if (!agreements.mayActivateInstitution(school) || agreements.requiredOutstanding(actor, school)) {
            throw error(HttpStatus.FORBIDDEN, "Revise las condiciones institucionales vigentes antes de contratar.");
        }
        if (!sql.sql("""
                SELECT EXISTS (SELECT 1 FROM subscription_runtime.school s JOIN evaluation_runtime.institutions i
                    ON i.id=s.institution_id WHERE i.active AND s.institution_id=:school AND s.trial_end<=statement_timestamp())
                """).param("school", school).query(Boolean.class).single()) {
            throw error(HttpStatus.CONFLICT, "La contratación se habilita al terminar la prueba de una institución activa.");
        }
        var existing = open(school);
        if (existing != null) {
            if (!existing.mode().equals(settings.mode().name())) throw error(HttpStatus.CONFLICT, "Hay una operación en otro entorno.");
            if (existing.actorId() != actor && (existing.status() == null || "pending".equals(existing.status()))) {
                throw error(HttpStatus.CONFLICT, "Otro representante inició la autorización. Solicite cancelar esa operación.");
            }
            return existing;
        }
        if (sql.sql("""
                SELECT EXISTS(SELECT 1 FROM subscription_runtime.invoice b JOIN subscription_runtime.contract c
                    ON c.ref=b.contract_ref WHERE c.institution_id=:school AND c.provider_mode=:mode
                    AND b.status='APPROVED' AND b.ends_at>statement_timestamp())
                """).param("school", school).param("mode", settings.mode().name()).query(Boolean.class).single()) {
            throw error(HttpStatus.CONFLICT, "Hay un período pagado pendiente de consumo.");
        }
        var ref = sql.sql("""
                INSERT INTO subscription_runtime.contract(institution_id,plan_version,actor_id,authority_id,provider_mode)
                SELECT :school,plan_version,:actor,:authority,:mode FROM subscription_runtime.school WHERE institution_id=:school
                RETURNING ref
                """).param("school", school).param("actor", actor).param("authority", authority)
                .param("mode", settings.mode().name()).query(UUID.class).single();
        audit(school, actor, ref, "CHECKOUT_REQUESTED", null);
        return contract(ref);
    }

    @Transactional
    public void requestCancellation(long school, long actor) {
        enabled();
        lock(school);
        requireAuthority(school, actor);
        var current = open(school);
        if (current == null || current.cancelAt() != null) return;
        if (!current.mode().equals(settings.mode().name())) throw error(HttpStatus.CONFLICT, "Hay una operación en otro entorno.");
        sql.sql("""
                UPDATE subscription_runtime.contract SET cancel_requested_at=clock_timestamp(),reconcile_at=clock_timestamp()
                WHERE ref=:ref
                """).param("ref", current.ref()).update();
        audit(school, actor, current.ref(), "CANCELLATION_REQUESTED", null);
    }

    @Transactional
    boolean claimCreation(Contract contract) {
        lock(contract.institutionId());
        if (authority(contract.institutionId(), contract.actorId()) == null
                || !agreements.mayActivateInstitution(contract.institutionId())
                || agreements.requiredOutstanding(contract.actorId(), contract.institutionId())) return false;
        return sql.sql("""
                UPDATE subscription_runtime.contract SET sent_at=clock_timestamp(),reconcile_at=clock_timestamp()+interval '30 seconds'
                WHERE ref=:ref AND sent_at IS NULL AND cancel_requested_at IS NULL AND closed_at IS NULL
                  AND EXISTS (SELECT 1 FROM evaluation_runtime.institutions i WHERE i.id=institution_id AND i.active)
                """).param("ref", contract.ref()).update() == 1;
    }

    @Transactional
    void observeContract(Contract contract, String id, String status, OffsetDateTime version, String checkout, OffsetDateTime next) {
        lock(contract.institutionId());
        var previous = contract(contract.ref());
        int updated = sql.sql("""
                UPDATE subscription_runtime.contract SET provider_id=:id,provider_status=:status,provider_modified=:version,
                    checkout_url=:checkout,next_charge=:next,closed_at=CASE WHEN :status='cancelled' THEN
                        coalesce(closed_at,clock_timestamp()) ELSE closed_at END
                WHERE ref=:ref AND (provider_id IS NULL OR provider_id=:id)
                  AND (provider_modified IS NULL OR provider_modified<:version OR
                      (provider_modified=:version AND :status='cancelled' AND provider_status<>'cancelled'))
                  AND (closed_at IS NULL OR :status='cancelled')
                """).param("ref", contract.ref()).param("id", id).param("status", status).param("version", version)
                .param("checkout", checkout).param("next", next, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
        if (updated != 0 && !status.equals(previous.status())) {
            audit(contract.institutionId(), null, contract.ref(), "RECURRENCE_STATE", status);
        }
    }

    @Transactional
    void closeUnsent(Contract contract) {
        lock(contract.institutionId());
        if (sql.sql("""
                UPDATE subscription_runtime.contract SET closed_at=clock_timestamp()
                WHERE ref=:ref AND sent_at IS NULL AND closed_at IS NULL AND cancel_requested_at IS NOT NULL
                """).param("ref", contract.ref()).update() != 0) {
            audit(contract.institutionId(), null, contract.ref(), "CHECKOUT_WITHDRAWN", null);
        }
    }

    @Transactional
    void observeInvoice(Contract contract, String invoice, String payment, String status, OffsetDateTime version,
                        OffsetDateTime from, OffsetDateTime until, OffsetDateTime approved) {
        lock(contract.institutionId());
        var previous = sql.sql("SELECT status FROM subscription_runtime.invoice WHERE id=:id")
                .param("id", invoice).query(String.class).optional();
        int changed = sql.sql("""
                INSERT INTO subscription_runtime.invoice(id,contract_ref,payment_id,status,observed_version,starts_at,ends_at,approved_at)
                VALUES (:id,:ref,:payment,:status,:version,:start,:end,:approved)
                ON CONFLICT(id) DO UPDATE SET payment_id=EXCLUDED.payment_id,status=EXCLUDED.status,
                    observed_version=EXCLUDED.observed_version,approved_at=EXCLUDED.approved_at
                WHERE invoice.contract_ref=EXCLUDED.contract_ref
                  AND (invoice.observed_version<EXCLUDED.observed_version OR
                    (invoice.observed_version=EXCLUDED.observed_version AND EXCLUDED.status='REVERSED' AND invoice.status<>'REVERSED'))
                  AND (invoice.status NOT IN ('APPROVED','REVERSED') OR
                    (invoice.status='APPROVED' AND EXCLUDED.status IN ('APPROVED','REVERSED')
                    AND invoice.payment_id=EXCLUDED.payment_id))
                """).param("id", invoice).param("ref", contract.ref()).param("payment", payment)
                .param("status", status).param("version", version).param("start", from).param("end", until)
                .param("approved", approved, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
        if (changed != 0 && !previous.orElse("").equals(status)) {
            audit(contract.institutionId(), null, contract.ref(), "PAYMENT_STATE", status);
        }
    }

    void notice(String kind, String resource, String requestId) {
        sql.sql("""
                INSERT INTO subscription_runtime.inbox(kind,resource,request_id)
                VALUES (:kind,:resource,:request) ON CONFLICT DO NOTHING
                """).param("kind", kind).param("resource", resource).param("request", requestId).update();
    }

    List<Notice> dueNotices() {
        return sql.sql("""
                WITH due AS (SELECT kind,resource,request_id FROM subscription_runtime.inbox
                  WHERE processed_at IS NULL AND available_at<=clock_timestamp()
                  ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                UPDATE subscription_runtime.inbox n SET available_at=clock_timestamp()+interval '8 minutes',attempts=n.attempts+1
                FROM due WHERE (n.kind,n.resource,n.request_id)=(due.kind,due.resource,due.request_id)
                RETURNING n.kind,n.resource,n.request_id,n.attempts
                """).query(Notice.class).list();
    }

    void noticeResult(Notice notice, boolean done) {
        sql.sql("""
                UPDATE subscription_runtime.inbox SET processed_at=CASE WHEN :done THEN clock_timestamp() END,
                    available_at=clock_timestamp()+make_interval(mins=>least(720,1<<least(attempts,10)))
                WHERE (kind,resource,request_id)=(:kind,:resource,:request) AND attempts=:attempts
                """).param("kind", notice.kind()).param("resource", notice.resource()).param("request", notice.requestId())
                .param("attempts", notice.attempts()).param("done", done).update();
    }

    List<Contract> dueContracts() {
        return sql.sql("""
                WITH due AS (SELECT ref FROM subscription_runtime.contract
                    WHERE (closed_at IS NULL OR provider_id IS NOT NULL AND closed_at>clock_timestamp()-interval '35 days')
                      AND provider_mode=:mode AND reconcile_at<=clock_timestamp()
                      AND (sent_at IS NOT NULL OR cancel_requested_at IS NOT NULL)
                    ORDER BY reconcile_at LIMIT 1 FOR UPDATE SKIP LOCKED),
                claimed AS (UPDATE subscription_runtime.contract c SET attempts=c.attempts+1,
                    reconcile_at=clock_timestamp()+interval '8 minutes' FROM due WHERE c.ref=due.ref RETURNING c.*)
                """ + CONTRACT.replace("subscription_runtime.contract c", "claimed c"))
                .param("mode", settings.mode().name()).query(SubscriptionLedger::contract).list();
    }

    void reconciliationResult(Contract contract, boolean done) {
        sql.sql("""
                UPDATE subscription_runtime.contract SET attempts=CASE WHEN :done THEN 0 ELSE attempts END,
                    reconcile_at=clock_timestamp()+make_interval(mins=>CASE WHEN :done THEN 1440 ELSE
                        least(720,1<<least(attempts,10)) END)
                WHERE ref=:ref AND attempts=:attempts
                  AND cancel_requested_at IS NOT DISTINCT FROM CAST(:claimedCancellation AS timestamptz)
                """).param("ref", contract.ref()).param("done", done).param("attempts", contract.attempts())
                .param("claimedCancellation", contract.cancelAt(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    public boolean blocked(long school) {
        if (!settings.enabled()) return false;
        return sql.sql("""
                SELECT EXISTS(SELECT 1 FROM subscription_runtime.school s WHERE s.institution_id=:school
                    AND (s.trial_end IS NULL OR s.trial_end<=statement_timestamp())
                    AND NOT EXISTS (%s))
                """.formatted(PAID.formatted("s.institution_id")))
                .param("school", school).param("mode", settings.mode().name()).query(Boolean.class).single();
    }

    void audit(long school, Long actor, UUID ref, String event, String outcome) {
        sql.sql("""
                INSERT INTO subscription_runtime.audit(institution_id,actor_id,contract_ref,event,outcome)
                VALUES (:school,:actor,:ref,:event,:outcome)
                """).param("school", school).param("actor", actor).param("ref", ref).param("event", event)
                .param("outcome", outcome).update();
    }

    Contract contract(UUID ref) {
        return sql.sql(CONTRACT + " WHERE c.ref=:ref AND c.provider_mode=:mode")
                .param("ref", ref).param("mode", settings.mode().name()).query(SubscriptionLedger::contract).optional().orElse(null);
    }

    private Contract open(long school) {
        return sql.sql(CONTRACT + " WHERE c.institution_id=:school AND c.closed_at IS NULL")
                .param("school", school).query(SubscriptionLedger::contract).optional().orElse(null);
    }

    private void checkReader(long school, long actor) {
        enabled();
        if (authority(school, actor) != null) return;
        if (!sql.sql("""
                SELECT EXISTS (SELECT 1 FROM subscription_runtime.school s JOIN agreement_runtime.valid_memberships m
                  ON m.institution_id=s.institution_id JOIN evaluation_runtime.roles r ON r.membership_id=m.id
                JOIN public.user_accounts u ON u.id=m.account_id AND u.active
                WHERE s.institution_id=:school AND m.account_id=:actor AND r.role='ADMIN_INSTITUCION'
                  AND r.revoked_at IS NULL AND r.valid_from<=statement_timestamp()
                  AND (r.valid_until IS NULL OR r.valid_until>statement_timestamp()))
                """).param("school", school).param("actor", actor).query(Boolean.class).single()) {
            throw error(HttpStatus.NOT_FOUND, "No existe una suscripción institucional accesible.");
        }
    }

    Long authority(long school, long actor) {
        return sql.sql(AUTHORITY).param("school", school).param("actor", actor).query(Long.class).optional().orElse(null);
    }

    private long requireAuthority(long school, long actor) {
        Long id = authority(school, actor);
        if (id == null) throw error(HttpStatus.FORBIDDEN, "Se requiere representación comercial verificada.");
        return id;
    }

    private void lock(long school) {
        sql.sql("SELECT pg_advisory_xact_lock(evaluation_runtime.institution_lock(:school))")
                .param("school", school).query((rs, n) -> true).single();
    }

    void enabled() {
        if (!settings.enabled()) throw error(HttpStatus.NOT_FOUND, "Suscripciones deshabilitadas.");
    }

    static ResponseStatusException error(HttpStatus status, String text) { return new ResponseStatusException(status, text); }

    private static Plan plan(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Plan(rs.getInt("version"), rs.getString("plan_name"), rs.getString("currency").trim(),
                rs.getBigDecimal("monthly_amount"), rs.getInt("active_users"), rs.getLong("storage_bytes"));
    }

    private static Contract contract(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Contract(rs.getObject("ref", UUID.class), rs.getLong("institution_id"), rs.getLong("actor_id"),
                rs.getLong("authority_id"), rs.getString("provider_mode"), rs.getString("provider_id"),
                rs.getString("provider_status"), rs.getObject("sent_at", OffsetDateTime.class),
                rs.getObject("cancel_requested_at", OffsetDateTime.class), rs.getObject("closed_at", OffsetDateTime.class),
                rs.getInt("attempts"), plan(rs));
    }
}

package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.AccountSessionService;
import com.morales.chemicallab.security.SessionPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** T12-A: fixture-only, read-only, internal decision. No consumer may treat a result as a capability.
 * Every later access must resolve again and coordinate with its writers before using protected data.
 */
@Service
public class EvaluationDeliveryAccessService {
    public enum Contract { LEGACY, NEW }
    public enum Action { READ_DELIVERY, READ_OWN_ATTEMPT, SUPERVISE, REVIEW_ATTEMPT, READ_CORRECTION, GRADE_ATTEMPT, SAVE_OWN_ANSWER }
    public enum RevisionKind { TECHNICAL_SNAPSHOT, PUBLISHED_ACADEMIC }
    public enum Projection { DELIVERY_WITHOUT_CORRECTION, OWN_ATTEMPT_WITHOUT_CORRECTION, SUPERVISION_METADATA,
                             TEACHER_REVIEW, OWN_CORRECTION, GRADE_PERMISSION_ONLY }
    public enum Outcome { ALLOW, DENY, UNAVAILABLE }
    /** LEGACY IDs name B3 delivery/revision/recipient and legacy attempt; NEW IDs name B8 rows. */
    public record Request(Contract contract, Action action, long institutionId, long classId,
                          long evaluationId, long deliveryId, long revisionId, long recipientAccountId, Long attemptId) {}
    /** Identifiers only. No questions, answers, scores, feedback, budgets or unrelated memberships. */
    public record Context(Contract contract, RevisionKind revisionKind, Projection projection, long institutionId, long classId,
                          long evaluationId, long deliveryId, long revisionId, long recipientAccountId,
                          Long attemptId, long rootContextVersion, long deliveryContextVersion, Instant observedAt) {}
    public record Decision(Outcome outcome, Action action, Context context) {
        public boolean allowed() { return outcome == Outcome.ALLOW; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final AccountSessionService sessions;
    private final TransactionTemplate read;
    private final TransactionTemplate coherentRead;

    public EvaluationDeliveryAccessService(JdbcTemplate jdbc, AccountSessionService sessions,
                                          PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        read.setTimeout(15);
        coherentRead = new TransactionTemplate(transactions);
        coherentRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // LOCK SHARE needs a read/write transaction. This unit executes no business DML.
        // READ_COMMITTED observes writers that committed while the lock was being acquired.
        coherentRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        coherentRead.setTimeout(15);
    }

    /** T12-B only: authorization and fully materialized DTO inside one fixture lock interval.
     * Package-private so consumers cannot reuse a previously returned ALLOW/context.
     * Broad table locks deliberately match the fixture protocol, not a production lock design.
     */
    <T> T readCoherently(SessionPrincipal principal, Request request, String approvedSchema,
                         Function<Context, T> projection) {
        try {
            require(principal != null && !principal.temporaryPassword() && valid(request));
            return coherentRead.execute(status -> {
                InstitutionalFixtureUnit.lock(jdbc, approvedSchema, false);
                sessions.revalidate(principal);
                Decision decision = resolve(principal, request, true);
                require(decision.allowed());
                T result = projection.apply(decision.context());
                sessions.revalidate(principal);
                return result;
            });
        } catch (Denied failure) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Lectura no autorizada.");
        } catch (AuthenticationException failure) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no vigente.");
        } catch (DataAccessException | TransactionException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lectura no disponible.");
        }
    }

    /** Principal must come from T02 authentication, never from a request body. Null/failure denies.
     * The separate transaction ignores an enclosing caller's stale persistence context.
     */
    public Decision decide(SessionPrincipal principal, Request request) {
        Action action = request == null ? null : request.action();
        try {
            require(principal != null && !principal.temporaryPassword() && principal.userId() != null
                    && principal.sessionId() != null && valid(request));
            sessions.revalidate(principal);
            Decision result = read.execute(status -> resolve(principal, request));
            // Recheck global revocation after the potentially expensive B10 photograph.
            sessions.revalidate(principal);
            return result;
        } catch (Denied | AuthenticationException failure) {
            return new Decision(Outcome.DENY, action, null);
        } catch (DataAccessException | TransactionException failure) {
            return new Decision(Outcome.UNAVAILABLE, action, null);
        }
    }

    private static boolean valid(Request q) {
        return q != null && q.contract() != null && q.action() != null && q.institutionId() > 0 && q.classId() > 0
                && q.evaluationId() > 0 && q.deliveryId() > 0 && q.revisionId() > 0 && q.recipientAccountId() > 0
                && (q.attemptId() == null || q.attemptId() > 0)
                && (q.action() == Action.SUPERVISE || (q.action() == Action.READ_DELIVERY ? q.attemptId() == null : q.attemptId() != null));
    }

    private Decision resolve(SessionPrincipal principal, Request q) {
        return resolve(principal,q,false);
    }

    Context authorizeSave(SessionPrincipal principal, Request q) {
        try {
            require(valid(q) && q.action()==Action.SAVE_OWN_ANSWER);
            return resolve(principal,q,true).context();
        } catch(Denied failure) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Guardado no autorizado.");
        }
    }

    private Decision resolve(SessionPrincipal principal, Request q, boolean readingRows) {
        // Retain the existing maintainer boundary. This read installs/mutates nothing.
        jdbc.execute("select t11b5_fixture()");
        // One SQL statement fixes both the server instant and B8's time-dependent
        // global closure. Java T05/relationship windows use that exact same instant.
        Observation observation = observe(q);
        Instant now = observation.at();
        Timestamp at = Timestamp.from(now);
        long member = member(principal.userId(), q.institutionId(), at);
        require(exists("""
            select exists(select 1 from academic_classes c join academic_periods p
              on p.id=c.period_id and p.institution_id=c.institution_id join institutions i on i.id=c.institution_id
              where c.id=? and c.institution_id=? and i.status='ACTIVE' and c.status='ACTIVE' and p.status='OPEN'
              and c.state_since<=? and p.state_since<=? and p.valid_from<=? and ?<p.valid_until)
            """, q.classId(), q.institutionId(), at, at, at, at));
        boolean admin = role(member, "ADMIN_INSTITUCION", at);
        boolean teacher = role(member, "DOCENTE", at) && assigned(member, q, at);
        boolean own = principal.userId() == q.recipientAccountId() && role(member, "ESTUDIANTE", at);

        var b10 = new CurrentContexts(q);
        Resolved root = b10.get("evaluations", q.evaluationId(), false, null, null);
        Delivery delivery = q.contract() == Contract.LEGACY ? legacy(q, b10, now) : academic(q, b10, observation);
        Resolved d = b10.get(delivery.source(), q.deliveryId(), true, null, null);
        b10.get(delivery.revisionSource(), q.revisionId(), false, null, null);
        Resolved recipient = b10.get(delivery.recipientSource(), delivery.recipientId(), true, q.recipientAccountId(), null);
        require(recipient.enrollment() != null);
        long targetMember = member(q.recipientAccountId(), q.institutionId(), at);
        require(role(targetMember, "ESTUDIANTE", at));
        require(exists("""
            select exists(select 1 from enrollments where id=? and institution_id=? and class_id=? and membership_id=?
              and status='ACTIVE' and state_since<=? and activated_at<=? and valid_from<=? and ?<valid_until
              and (ended_at is null or ?<ended_at))
            """, recipient.enrollment(), q.institutionId(), q.classId(), targetMember, at, at, at, at, at));

        Attempt attempt = null;
        if (q.attemptId() != null) {
            attempt = q.contract() == Contract.LEGACY ? legacyAttempt(q, delivery, now) : academicAttempt(q, delivery);
            b10.get(q.contract() == Contract.LEGACY ? "evaluation_attempts" : "t11b8_fixture_attempts",
                    q.attemptId(), true, q.recipientAccountId(), recipient.enrollment());
        }
        boolean permitted = switch (q.action()) {
            case READ_DELIVERY -> admin || teacher || (own && delivery.available());
            case READ_OWN_ATTEMPT -> own && delivery.started();
            case SAVE_OWN_ANSWER -> q.contract()==Contract.LEGACY && own && delivery.available() && !attempt.terminal();
            case SUPERVISE -> admin || teacher;
            case REVIEW_ATTEMPT -> teacher && principal.userId() != q.recipientAccountId() && attempt.terminal();
            case GRADE_ATTEMPT -> teacher && principal.userId() != q.recipientAccountId() && attempt.terminal() && !attempt.closed();
            case READ_CORRECTION -> own && attempt.correctionVisible();
        };
        require(permitted);
        Projection projection = switch (q.action()) {
            case READ_DELIVERY -> own && delivery.available() ? Projection.DELIVERY_WITHOUT_CORRECTION : Projection.SUPERVISION_METADATA;
            case READ_OWN_ATTEMPT, SAVE_OWN_ANSWER -> Projection.OWN_ATTEMPT_WITHOUT_CORRECTION;
            case SUPERVISE -> Projection.SUPERVISION_METADATA;
            case REVIEW_ATTEMPT -> Projection.TEACHER_REVIEW;
            case READ_CORRECTION -> Projection.OWN_CORRECTION;
            case GRADE_ATTEMPT -> Projection.GRADE_PERMISSION_ONLY;
        };
        if (readingRows && q.contract()==Contract.LEGACY && projection!=Projection.SUPERVISION_METADATA)
            validateReadRows(q,b10);
        return new Decision(Outcome.ALLOW, q.action(), new Context(q.contract(),
                q.contract() == Contract.LEGACY ? RevisionKind.TECHNICAL_SNAPSHOT : RevisionKind.PUBLISHED_ACADEMIC,
                projection, q.institutionId(), q.classId(), q.evaluationId(), q.deliveryId(), q.revisionId(), q.recipientAccountId(),
                q.attemptId(), root.version(), d.version(), now));
    }

    /** A resolved root does not resolve uncertain legacy content/answer rows.
     * Use the very same B10 resolver/cache as the decision; NEW JSON belongs to its exact revision row.
     */
    private void validateReadRows(Request q, CurrentContexts b10) {
        if(q.action()==Action.READ_OWN_ATTEMPT || q.action()==Action.SAVE_OWN_ANSWER) {
            String stored=jdbc.queryForObject("select question_order from evaluation_attempts where id=?",String.class,q.attemptId());
            // Historical order may contain inactive, unanswered questions. Check every ID,
            // without filtering/reordering, with this decision's resolver and coherent locks.
            List<Long> order=legacyQuestionOrder(stored);
            if(q.action()==Action.SAVE_OWN_ANSWER)require(new HashSet<>(order).size()==order.size());
            for(long id:order) {
                require(exists("select exists(select 1 from evaluation_questions where id=? and evaluation_id=?)",id,q.evaluationId()));
                b10.get("evaluation_questions",id,false,null,null);
            }
        }
        for(long id:jdbc.queryForList("""
            select x.id from evaluation_questions x where x.evaluation_id=? and
              (x.active or exists(select 1 from evaluation_answers a where a.question_id=x.id and a.attempt_id=?))
            """,Long.class,q.evaluationId(),q.attemptId()))
            b10.get("evaluation_questions",id,false,null,null);
        for(long id:jdbc.queryForList("""
            select o.id from evaluation_options o join evaluation_questions x on x.id=o.question_id
            where x.evaluation_id=? and ((x.active and o.active) or exists
              (select 1 from evaluation_answers a where a.selected_option_id=o.id and a.attempt_id=?))
            """,Long.class,q.evaluationId(),q.attemptId()))
            b10.get("evaluation_options",id,false,null,null);
        if(q.attemptId()!=null)
            for(long id:jdbc.queryForList("select id from evaluation_answers where attempt_id=?",Long.class,q.attemptId()))
                b10.get("evaluation_answers",id,true,q.recipientAccountId(),null);
    }

    /** Shared stored-order parser: validation and projection consume exactly the same IDs.
     * An absent order still uses the active-content fallback, validated above.
     */
    static List<Long> legacyQuestionOrder(String raw) {
        if(raw==null || raw.isBlank()) return List.of();
        var order=new ArrayList<Long>();
        try {
            for(String part:raw.split(",")) {
                String value=part.trim();
                if(!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                order.add(Long.parseLong(value));
            }
        } catch(NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Proyección no disponible.");
        }
        return List.copyOf(order);
    }

    private record Resolved(long version, Long enrollment) {}
    private record Observation(Instant at, boolean rootClosed) {}
    private Observation observe(Request q) {
        if (q.contract() == Contract.LEGACY)
            return new Observation(jdbc.queryForObject("select statement_timestamp()",Timestamp.class).toInstant(),false);
        var rows = jdbc.query("""
            select statement_timestamp() observed,t11b8_closed(d.resource_id) closed
            from t11b8_deliveries d join t11b8_resources r on r.id=d.resource_id
            where d.id=? and d.revision_id=? and r.evaluation_id=?
            """,(rs,n)->new Observation(rs.getTimestamp("observed").toInstant(),rs.getBoolean("closed")),
                q.deliveryId(),q.revisionId(),q.evaluationId());
        require(rows.size()==1);
        return rows.get(0);
    }
    /** Same latest-head + fresh + INSTITUTION predicate as t11b10_current.
     * Freshness is shared only by rows of one proposal in this invocation's repeatable snapshot.
     */
    private final class CurrentContexts {
        private final Request q;
        private final Map<Long, Boolean> freshness = new HashMap<>();
        private long rootVersion;
        private long rootProposal;
        private CurrentContexts(Request q) { this.q = q; }
        private Resolved get(String source, long id, boolean classRequired, Long account, Long enrollment) {
            var rows = jdbc.queryForList("""
                select v.id,v.proposal_id,v.resolution::text from t11b10_versions v
                where v.source_table=? and v.source_pk=jsonb_build_object(?::text,?::bigint)
                  and not exists(select 1 from t11b10_versions next where next.predecessor_id=v.id)
                """, source, source.equals("t11b3_evaluation_resources") ? "evaluation_id" : "id", id);
            if(rows.isEmpty() && source.equals("evaluation_answers") && bridgeInstalled()) {
                // A new answer has no historical B10 version. Only an exact, committed
                // receipt for the current approved root may supply its derived context.
                require(rootVersion>0 && Boolean.TRUE.equals(freshness.get(rootProposal)));
                // The same resolver already verified the complete current manifest above.
                // Reuse that observation, not another ALLOW or a separately scanned snapshot.
                String raw=jdbc.queryForObject("select answer_resolution::text from "+control()+".saves where root_version=? and answer_id=? and after_manifest is not null order by ordinal desc limit 1",String.class,rootVersion,id);
                if(raw!=null) {
                    try {
                        JsonNode derived=JSON.readTree(raw);
                        require(equal(derived,"institutionId",q.institutionId()) && equal(derived,"classId",q.classId())
                            && account!=null && equal(derived,"accountId",account));
                        return new Resolved(derived.path("version").asLong(),derived.path("enrollmentId").asLong());
                    } catch(java.io.IOException invalid) {throw new Denied();}
                }
            }
            require(rows.size() == 1);
            var row = rows.get(0);
            require(freshness.computeIfAbsent(number(row, "proposal_id"), proposal ->
                    (exists("select t11p05_fresh(?)", proposal) || (bridgeInstalled() && exists("select "+control()+".fresh(?)",proposal)))));
            if(source.equals("evaluations")) {
                rootVersion=number(row,"id");rootProposal=number(row,"proposal_id");
            }
            JsonNode resolution;
            try { resolution = JSON.readTree((String) row.get("resolution")); }
            catch (Exception invalid) { throw new Denied(); }
            require(resolution != null && resolution.isObject() && "INSTITUTION".equals(resolution.path("scope").asText())
                    && equal(resolution, "institutionId", q.institutionId()));
            if (classRequired || resolution.has("classId")) require(equal(resolution, "classId", q.classId()));
            if (account != null) require(equal(resolution, "accountId", account));
            if (enrollment != null) require(equal(resolution, "enrollmentId", enrollment));
            return new Resolved(((Number) row.get("id")).longValue(),
                    resolution.has("enrollmentId") ? resolution.get("enrollmentId").longValue() : null);
        }
    }
    private String control() {return InstitutionalFixtureUnit.control(jdbc.queryForObject("select current_schema()",String.class));}
    private boolean bridgeInstalled() {return exists("select to_regprocedure(?::text) is not null",control()+".fresh(bigint)");}
    private static boolean equal(JsonNode value, String key, long expected) {
        return value.path(key).isIntegralNumber() && value.path(key).canConvertToLong() && value.path(key).longValue() == expected;
    }

    private long member(long account, long institution, Timestamp at) {
        var rows = jdbc.queryForList("""
            select m.id from institution_memberships m join user_accounts u on u.id=m.account_id
              join institutions i on i.id=m.institution_id
            where m.account_id=? and m.institution_id=? and u.active and i.status='ACTIVE' and m.status='ACTIVE'
              and m.state_since<=? and m.valid_from<=? and (m.valid_until is null or ?<m.valid_until)
            """, Long.class, account, institution, at, at, at);
        require(rows.size() == 1);
        return rows.get(0);
    }
    private boolean role(long member, String role, Timestamp at) {
        return exists("""
            select exists(select 1 from membership_role_grants where membership_id=? and role=? and status='ACTIVE'
              and revoked_at is null and valid_from<=? and (valid_until is null or ?<valid_until))
            """, member, role, at, at);
    }
    private boolean assigned(long member, Request q, Timestamp at) {
        return exists("""
            select exists(select 1 from (
              select institution_id,class_id,membership_id,status,state_since,valid_from,valid_until,activated_at,ended_at
                from teaching_assignments where function='RESPONSABLE'
              union all
              select institution_id,class_id,membership_id,status,state_since,valid_from,valid_until,activated_at,ended_at
                from t11b6_co_assignments where function='CO_DOCENTE') t
              where institution_id=? and class_id=? and membership_id=? and status='ACTIVE'
                and state_since<=? and valid_from<=? and activated_at<=? and ?<valid_until and (ended_at is null or ?<ended_at))
            """, q.institutionId(), q.classId(), member, at, at, at, at, at);
    }

    private record Delivery(String source, String revisionSource, String recipientSource, long recipientId,
                            Long assignmentId, Long resourceId, boolean started, boolean available, boolean correctionAvailable) {}
    private Delivery legacy(Request q, CurrentContexts b10, Instant now) {
        var rows = jdbc.queryForList("""
            select d.legacy_assignment_id,r.id recipient_id from t11b3_evaluation_deliveries d
              join t11b3_evaluation_revisions v on v.id=d.revision_id and v.evaluation_id=d.evaluation_id
              join t11b3_evaluation_recipients r on r.delivery_id=d.id
            where d.id=? and d.evaluation_id=? and d.revision_id=? and r.account_id=?
            """, q.deliveryId(), q.evaluationId(), q.revisionId(), q.recipientAccountId());
        require(rows.size() == 1);
        var row = rows.get(0);
        long assignmentId = number(row, "legacy_assignment_id");
        b10.get("evaluation_assignments", assignmentId, true, null, null);
        b10.get("t11b3_evaluation_resources", q.evaluationId(), false, null, null);
        // Build only the metadata T05 needs. Never load a question, answer or correction payload.
        Evaluation evaluation = legacyEvaluation(q.evaluationId());
        var assignments = legacyAssignments(evaluation);
        EvaluationAssignment assignment = assignments.stream().filter(a -> a.getId() == assignmentId).findFirst().orElseThrow(Denied::new);
        var policy = policy(now);
        return new Delivery("t11b3_evaluation_deliveries", "t11b3_evaluation_revisions", "t11b3_evaluation_recipients",
                number(row, "recipient_id"), assignmentId, null, !policy.beforeStart(assignment),
                policy.window(assignment) == EvaluationAccessPolicy.Window.AVAILABLE, policy.reviewAvailable(evaluation, assignments));
    }

    private Delivery academic(Request q, CurrentContexts b10, Observation observation) {
        var rows = jdbc.queryForList("""
            select d.resource_id,d.starts_at,d.due_at,d.status,r.id recipient_id,s.availability
            from t11b8_deliveries d join t11b8_resources s on s.id=d.resource_id
              join t11b8_revisions v on v.id=d.revision_id and v.resource_id=s.id
              join t11b8_recipients r on r.delivery_id=d.id and r.resource_id=s.id
            where d.id=? and s.evaluation_id=? and d.revision_id=? and s.creation='EXPLICIT_NEW'
              and v.status='PUBLISHED' and v.published_at<=? and r.account_id=? and r.status='ADMITTED'
            """, q.deliveryId(), q.evaluationId(), q.revisionId(), Timestamp.from(observation.at()), q.recipientAccountId());
        require(rows.size() == 1);
        var row = rows.get(0);
        long resource = number(row, "resource_id");
        b10.get("t11b8_resources", resource, false, null, null);
        // The DRAFT evaluation is a technical anchor, not a second published academic revision or engine.
        require(!exists("select exists(select 1 from evaluation_assignments where evaluation_id=?) or exists(select 1 from evaluation_attempts where evaluation_id=?)",
                q.evaluationId(), q.evaluationId()));
        boolean rootClosed = observation.rootClosed();
        Instant now = observation.at();
        boolean started = !now.isBefore(((Timestamp) row.get("starts_at")).toInstant());
        boolean available = started && !rootClosed && "OPEN".equals(row.get("status")) && "AVAILABLE".equals(row.get("availability"))
                && (row.get("due_at") == null || now.isBefore(((Timestamp) row.get("due_at")).toInstant()));
        boolean closed = rootClosed && !exists("""
            select exists(select 1 from t11b8_fixture_attempts where resource_id=? and (status<>'FINAL' or not grade_closed))
            """, resource);
        return new Delivery("t11b8_deliveries", "t11b8_revisions", "t11b8_recipients", number(row, "recipient_id"),
                null, resource, started, available, closed);
    }

    private record Attempt(boolean terminal, boolean closed, boolean correctionVisible) {}
    private Attempt legacyAttempt(Request q, Delivery delivery, Instant now) {
        var rows = jdbc.queryForList("""
            select a.status,a.grade_closed,s.start_at from evaluation_attempts a join student_profiles p on p.id=a.student_id
              join evaluation_assignments s on s.id=a.assignment_id
            where a.id=? and a.evaluation_id=? and a.assignment_id=? and p.user_id=?
            """, q.attemptId(), q.evaluationId(), delivery.assignmentId(), q.recipientAccountId());
        require(rows.size() == 1);
        var row = rows.get(0);
        String status = (String) row.get("status");
        require(Set.of("IN_PROGRESS", "SUBMITTED", "GRADED", "PENDING_MANUAL_REVIEW").contains(status));
        boolean closed = Boolean.TRUE.equals(row.get("grade_closed"));
        var assignment = EvaluationAssignment.builder().startAt(row.get("start_at") == null ? null : ((Timestamp) row.get("start_at")).toLocalDateTime()).build();
        var metadata = EvaluationAttempt.builder().status(AttemptStatus.valueOf(status)).gradeClosed(closed).assignment(assignment).build();
        return new Attempt(!"IN_PROGRESS".equals(status), closed, policy(now).exposeCorrection(metadata, delivery.correctionAvailable()));
    }
    private Attempt academicAttempt(Request q, Delivery delivery) {
        var rows = jdbc.queryForList("""
            select status,grade_closed from t11b8_fixture_attempts
            where id=? and resource_id=? and recipient_id=? and revision_id=? and account_id=?
            """, q.attemptId(), delivery.resourceId(), delivery.recipientId(), q.revisionId(), q.recipientAccountId());
        require(rows.size() == 1);
        var row = rows.get(0);
        boolean terminal = "FINAL".equals(row.get("status"));
        boolean closed = Boolean.TRUE.equals(row.get("grade_closed"));
        return new Attempt(terminal, closed, terminal && closed && delivery.started() && delivery.correctionAvailable());
    }

    private EvaluationAccessPolicy policy(Instant now) {
        return new EvaluationAccessPolicy(Clock.fixed(now, ZoneOffset.UTC));
    }
    private Evaluation legacyEvaluation(long id) {
        return jdbc.queryForObject("select id,status,active from evaluations where id=?", (rs, n) ->
                Evaluation.builder().id(rs.getLong("id")).status(EvaluationStatus.valueOf(rs.getString("status"))).active(rs.getBoolean("active")).build(), id);
    }
    private List<EvaluationAssignment> legacyAssignments(Evaluation evaluation) {
        return jdbc.query("select id,start_at,due_at,active from evaluation_assignments where evaluation_id=?", (rs, n) ->
                EvaluationAssignment.builder().id(rs.getLong("id")).evaluation(evaluation).active(rs.getBoolean("active"))
                        .startAt(rs.getObject("start_at", LocalDateTime.class)).dueAt(rs.getObject("due_at", LocalDateTime.class)).build(), evaluation.getId());
    }
    private boolean exists(String sql, Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args)); }
    private static long number(Map<String, Object> row, String name) { return ((Number) row.get(name)).longValue(); }
    private static void require(boolean condition) { if (!condition) throw new Denied(); }
    private static final class Denied extends RuntimeException {}
}

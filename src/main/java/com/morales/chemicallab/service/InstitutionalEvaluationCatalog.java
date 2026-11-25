package com.morales.chemicallab.service;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Action;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Contract;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Projection;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Request;
import com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService.DraftFaculties;
import com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** Discovery for the same certified local execution unit as the versioned commands. */
@Service
public class InstitutionalEvaluationCatalog {
    public record Page<T>(List<T> items, Long nextAfter) {}
    public record ClassChoice(long institutionId, String institutionName, long classId, String className,
                              boolean student, boolean teacher) {}
    public record Delivery(long deliveryId, long evaluationId, long revisionId, String title, Instant startsAt,
                           Instant dueAt, boolean windowOpen, int remainingAttempts, boolean activeAttempt) {}
    public record AttemptRow(long attemptId, long evaluationId, long revisionId, long deliveryId, String title,
                             String status, int attemptNumber, String student, boolean gradeClosed) {}
    public record EnrollmentRow(long enrollmentId, String student) {}
    public record RevisionRow(long revisionId, long evaluationId, long version, int revisionNumber, String status,
                              String title, boolean canEdit, boolean canPublish, boolean canAssign) {}

    private record CandidateDelivery(Delivery delivery, Long attempt) {}
    private record CandidateAttempt(AttemptRow item, long account) {}
    private record CandidateRevision(long revision, long evaluation, long version, int number, String status,
                                     String title, boolean assigned) {}
    private static final int MAX_PAGE = 50;
    private static final int SCAN_LIMIT = 200;
    private static final String CURRENT_EPISODE = """
        e.status='ACTIVE' and e.state_since<=at.ts and e.valid_from<=at.ts and e.activated_at<=at.ts
        and at.ts<e.valid_until and (e.ended_at is null or at.ts<e.ended_at)
        """;
    private static final String CLASS_RIGHTS = """
        with at as (select statement_timestamp() ts), responsible as (
          select institution_id,class_id,membership_id,status,state_since,valid_from,valid_until,activated_at,ended_at
          from teaching_assignments where function='RESPONSABLE'
          union all
          select institution_id,class_id,membership_id,status,state_since,valid_from,valid_until,activated_at,ended_at
          from t11b6_co_assignments where function='CO_DOCENTE'
        ), rights as (
          select i.id institution_id,i.name institution_name,c.id class_id,c.name || ' · ' || c.local_code class_name,
            t11b6_role_valid(m.id,i.id,'ESTUDIANTE',at.ts,m.account_id) and exists (
              select 1 from enrollments e where e.institution_id=i.id and e.class_id=c.id
                and e.membership_id=m.id and %s
            ) student,
            t11b6_role_valid(m.id,i.id,'DOCENTE',at.ts,m.account_id) and exists (
              select 1 from responsible a where a.institution_id=i.id and a.class_id=c.id and a.membership_id=m.id
                and a.status='ACTIVE' and a.state_since<=at.ts and a.valid_from<=at.ts and a.activated_at<=at.ts
                and at.ts<a.valid_until and (a.ended_at is null or at.ts<a.ended_at)
            ) teacher
          from institution_memberships m
          join institutions i on i.id=m.institution_id
          join academic_classes c on c.institution_id=i.id
          join academic_periods p on p.id=c.period_id and p.institution_id=i.id
          cross join at
          where m.account_id=? and i.status='ACTIVE' and c.status='ACTIVE' and p.status='OPEN'
            and c.state_since<=at.ts and p.state_since<=at.ts and p.valid_from<=at.ts and at.ts<p.valid_until
        )
        """.formatted(CURRENT_EPISODE);

    private final JdbcTemplate jdbc;
    private final NewEvaluationExecutionService execution;
    private final EvaluationDeliveryAccessService access;
    private final InstitutionalEvaluationAuthoringService authoring;
    private final InstitutionalEvaluationAssignmentService assignment;
    private final EvaluationRuntime runtime;
    private final EvaluationRuntimeCatalog nativeCatalog;

    public InstitutionalEvaluationCatalog(JdbcTemplate jdbc, NewEvaluationExecutionService execution,
            EvaluationDeliveryAccessService access, InstitutionalEvaluationAuthoringService authoring,
            InstitutionalEvaluationAssignmentService assignment, EvaluationRuntime runtime, EvaluationRuntimeCatalog nativeCatalog) {
        this.jdbc = jdbc;
        this.execution = execution;
        this.access = access;
        this.authoring = authoring;
        this.assignment = assignment;
        this.runtime = runtime;
        this.nativeCatalog = nativeCatalog;
    }

    public Page<ClassChoice> classes(SessionPrincipal actor, long after, int limit) {
        validate(after, limit);
        if (runtime.installed()) return nativeCatalog.classes(actor,after,limit);
        return execution.unit(actor, false, ctl -> page(jdbc.query(CLASS_RIGHTS + """
            select * from rights where class_id>? and (student or teacher) order by class_id limit ?
            """, (rs, row) -> new ClassChoice(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getBoolean(5), rs.getBoolean(6)), actor.userId(), after, limit + 1), limit, ClassChoice::classId));
    }

    public Page<Delivery> deliveries(SessionPrincipal actor, long institution, long clazz, long after, int limit) {
        validate(after, limit);
        if (runtime.installed()) return nativeCatalog.deliveries(actor,institution,clazz,after,limit);
        return execution.unit(actor, false, ctl -> {
            requireClass(actor, institution, clazz, false);
            var candidates = jdbc.query("""
                with at as (select statement_timestamp() ts)
                select d.id,s.evaluation_id,d.revision_id,v.payload->'resource'->>'title',d.starts_at,d.due_at,
                       availability.is_open,s.max_attempts-ledger.used,ledger.active,ledger.latest
                from t11b8_deliveries d
                join t11b8_resources s on s.id=d.resource_id and s.institution_id=d.institution_id
                join t11b8_revisions v on v.id=d.revision_id and v.resource_id=s.id
                join t11b8_recipients r on r.delivery_id=d.id and r.resource_id=s.id and r.institution_id=d.institution_id
                join enrollments e on e.id=r.enrollment_id and e.institution_id=d.institution_id and e.class_id=d.class_id
                join institution_memberships m on m.id=e.membership_id and m.institution_id=e.institution_id
                    and m.account_id=r.account_id
                join t11b10_versions head on head.source_table='t11b8_recipients'
                    and head.source_pk=jsonb_build_object('id',r.id) and head.resolution->>'scope'='INSTITUTION'
                    and head.resolution->'institutionId'=to_jsonb(d.institution_id)
                    and head.resolution->'classId'=to_jsonb(d.class_id)
                    and head.resolution->'accountId'=to_jsonb(r.account_id)
                    and head.resolution->'enrollmentId'=to_jsonb(e.id)
                    and not exists(select 1 from t11b10_versions newer where newer.predecessor_id=head.id)
                cross join at
                cross join lateral (select d.status='OPEN' and s.availability='AVAILABLE'
                    and (d.due_at is null or at.ts<d.due_at) as is_open) availability
                cross join lateral (
                    select count(*) used,coalesce(bool_or(a.status='IN_PROGRESS'),false) active,
                        max(a.id) filter(where a.delivery_id=d.id and a.revision_id=d.revision_id
                            and a.enrollment_id=e.id and a.recipient_id=r.id) latest
                    from %s.attempts a where a.resource_id=s.id and a.account_id=r.account_id
                ) ledger
                where d.institution_id=? and d.class_id=? and d.id>? and r.account_id=? and r.status='ADMITTED'
                    and s.creation='EXPLICIT_NEW' and v.status='PUBLISHED' and v.published_at<=at.ts
                    and d.starts_at<=at.ts and (availability.is_open or ledger.latest is not null)
                    and %s
                order by d.id limit ?
                """.formatted(ctl, CURRENT_EPISODE), (rs, row) -> new CandidateDelivery(
                    new Delivery(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), instant(rs.getTimestamp(5)),
                        instant(rs.getTimestamp(6)), rs.getBoolean(7), Math.max(0, rs.getInt(8)), rs.getBoolean(9)),
                    rs.getObject(10, Long.class)), institution, clazz, after, actor.userId(), SCAN_LIMIT + 1);
            return filtered(candidates, limit, candidate -> {
                var item = candidate.delivery();
                Long attempt = candidate.attempt();
                Action action = attempt == null ? Action.READ_DELIVERY : Action.READ_OWN_ATTEMPT;
                return permits(() -> {
                    var decision = access.authorizeExecution(actor, new Request(Contract.NEW, action, institution, clazz,
                        item.evaluationId(), item.deliveryId(), item.revisionId(), actor.userId(), attempt));
                    if (attempt == null && decision.projection() != Projection.DELIVERY_WITHOUT_CORRECTION) throw denied();
                });
            }, CandidateDelivery::delivery, Delivery::deliveryId);
        });
    }

    public Page<AttemptRow> attempts(SessionPrincipal actor, long institution, long clazz, Long delivery,
                                     boolean teacher, long after, int limit) {
        validate(after, limit);
        if (!teacher && (delivery == null || delivery <= 0)) throw badPage();
        if (runtime.installed()) return nativeCatalog.attempts(actor,institution,clazz,delivery,teacher,after,limit);
        return execution.unit(actor, false, ctl -> {
            requireClass(actor, institution, clazz, teacher);
            String actorFilter = teacher ? "a.account_id<>? and a.status<>'IN_PROGRESS'" : "a.account_id=? and a.delivery_id=?";
            var args = new ArrayList<>(List.of(institution, clazz, after, actor.userId()));
            if (!teacher) args.add(delivery);
            args.add((long) SCAN_LIMIT + 1);
            var candidates = jdbc.query("""
                select a.id,a.evaluation_id,a.revision_id,a.delivery_id,v.payload->'resource'->>'title',
                       a.status,a.attempt_number,u.username,a.grade_closed,a.account_id
                from %s.attempts a join user_accounts u on u.id=a.account_id
                join t11b8_revisions v on v.id=a.revision_id and v.resource_id=a.resource_id
                where a.institution_id=? and a.class_id=? and a.id>? and %s
                order by a.id limit ?
                """.formatted(ctl, actorFilter), (rs, row) -> new CandidateAttempt(
                    new AttemptRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7), teacher ? rs.getString(8) : null, teacher && rs.getBoolean(9)),
                    rs.getLong(10)), args.toArray());
            return filtered(candidates, limit, candidate -> {
                var item = candidate.item();
                return permits(() -> access.authorizeExecution(actor, new Request(Contract.NEW,
                    teacher ? Action.REVIEW_ATTEMPT : Action.READ_OWN_ATTEMPT, institution, clazz,
                    item.evaluationId(), item.deliveryId(), item.revisionId(), candidate.account(), item.attemptId())));
            }, CandidateAttempt::item, AttemptRow::attemptId);
        });
    }

    public Page<EnrollmentRow> enrollments(SessionPrincipal actor, long institution, long clazz, long after, int limit) {
        validate(after, limit);
        if (runtime.installed()) return nativeCatalog.enrollments(actor,institution,clazz,after,limit);
        return execution.unit(actor, false, ctl -> {
            requireClass(actor, institution, clazz, true);
            return page(jdbc.query("""
                with at as (select statement_timestamp() ts)
                select e.id,u.username from enrollments e
                join institution_memberships m on m.id=e.membership_id and m.institution_id=e.institution_id
                join user_accounts u on u.id=m.account_id
                cross join at
                where e.institution_id=? and e.class_id=? and e.id>? and %s
                    and t11b6_role_valid(m.id,e.institution_id,'ESTUDIANTE',at.ts,u.id)
                order by e.id limit ?
                """.formatted(CURRENT_EPISODE), (rs, row) -> new EnrollmentRow(rs.getLong(1), rs.getString(2)),
                institution, clazz, after, limit + 1), limit, EnrollmentRow::enrollmentId);
        });
    }

    public Page<RevisionRow> revisions(SessionPrincipal actor, long institution, long clazz, long after, int limit) {
        validate(after, limit);
        if (runtime.installed()) return nativeCatalog.revisions(actor,institution,clazz,after,limit);
        return authoring.unit(actor, false, g -> {
            var member = authoring.member(actor, new Scope(institution, clazz, null, null));
            String h = assignment.assignmentEnabled()
                ? InstitutionalFixtureUnit.assignmentControl(jdbc, "t03_" + g.substring(5)) : null;
            var candidates = jdbc.query("""
                select v.id,s.evaluation_id,v.version,v.revision_number,v.status,v.payload->'resource'->>'title',
                       exists(select 1 from t11b8_deliveries d where d.revision_id=v.id
                         and d.institution_id=s.institution_id and d.class_id=?)
                from t11b8_revisions v join t11b8_resources s on s.id=v.resource_id and s.institution_id=v.institution_id
                where s.institution_id=? and v.id>? and s.creation='EXPLICIT_NEW' and s.availability='AVAILABLE'
                    and v.status in ('DRAFT','PUBLISHED') and (
                      (s.author_account_id=? and s.author_membership_id=?) or exists(
                        select 1 from %s.grants x where x.resource_id=s.id and x.institution_id=s.institution_id
                        and x.membership_id=? and x.status='ACTIVE' and x.revoked_at is null
                        and x.valid_from<=statement_timestamp() and statement_timestamp()<x.valid_until
                      )
                    ) order by v.id limit ?
                """.formatted(g), (rs, row) -> new CandidateRevision(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                    rs.getInt(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)),
                clazz, institution, after, actor.userId(), member.id(), member.id(), SCAN_LIMIT + 1);
            var rows = new ArrayList<RevisionRow>();
            int scanned = 0;
            for (var item : candidates) {
                if (++scanned > SCAN_LIMIT) throw scanLimit();
                var scope = new Scope(institution, clazz, item.evaluation(), item.revision());
                boolean draft = item.status().equals("DRAFT");
                DraftFaculties faculties = draft ? permitted(() -> authoring.draftFaculties(g, actor, scope, member)) : null;
                boolean edit = faculties != null && faculties.edit();
                boolean publish = faculties != null && faculties.publish();
                boolean assign = !draft && h != null && permits(() -> assignment.authorize(actor, scope, g, h, member));
                if (edit || publish || assign) rows.add(new RevisionRow(item.revision(), item.evaluation(), item.version(),
                    item.number(), item.status(), item.title(), edit, publish, assign && !item.assigned()));
                if (rows.size() > limit) break;
            }
            return page(rows, limit, RevisionRow::revisionId);
        });
    }

    private void requireClass(SessionPrincipal actor, long institution, long clazz, boolean teacher) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(CLASS_RIGHTS + """
            select exists(select 1 from rights where institution_id=? and class_id=? and %s)
            """.formatted(teacher ? "teacher" : "student"), Boolean.class, actor.userId(), institution, clazz))) throw denied();
    }

    private static boolean permits(Runnable authorize) {
        return permitted(() -> {
            authorize.run();
            return true;
        }) != null;
    }

    private static <T> T permitted(Supplier<T> authorize) {
        try {
            return authorize.get();
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode() != HttpStatus.FORBIDDEN) throw failure;
            return null;
        }
    }

    private static <C,T> Page<T> filtered(List<C> candidates, int limit, Predicate<C> authorized,
                                        java.util.function.Function<C,T> project, ToLongFunction<T> id) {
        var rows = new ArrayList<T>();
        int scanned = 0;
        for (C item : candidates) {
            if (++scanned > SCAN_LIMIT) throw scanLimit();
            if (authorized.test(item)) rows.add(project.apply(item));
            if (rows.size() > limit) break;
        }
        return page(rows, limit, id);
    }

    static <T> Page<T> page(List<T> rows, int limit, ToLongFunction<T> id) {
        int size = Math.min(rows.size(), limit);
        return new Page<>(List.copyOf(rows.subList(0, size)), rows.size() > limit ? id.applyAsLong(rows.get(size - 1)) : null);
    }

    private static void validate(long after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_PAGE) throw badPage();
    }

    private static ResponseStatusException badPage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Página inválida.");
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Operación no autorizada.");
    }

    private static ResponseStatusException scanLimit() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Consulta fuera del límite del entorno local.");
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

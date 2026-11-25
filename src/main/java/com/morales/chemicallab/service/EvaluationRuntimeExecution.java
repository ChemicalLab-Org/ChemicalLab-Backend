package com.morales.chemicallab.service;

import com.morales.chemicallab.security.SessionPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static com.morales.chemicallab.service.EvaluationRuntime.*;

@Service
public class EvaluationRuntimeExecution {
    record Resolved(Map<String,Object> binding, Projection projection, Instant observed) {}
    private final EvaluationRuntime runtime;
    private final JdbcTemplate jdbc;

    public EvaluationRuntimeExecution(EvaluationRuntime runtime, JdbcTemplate jdbc) {
        this.runtime=runtime;
        this.jdbc=jdbc;
    }

    Resolved resolve(SessionPrincipal actor, Request scope) {
        Rights rights=runtime.rights(actor,scope.institutionId(),scope.classId());
        boolean own=actor.userId()==scope.recipientAccountId() && rights.student();
        boolean teacher=rights.teacher() && actor.userId()!=scope.recipientAccountId();
        switch (scope.action()) {
            case READ_DELIVERY -> require(own || rights.teacher() || rights.admin());
            case REVIEW_ATTEMPT, GRADE_ATTEMPT -> require(teacher);
            default -> require(own);
        }
        var rows=jdbc.queryForList("""
            with t as (select statement_timestamp() at)
            select d.resource_id,r.id recipient_id,r.enrollment_id,v.payload::text,v.payload_checksum,s.max_attempts,d.due_at,
                   1::bigint root_version,t.at observed,d.starts_at<=t.at started,
                   d.starts_at<=t.at and t.at<d.due_at available,
                   a.status attempt_status, a.grade_closed,
                   case when ? then
                     not exists(select 1 from evaluation_runtime.deliveries w where w.resource_id=s.id and w.due_at>t.at)
                     and not exists(select 1 from evaluation_runtime.attempts x where x.resource_id=s.id and not x.grade_closed)
                   else false end root_closed
            from evaluation_runtime.deliveries d
            join evaluation_runtime.resources s on s.id=d.resource_id
            join evaluation_runtime.revisions v on v.id=d.revision_id and v.status='PUBLISHED'
            join evaluation_runtime.recipients r on r.delivery_id=d.id and r.account_id=?
            join evaluation_runtime.enrollments e on e.id=r.enrollment_id
            join evaluation_runtime.memberships m on m.id=e.membership_id
            join user_accounts u on u.id=m.account_id
            join evaluation_runtime.roles g on g.membership_id=m.id and g.role='ESTUDIANTE'
            left join evaluation_runtime.attempts a on a.recipient_id=r.id and a.id=?
            cross join t where d.id=? and d.resource_id=? and d.revision_id=? and d.institution_id=? and d.class_id=?
                and v.published_at<=t.at and (?::bigint is null or a.id is not null) and %s and %s
            for share of u
            """.formatted(ROLE,EPISODE), scope.action()==Action.READ_CORRECTION,scope.recipientAccountId(),scope.attemptId(),
            scope.deliveryId(),scope.evaluationId(),scope.revisionId(),scope.institutionId(),scope.classId(),scope.attemptId());
        require(rows.size()==1);
        var row=rows.get(0);
        boolean available=Boolean.TRUE.equals(row.get("available"));
        boolean started=Boolean.TRUE.equals(row.get("started"));
        String status=(String)row.get("attempt_status");
        boolean active="IN_PROGRESS".equals(status);
        boolean closed=Boolean.TRUE.equals(row.get("grade_closed"));
        Projection projection=switch (scope.action()) {
            case READ_DELIVERY -> {
                if (own && available) yield Projection.DELIVERY_WITHOUT_CORRECTION;
                require(rights.teacher() || rights.admin());
                yield Projection.SUPERVISION_METADATA;
            }
            case START_OWN_ATTEMPT -> {
                require(available);
                yield Projection.DELIVERY_WITHOUT_CORRECTION;
            }
            case READ_OWN_ATTEMPT, SUBMIT_OWN_ATTEMPT, SAVE_OWN_ANSWER -> {
                require(started && status!=null);
                if (scope.action()==Action.SAVE_OWN_ANSWER) require(available && active);
                if (scope.action()==Action.SUBMIT_OWN_ATTEMPT) require(active);
                yield Projection.OWN_ATTEMPT_WITHOUT_CORRECTION;
            }
            case REVIEW_ATTEMPT, GRADE_ATTEMPT -> {
                require(status!=null && !active);
                if (scope.action()==Action.GRADE_ATTEMPT) require(!closed);
                yield Projection.TEACHER_REVIEW;
            }
            case READ_CORRECTION -> {
                require(started && closed && "GRADED".equals(status) && Boolean.TRUE.equals(row.get("root_closed")));
                yield Projection.OWN_CORRECTION;
            }
            default -> throw denied();
        };
        return new Resolved(row,projection,((Timestamp)row.get("observed")).toInstant());
    }
}

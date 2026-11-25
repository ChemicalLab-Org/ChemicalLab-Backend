package com.morales.chemicallab.service;

import com.morales.chemicallab.security.SessionPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.morales.chemicallab.service.EvaluationRuntime.*;
import static com.morales.chemicallab.service.InstitutionalEvaluationCatalog.*;

@Service
public class EvaluationRuntimeCatalog {
    private static final String RECIPIENT = """
        join evaluation_runtime.enrollments e on e.id=r.enrollment_id
        join evaluation_runtime.memberships m on m.id=e.membership_id
        join evaluation_runtime.roles g on g.membership_id=m.id and g.role='ESTUDIANTE'
        join user_accounts u on u.id=m.account_id
        cross join t
        """;
    private final JdbcTemplate jdbc;
    private final EvaluationRuntime runtime;

    public EvaluationRuntimeCatalog(JdbcTemplate jdbc,EvaluationRuntime runtime) {
        this.jdbc=jdbc;
        this.runtime=runtime;
    }

    Page<ClassChoice> classes(SessionPrincipal actor,long after,int limit) {
        return runtime.unit(actor,null,null,false,null,()->page(jdbc.query(RIGHTS+"""
            select institution_id,institution_name,class_id,class_name,student,teacher
            from rights where class_id>? and (student or teacher) order by class_id limit ?
            """,(rs,n)->new ClassChoice(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getBoolean(5),
            rs.getBoolean(6)),actor.userId(),after,limit+1),limit,ClassChoice::classId));
    }

    Page<Delivery> deliveries(SessionPrincipal actor,long institution,long clazz,long after,int limit) {
        return runtime.unit(actor,institution,null,false,null,()->{
            require(runtime.rights(actor,institution,clazz).student());
            return page(jdbc.query("""
                with t as (select statement_timestamp() at)
                select d.id,d.resource_id,d.revision_id,v.payload->'resource'->>'title',d.starts_at,d.due_at,
                    t.at<d.due_at,s.max_attempts-ledger.used,ledger.active
                from evaluation_runtime.recipients r join evaluation_runtime.deliveries d on d.id=r.delivery_id
                join evaluation_runtime.revisions v on v.id=d.revision_id and v.status='PUBLISHED'
                join evaluation_runtime.resources s on s.id=d.resource_id
                %s
                cross join lateral (
                  select count(*) used,coalesce(bool_or(a.status='IN_PROGRESS'),false) active,
                    bool_or(a.recipient_id=r.id) had_attempt
                  from evaluation_runtime.attempts a where a.resource_id=s.id and a.account_id=r.account_id
                ) ledger
                where r.account_id=? and r.institution_id=? and r.class_id=? and d.id>?
                  and d.starts_at<=t.at and (t.at<d.due_at or ledger.had_attempt) and %s and %s
                order by d.id limit ?
                """.formatted(RECIPIENT,ROLE,EPISODE),(rs,n)->new Delivery(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant(),rs.getBoolean(7),
                rs.getInt(8),rs.getBoolean(9)),actor.userId(),institution,clazz,after,limit+1),limit,Delivery::deliveryId);
        });
    }

    Page<AttemptRow> attempts(SessionPrincipal actor,long institution,long clazz,Long delivery,boolean teacher,long after,int limit) {
        return runtime.unit(actor,institution,null,false,null,()->{
            var permissions=runtime.rights(actor,institution,clazz);
            require(teacher?permissions.teacher():permissions.student());
            var parameters=new ArrayList<Long>(List.of(institution,clazz,after,actor.userId()));
            String filter=teacher?"a.account_id<>? and a.status<>'IN_PROGRESS'":"a.account_id=? and a.delivery_id=?";
            if (!teacher) parameters.add(delivery);
            parameters.add((long)limit+1);
            return page(jdbc.query("""
                with t as (select statement_timestamp() at)
                select a.id,a.evaluation_id,a.revision_id,a.delivery_id,v.payload->'resource'->>'title',
                    a.status,a.attempt_number,u.username,a.grade_closed
                from evaluation_runtime.attempts a
                join evaluation_runtime.recipients r on r.id=a.recipient_id
                join evaluation_runtime.revisions v on v.id=a.revision_id
                %s
                where a.institution_id=? and a.class_id=? and a.id>? and %s and %s and %s
                order by a.id limit ?
                """.formatted(RECIPIENT,ROLE,EPISODE,filter),(rs,n)->new AttemptRow(rs.getLong(1),rs.getLong(2),
                rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getString(6),rs.getInt(7),
                teacher?rs.getString(8):null,teacher&&rs.getBoolean(9)),parameters.toArray()),limit,AttemptRow::attemptId);
        });
    }

    Page<EnrollmentRow> enrollments(SessionPrincipal actor,long institution,long clazz,long after,int limit) {
        return runtime.unit(actor,institution,null,false,null,()->{
            require(runtime.rights(actor,institution,clazz).teacher());
            return page(jdbc.query("""
                with t as (select statement_timestamp() at)
                select e.id,u.username from evaluation_runtime.enrollments e
                join evaluation_runtime.memberships m on m.id=e.membership_id
                join evaluation_runtime.roles g on g.membership_id=m.id and g.role='ESTUDIANTE'
                join user_accounts u on u.id=m.account_id cross join t
                where e.institution_id=? and e.class_id=? and e.id>? and %s and %s
                order by e.id limit ?
                """.formatted(ROLE,EPISODE),(rs,n)->new EnrollmentRow(rs.getLong(1),rs.getString(2)),institution,clazz,after,
                limit+1),limit,EnrollmentRow::enrollmentId);
        });
    }

    Page<RevisionRow> revisions(SessionPrincipal actor,long institution,long clazz,long after,int limit) {
        return runtime.unit(actor,institution,null,false,null,()->{
            var rights=runtime.rights(actor,institution,clazz);
            require(rights.teachingRole());
            return page(jdbc.query("""
                with selected as (
                  select v.id,v.resource_id,v.version,v.revision_number,v.status,v.payload->'resource'->>'title' title,
                    v.status='DRAFT' and (owner.author and owner.origin or 'EDIT_DRAFT'=any(g.faculties)) edit,
                    v.status='DRAFT' and (owner.author and owner.origin or 'PUBLISH'=any(g.faculties)) publish,
                    v.status='PUBLISHED' and ? and (owner.author or 'ASSIGN'=any(g.faculties)) assign,
                    exists(select 1 from evaluation_runtime.deliveries d where d.revision_id=v.id and d.class_id=?) assigned,
                    exists(select 1 from evaluation_runtime.deliveries w where w.resource_id=s.id)
                    and not exists(select 1 from evaluation_runtime.deliveries w
                        where w.resource_id=s.id and w.due_at>statement_timestamp()) closed
                  from evaluation_runtime.revisions v join evaluation_runtime.resources s on s.id=v.resource_id
                  cross join lateral (select ? and s.author_membership_id=? and s.author_account_id=? author,
                    s.origin_class_id=? origin) owner
                  cross join lateral (select array(select x.faculty from evaluation_runtime.faculties x
                    where x.resource_id=s.id and x.membership_id=? and x.revoked_at is null
                    and x.valid_from<=statement_timestamp() and statement_timestamp()<x.valid_until) faculties) g
                  where s.institution_id=? and v.id>?
                )
                select id,resource_id,version,revision_number,status,title,edit,publish,assign and not assigned and not closed
                from selected where edit or publish or assign order by id limit ?
                """,(rs,n)->new RevisionRow(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getInt(4),rs.getString(5),
                rs.getString(6),rs.getBoolean(7),rs.getBoolean(8),rs.getBoolean(9)),rights.teacher(),clazz,rights.teacher(),rights.membership(),actor.userId(),
                clazz,rights.membership(),institution,after,limit+1),limit,RevisionRow::revisionId);
        });
    }
}

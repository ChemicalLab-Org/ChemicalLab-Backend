package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.AcademicRepository;
import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.LongStream;

/** Trusted internal infrastructure only; no endpoint, seeder, scheduler or authorization.
 * Lock order: sorted membership IDs, institution, period, class, relation.
 * JDBC business/history and T08 SUCCESS join the existing transaction manager.
 * Effective instants are supplied by the trusted caller; T12 must use server time for access.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AcademicMaintenanceService {
    private final JdbcTemplate jdbc;
    private final AcademicRepository repository;
    private final AuditLogService audit;

    public AcademicPeriod createPeriod(long institutionId,String code,String name,Instant from,Instant until,Instant at) {
        lockInstitution(institutionId);
        long id=jdbc.queryForObject("""
            insert into academic_periods(institution_id,local_code,name,valid_from,valid_until,state_since,changed_by,change_reason)
            values(?,?,?,?,?,?,?,'CREATED') returning id
            """,Long.class,institutionId,code,name,time(from),time(until),time(at),actor());
        evidence(LogEventType.ACADEMIC_PERIOD_CREATED,"AcademicPeriod",id,institutionId);
        return repository.period(id);
    }
    public AcademicPeriod transitionPeriod(long id,AcademicPeriod.Status status,Instant at) {
        var p=repository.period(id);lockInstitution(p.institutionId());lock("academic_periods",id);
        jdbc.update("update academic_periods set status=?,state_since=?,changed_by=?,change_reason=? where id=?",
            Objects.requireNonNull(status).name(),time(at),actor(),status.name(),id);
        evidence(LogEventType.ACADEMIC_PERIOD_STATE_CHANGED,"AcademicPeriod",id,p.institutionId());
        return repository.period(id);
    }
    public AcademicClass createClass(long institutionId,long periodId,String code,String name,String course,String grade,String section,Instant at) {
        lockInstitution(institutionId);lock("academic_periods",periodId);
        long id=jdbc.queryForObject("""
            insert into academic_classes(institution_id,period_id,local_code,name,course,grade,section,state_since,changed_by,change_reason)
            values(?,?,?,?,?,?,?,?,?,'CREATED') returning id
            """,Long.class,institutionId,periodId,code,name,course,grade,section,time(at),actor());
        evidence(LogEventType.ACADEMIC_CLASS_CREATED,"AcademicClass",id,institutionId);
        return repository.academicClass(id);
    }
    public AcademicClass transitionClass(long id,AcademicClass.Status status,Instant at) {
        var c=repository.academicClass(id);lockClass(c.institutionId(),id);
        jdbc.update("update academic_classes set status=?,state_since=?,changed_by=?,change_reason=? where id=?",
            Objects.requireNonNull(status).name(),time(at),actor(),status.name(),id);
        evidence(LogEventType.ACADEMIC_CLASS_STATE_CHANGED,"AcademicClass",id,c.institutionId());
        return repository.academicClass(id);
    }
    public TeachingAssignment planAssignment(long institutionId,long classId,long membershipId,Instant from,Instant until,Instant at) {
        lockRelations(institutionId,classId,membershipId);
        long id=jdbc.queryForObject("""
            insert into teaching_assignments(institution_id,class_id,membership_id,valid_from,valid_until,state_since,changed_by,change_reason)
            values(?,?,?,?,?,?,?,'CREATED') returning id
            """,Long.class,institutionId,classId,membershipId,time(from),time(until),time(at),actor());
        evidence(LogEventType.TEACHING_ASSIGNMENT_CREATED,"TeachingAssignment",id,institutionId);
        return repository.assignment(id);
    }
    public TeachingAssignment transitionAssignment(long id,TeachingAssignment.Status status,Instant at) {
        var r=repository.assignment(id);lockRelations(r.institutionId(),r.classId(),r.membershipId());lock("teaching_assignments",id);
        transitionRelation("teaching_assignments",id,Objects.requireNonNull(status).name(),at);
        evidence(LogEventType.TEACHING_ASSIGNMENT_STATE_CHANGED,"TeachingAssignment",id,r.institutionId());
        return repository.assignment(id);
    }
    /** End the predecessor and activate the successor atomically, without changing the class or enrollments.
     * A failed successor/evidence write also restores the predecessor and all its history.
     */
    public TeachingAssignment replaceResponsible(long previousId,long replacementMembershipId,Instant at,Instant until) {
        var old=repository.assignment(previousId);
        lockRelations(old.institutionId(),old.classId(),old.membershipId(),replacementMembershipId);
        old=repository.assignment(previousId);
        if(old.status()!=TeachingAssignment.Status.ACTIVE)throw new IllegalArgumentException("Responsible must be active");
        transitionAssignment(previousId,TeachingAssignment.Status.ENDED,at);
        var next=planAssignment(old.institutionId(),old.classId(),replacementMembershipId,at,until,at);
        return transitionAssignment(next.id(),TeachingAssignment.Status.ACTIVE,at);
    }
    public Enrollment createEnrollment(long institutionId,long classId,long membershipId,Instant from,Instant until,Instant at) {
        lockRelations(institutionId,classId,membershipId);
        int episode=jdbc.queryForObject("select coalesce(max(episode_number),0)+1 from enrollments where class_id=? and membership_id=?",
            Integer.class,classId,membershipId);
        long id=jdbc.queryForObject("""
            insert into enrollments(institution_id,class_id,membership_id,episode_number,valid_from,valid_until,state_since,changed_by,change_reason)
            values(?,?,?,?,?,?,?,?,'CREATED') returning id
            """,Long.class,institutionId,classId,membershipId,episode,time(from),time(until),time(at),actor());
        evidence(LogEventType.ENROLLMENT_CREATED,"Enrollment",id,institutionId);
        return repository.enrollment(id);
    }
    public Enrollment transitionEnrollment(long id,Enrollment.Status status,Instant at) {
        var r=repository.enrollment(id);lockRelations(r.institutionId(),r.classId(),r.membershipId());lock("enrollments",id);
        transitionRelation("enrollments",id,Objects.requireNonNull(status).name(),at);
        evidence(LogEventType.ENROLLMENT_STATE_CHANGED,"Enrollment",id,r.institutionId());
        return repository.enrollment(id);
    }
    private void transitionRelation(String table,long id,String status,Instant at) {
        // Table is a fixed internal literal, never input from a caller.
        jdbc.update("update "+table+"""
             set status=?,state_since=?,changed_by=?,change_reason=?,
             activated_at=case when activated_at is null and ?='ACTIVE' then ? else activated_at end,
             ended_at=case when ? in ('ENDED','COMPLETED','WITHDRAWN','TRANSFERRED') then ? else ended_at end where id=?
            """,status,time(at),actor(),status,status,time(at),status,time(at),id);
    }
    private void lockRelations(long institutionId,long classId,long... membershipIds) {
        LongStream.of(membershipIds).distinct().sorted().forEach(id->lock("institution_memberships",id));
        lockClass(institutionId,classId);
    }
    private void lockClass(long institutionId,long id) {
        lockInstitution(institutionId);
        var c=repository.academicClass(id);
        lock("academic_periods",c.periodId());lock("academic_classes",id);
    }
    private void lockInstitution(long id) { lock("institutions",id); }
    private void lock(String table,long id) {
        jdbc.queryForObject("select id from "+table+" where id=? for update",Long.class,id);
    }
    private void evidence(LogEventType event,String target,long id,long institutionId) {
        audit.recordInfo(event,target,id,null,null,null,"institutionId="+institutionId);
    }
    private static Timestamp time(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }
    private static Long actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.isAuthenticated() && auth.getPrincipal() instanceof SessionPrincipal p?p.userId():null;
    }
}

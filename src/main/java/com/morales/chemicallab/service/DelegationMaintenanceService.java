package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.*;
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

/** Internal P01 maintainer. No controller, scheduler, login hook or access resolver.
 * JDBC business, generated history/quota and mandatory T08 evidence share one transaction.
 * Times belong to trusted maintenance; future authorization must use server time (T12).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class DelegationMaintenanceService {
    private final JdbcTemplate jdbc;
    private final DelegationRepository repository;
    private final AcademicRepository academic;
    private final AuditLogService audit;

    public CoTeachingAssignment planCoTeacher(long institutionId,long classId,long membershipId,Instant from,Instant until,Instant at) {
        lockMembers(membershipId);lockClass(institutionId,classId);
        long id=jdbc.queryForObject("""
            insert into t11b6_co_assignments(institution_id,class_id,membership_id,valid_from,valid_until,state_since,changed_by,change_reason)
            values(?,?,?,?,?,?,?,'CREATED') returning id
            """,Long.class,institutionId,classId,membershipId,time(from),time(until),time(at),actor());
        evidence("CoTeachingAssignment",id,institutionId);
        return repository.coTeacher(id);
    }
    public CoTeachingAssignment transitionCoTeacher(long id,TeachingAssignment.Status status,Instant at) {
        var old=repository.coTeacher(id);lockMembers(old.membershipId());lockClass(old.institutionId(),old.classId());
        lock("t11b6_co_assignments",id);
        String next=Objects.requireNonNull(status).name();
        jdbc.update("""
            update t11b6_co_assignments set status=?,state_since=?,changed_by=?,change_reason=?,
             activated_at=case when activated_at is null and ?='ACTIVE' then ? else activated_at end,
             ended_at=case when ?='ENDED' then ? else ended_at end where id=?
            """,next,time(at),actor(),next,next,time(at),next,time(at),id);
        evidence("CoTeachingAssignment",id,old.institutionId());
        return repository.coTeacher(id);
    }
    public ResourceManagerGrant grantResource(long institutionId,ResourceManagerGrant.Kind kind,long resourceId,
            long teacherMembershipId,ResourceManagerGrant.Faculty faculty,long adminMembershipId,Instant from,Instant until,Instant at) {
        long account=requiredActor();
        lockMembers(teacherMembershipId,adminMembershipId);lock("institutions",institutionId);
        Objects.requireNonNull(kind);Objects.requireNonNull(faculty);
        long id=jdbc.queryForObject("""
            insert into t11b6_resource_grants(institution_id,concept_id,evaluation_id,membership_id,faculty,
             valid_from,valid_until,granted_at,granted_by,grantor_membership_id,reason)
            values(?,?,?,?,?,?,?,?,?,?,'DESIGNATED') returning id
            """,Long.class,institutionId,kind==ResourceManagerGrant.Kind.CONCEPT?resourceId:null,
            kind==ResourceManagerGrant.Kind.EVALUATION?resourceId:null,teacherMembershipId,faculty.name(),
            time(from),time(until),time(at),account,adminMembershipId);
        evidence("ResourceManagerGrant",id,institutionId);
        return repository.grant(id);
    }
    public ResourceManagerGrant revokeResource(long id,long adminMembershipId,Instant at) {
        long account=requiredActor();var old=repository.grant(id);
        lockMembers(old.membershipId(),adminMembershipId);lock("institutions",old.institutionId());lock("t11b6_resource_grants",id);
        jdbc.update("""
            update t11b6_resource_grants set status='REVOKED',revoked_at=?,revoked_by=?,revoker_membership_id=?,reason='REMOVED' where id=?
            """,time(at),account,adminMembershipId,id);
        evidence("ResourceManagerGrant",id,old.institutionId());
        return repository.grant(id);
    }
    private void lockMembers(long... ids) {LongStream.of(ids).distinct().sorted().forEach(id->lock("institution_memberships",id));}
    private void lockClass(long institutionId,long classId) {
        lock("institutions",institutionId);var c=academic.academicClass(classId);
        lock("academic_periods",c.periodId());lock("academic_classes",classId);
    }
    private void lock(String table,long id) {jdbc.queryForObject("select id from "+table+" where id=? for update",Long.class,id);}
    private void evidence(String target,long id,long institutionId) {audit.recordInfo(LogEventType.ADMIN_ACTION,target,id,null,null,null,"institutionId="+institutionId);}
    private static Timestamp time(Instant value) {return Timestamp.from(Objects.requireNonNull(value));}
    private static Long actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null&&auth.isAuthenticated()&&auth.getPrincipal() instanceof SessionPrincipal p?p.userId():null;
    }
    private static long requiredActor() {
        Long id=actor();if(id==null)throw new IllegalStateException("Authenticated administrator provenance required");return id;
    }
}

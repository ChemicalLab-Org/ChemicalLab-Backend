package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.InstitutionRepository;
import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/** Trusted internal maintenance only. No controller, login hook, seeder or automatic backfill.
 * T12 must authorize future callers before exposing any institution workflow.
 * JDBC joins the existing JPA transaction manager and T08 evidence transaction.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InstitutionMaintenanceService {
    private final JdbcTemplate jdbc;
    private final InstitutionRepository repository;
    private final AuditLogService audit;

    public Institution createInstitution(String code,String name,String timezone) {
        long id=jdbc.queryForObject("insert into institutions(platform_code,name,timezone) values(?,?,?) returning id",
            Long.class,code,name,timezone);
        evidence(LogEventType.INSTITUTION_CREATED,"Institution",id,null);
        return repository.institution(id);
    }
    public Institution changeInstitutionStatus(long id,Institution.Status status) {
        Objects.requireNonNull(status);
        jdbc.queryForObject("select id from institutions where id=? for update",Long.class,id);
        if(repository.institution(id).status()==status)throw new IllegalArgumentException("State already set");
        jdbc.update("update institutions set status=? where id=?",status.name(),id);
        evidence(LogEventType.INSTITUTION_STATE_CHANGED,"Institution",id,null);
        return repository.institution(id);
    }
    public InstitutionMembership createMembership(long institutionId,long accountId,Instant from) {
        long id=jdbc.queryForObject("""
            insert into institution_memberships(institution_id,account_id,valid_from,state_since,changed_by,change_reason)
            values(?,?,?,?,?,'CREATED') returning id
            """,Long.class,institutionId,accountId,time(from),time(from),actor());
        evidence(LogEventType.MEMBERSHIP_CREATED,"InstitutionMembership",id,institutionId);
        return repository.membership(id);
    }
    public InstitutionMembership transitionMembership(long id,InstitutionMembership.Status status,Instant at) {
        Objects.requireNonNull(status);time(at);
        lockMembership(id);
        var old=repository.membership(id);
        String reason=switch(status) {
            case PENDING -> throw new IllegalArgumentException("Cannot return to pending");
            case ACTIVE -> switch(old.status()) {
                case PENDING -> "ACTIVATED"; case SUSPENDED -> "REACTIVATED"; case ENDED -> "READMITTED";
                case ACTIVE -> throw new IllegalArgumentException("State already set");
            };
            case SUSPENDED -> "SUSPENDED"; case ENDED -> "ENDED";
        };
        if(status==InstitutionMembership.Status.ENDED) {
            // Revoke before closing, in the same locked transaction. Readmission never restores grants.
            var ids=jdbc.queryForList("select id from membership_role_grants where membership_id=? and status='ACTIVE' order by id",Long.class,id);
            for(long grantId:ids)revokeLocked(grantId,at,"MEMBERSHIP_ENDED",old.institutionId());
        }
        jdbc.update("""
            update institution_memberships set status=?,state_since=?,
            valid_from=case when status='ENDED' then ? else valid_from end,
            valid_until=?,changed_by=?,change_reason=? where id=?
            """,status.name(),time(at),time(at),status==InstitutionMembership.Status.ENDED?time(at):null,actor(),reason,id);
        evidence(LogEventType.MEMBERSHIP_STATE_CHANGED,"InstitutionMembership",id,old.institutionId());
        return repository.membership(id);
    }
    public MembershipRoleGrant grantRole(long membershipId,MembershipRoleGrant.InstitutionalRole role,Instant from,Instant until) {
        Objects.requireNonNull(role);lockMembership(membershipId);
        long institutionId=repository.membership(membershipId).institutionId();
        long id=jdbc.queryForObject("""
            insert into membership_role_grants(membership_id,institution_id,role,valid_from,valid_until,granted_by)
            values(?,?,?,?,?,?) returning id
            """,Long.class,membershipId,institutionId,role.name(),time(from),until==null?null:time(until),actor());
        evidence(LogEventType.MEMBERSHIP_ROLE_GRANTED,"MembershipRoleGrant",id,institutionId);
        return repository.grant(id);
    }
    public MembershipRoleGrant revokeRole(long id,Instant at) {
        var grant=repository.grant(id);lockMembership(grant.membershipId());
        revokeLocked(id,at,"REMOVED",grant.institutionId());
        return repository.grant(id);
    }
    private void revokeLocked(long id,Instant at,String reason,long institutionId) {
        jdbc.update("update membership_role_grants set status='REVOKED',revoked_at=?,revoked_by=?,revocation_reason=? where id=?",
            time(at),actor(),reason,id);
        evidence(LogEventType.MEMBERSHIP_ROLE_REVOKED,"MembershipRoleGrant",id,institutionId);
    }
    private void lockMembership(long id) {
        jdbc.queryForObject("select id from institution_memberships where id=? for update",Long.class,id);
    }
    private void evidence(LogEventType event,String target,long id,Long institutionId) {
        audit.recordInfo(event,target,id,null,null,null,institutionId==null?null:"institutionId="+institutionId);
    }
    private static Timestamp time(Instant value) { return Timestamp.from(Objects.requireNonNull(value)); }
    private static Long actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.isAuthenticated() && auth.getPrincipal() instanceof SessionPrincipal p?p.userId():null;
    }
}

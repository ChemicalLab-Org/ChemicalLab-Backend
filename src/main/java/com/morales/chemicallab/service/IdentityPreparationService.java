package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.IdentityPreparationRepository;
import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/** Explicit fixture maps only. No controller, automatic import, admission or effective authority. */
@Service
@Transactional
@RequiredArgsConstructor
public class IdentityPreparationService {
    private final JdbcTemplate jdbc;
    private final IdentityPreparationRepository repository;
    private final AuditLogService audit;

    public PlatformRoleGrant prepareLegacyAdministrator(long accountId, String expectedSourceChecksum,
            String fixtureEvidence, Instant from, Instant until) {
        from=time(from).toInstant();until=until==null?null:time(until).toInstant();
        fixture();
        jdbc.queryForObject("select id from user_accounts where id=? for update",Long.class,accountId);
        var previous=jdbc.queryForList("select * from t11b5_admin_proofs where evidence=?",fixtureEvidence);
        if(!previous.isEmpty()) {
            var proof=previous.get(0);
            if(((Number)proof.get("account_id")).longValue()!=accountId || !proof.get("source_checksum").equals(expectedSourceChecksum))
                throw new IllegalArgumentException("Different explicit privilege map");
            var grant=repository.grant(jdbc.queryForObject("select id from t11b5_platform_role_grants where proof_id=?",Long.class,proof.get("id")));
            if(!grant.validFrom().equals(from) || !Objects.equals(grant.validUntil(),until)) throw new IllegalArgumentException("Different grant interval");
            return grant;
        }
        long proof=jdbc.queryForObject("insert into t11b5_admin_proofs(account_id,evidence,source_checksum,checked_by) values(?,?,?,?) returning id",
            Long.class,accountId,fixtureEvidence,expectedSourceChecksum,actor());
        long id=jdbc.queryForObject("insert into t11b5_platform_role_grants(account_id,proof_id,valid_from,valid_until,granted_by) values(?,?,?,?,?) returning id",
            Long.class,accountId,proof,time(from),until==null?null:time(until),actor());
        evidence("PlatformRoleGrant",id,null);
        return repository.grant(id);
    }
    public PlatformRoleGrant revokePlatformRole(long id,Instant at) {
        at=time(at).toInstant();
        fixture();
        long account=repository.grant(id).accountId();
        jdbc.queryForObject("select id from user_accounts where id=? for update",Long.class,account);
        var old=repository.grant(id);
        if(old.status()==PlatformRoleGrant.Status.REVOKED) {
            if(!old.revokedAt().equals(at)) throw new IllegalArgumentException("Different revocation");
            return old;
        }
        jdbc.update("update t11b5_platform_role_grants set status='REVOKED',revoked_at=?,revoked_by=?,revocation_reason='REMOVED' where id=?",time(at),actor(),id);
        evidence("PlatformRoleGrant",id,null);
        return repository.grant(id);
    }
    public AccountLoginIdentifier prepareIdentifier(long accountId,AccountLoginIdentifier.Kind kind,
            String expectedRawValue,String fixtureEvidence) {
        lockIdentifiers();Objects.requireNonNull(kind);
        Long prior=jdbc.query("select id,evidence from t11b5_login_identifiers where account_id=? and kind=? and value=?",
            r->{if(!r.next())return null;if(!r.getString("evidence").equals(fixtureEvidence))throw new IllegalArgumentException("Different identifier evidence");return r.getLong("id");},accountId,kind.name(),expectedRawValue);
        if(prior!=null)return repository.identifier(prior);
        long id=jdbc.queryForObject("insert into t11b5_login_identifiers(account_id,kind,value,comparison_value,evidence,recorded_by) values(?,?,?,?,?,?) returning id",
            Long.class,accountId,kind.name(),expectedRawValue,expectedRawValue,fixtureEvidence,actor());
        evidence("AccountLoginIdentifier",id,null);
        // A collision is a durable observation, not an exception that would erase the candidate.
        return repository.identifier(id);
    }
    public long reconcileIdentifier(long identifierId) {
        lockIdentifiers();
        var identifier=repository.identifier(identifierId);
        if(!identifier.status().equals("PREPARED"))throw new IllegalStateException("Identifier collision/stale source blocks reconciliation");
        Long prior=jdbc.query("select id,account_id from t11b5_login_bindings where comparison_value=?",
            r->{if(!r.next())return null;if(r.getLong("account_id")!=identifier.accountId())throw new IllegalStateException("Identifier owner conflict");return r.getLong("id");},identifier.comparisonValue());
        if(prior!=null)return prior;
        long id=jdbc.queryForObject("insert into t11b5_login_bindings(comparison_value,account_id,identifier_id,recorded_by) values(?,?,?,?) returning id",
            Long.class,identifier.comparisonValue(),identifier.accountId(),identifierId,actor());
        evidence("AccountLoginBinding",id,null);
        return id;
    }
    public AcademicCodeReservation reserveCode(long institutionId,long membershipId,long accountId,
            String exactCode,String fixtureEvidence) {
        fixture();
        jdbc.queryForObject("select id from institution_memberships where id=? for update",Long.class,membershipId);
        Long prior=jdbc.query("select id,code,evidence,account_id,institution_id from t11b5_academic_codes where membership_id=?",
            r->{if(!r.next())return null;
                if(!r.getString("code").equals(exactCode) || !r.getString("evidence").equals(fixtureEvidence)
                    || r.getLong("account_id")!=accountId || r.getLong("institution_id")!=institutionId)
                    throw new IllegalArgumentException("Code identity/evidence immutable; rename decision pending");
                return r.getLong("id");},membershipId);
        if(prior!=null)return repository.code(prior);
        long id=jdbc.queryForObject("insert into t11b5_academic_codes(institution_id,membership_id,account_id,code,evidence,reserved_by) values(?,?,?,?,?,?) returning id",
            Long.class,institutionId,membershipId,accountId,exactCode,fixtureEvidence,actor());
        evidence("AcademicCodeReservation",id,institutionId);
        return repository.code(id);
    }
    private void fixture(){jdbc.execute("select t11b5_fixture()");}
    private void lockIdentifiers(){fixture();jdbc.execute("lock table user_accounts,t11b5_login_identifiers in share row exclusive mode");}
    private void evidence(String type,long id,Long institutionId) {
        // Existing T08 ADMIN_ACTION avoids mutating any predecessor event CHECK/fingerprint.
        // Typed target plus immutable B5 history distinguishes preparation/revocation.
        audit.recordInfo(LogEventType.ADMIN_ACTION,type,id,null,null,null,institutionId==null?null:"institutionId="+institutionId);
    }
    private static Timestamp time(Instant at){return Timestamp.from(Objects.requireNonNull(at).truncatedTo(java.time.temporal.ChronoUnit.MICROS));}
    private static Long actor(){var a=SecurityContextHolder.getContext().getAuthentication();
        return a!=null && a.isAuthenticated() && a.getPrincipal() instanceof SessionPrincipal p?p.userId():null;}
}

package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.institution.GlobalAccessIdentifier;
import com.morales.chemicallab.repository.GlobalAccessIdentifierRepository;
import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static com.morales.chemicallab.entity.institution.GlobalAccessIdentifier.*;

/** Controlled fictitious maintenance only: no REST/STOMP/job/login caller or external verifier. */
@Service
@Transactional
@RequiredArgsConstructor
public class GlobalAccessIdentifierMaintenanceService {
    private final JdbcTemplate jdbc;
    private final GlobalAccessIdentifierRepository repository;
    private final AuditLogService audit;

    public GlobalAccessIdentifier prepare(long accountId,Kind kind,String exactValue,Origin origin,
            Long b5Candidate,UUID operationId,String fixtureEvidence) {
        lock();Long actor=actor(accountId);
        var rows=jdbc.queryForList("select id,account_id,kind,value,origin,candidate_id,evidence from t11b7_identifiers where operation_id=?",operationId);
        if(!rows.isEmpty()) {
            var row=rows.get(0);
            if(((Number)row.get("account_id")).longValue()!=accountId || !row.get("kind").equals(kind.name())
                || !row.get("value").equals(exactValue) || !row.get("origin").equals(origin.name())
                || !Objects.equals(row.get("candidate_id"),b5Candidate) || !row.get("evidence").equals(fixtureEvidence))
                throw new IllegalArgumentException("Operation already used for a different explicit map");
            return repository.identifier(((Number)row.get("id")).longValue());
        }
        long id=jdbc.queryForObject("insert into t11b7_identifiers(account_id,kind,value,comparison_value,origin,candidate_id,operation_id,evidence,recorded_by,changed_by) values(?,?,?,?,?,?,?,?,?,?) returning id",
            Long.class,accountId,kind.name(),exactValue,exactValue,origin.name(),b5Candidate,operationId,fixtureEvidence,actor,actor);
        success("GlobalAccessIdentifier",id);return repository.identifier(id);
    }
    /** This is an explicit maintainer approval, never an automatic choice of a candidate. */
    public long review(long identifierId,long expectedVersion,String fixtureEvidence) {
        lock();internal();var identifier=repository.identifier(identifierId);version(identifier,expectedVersion);
        var previous=jdbc.queryForList("select id,evidence from t11b7_reviews where identifier_id=? and identifier_version=? and fingerprint=md5(t11b7_inventory(?)::text)",identifierId,expectedVersion,identifierId);
        if(!previous.isEmpty()) {
            if(!previous.get(0).get("evidence").equals(fixtureEvidence))throw new IllegalArgumentException("Different review provenance");
            return ((Number)previous.get(0).get("id")).longValue();
        }
        long id=jdbc.queryForObject("insert into t11b7_reviews(identifier_id,identifier_version,fingerprint,observed,decision,evidence) values(?,?,'pending','[]','APPROVE',?) returning id",Long.class,identifierId,expectedVersion,fixtureEvidence);
        success("IdentifierConflictReview",id);return id;
    }
    /** SHA-256 of a fictitious receipt, supplied by the controlled fixture issuer; no raw codes. */
    public long issueFixtureProof(long identifierId,long expectedVersion,String receiptDigest,Instant expiresAt,String fixtureEvidence) {
        lock();internal();var identifier=repository.identifier(identifierId);version(identifier,expectedVersion);
        var prior=jdbc.queryForList("select id,identifier_version,receipt_digest,expires_at from t11b7_proofs where identifier_id=? and evidence=?",identifierId,fixtureEvidence);
        if(!prior.isEmpty()) {
            var row=prior.get(0);
            if(((Number)row.get("identifier_version")).longValue()!=expectedVersion || !row.get("receipt_digest").equals(receiptDigest)
                || !((Timestamp)row.get("expires_at")).toInstant().equals(expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
                throw new IllegalArgumentException("Different verification operation provenance");
            return ((Number)row.get("id")).longValue();
        }
        long id=jdbc.queryForObject("insert into t11b7_proofs(identifier_id,account_id,identifier_version,operation_id,receipt_digest,provider,evidence,expires_at) values(?,?,?,?,?,'FIXTURE_INTERNAL_V1',?,?) returning id",
            Long.class,identifierId,identifier.accountId(),expectedVersion,identifier.operationId(),receiptDigest,fixtureEvidence,Timestamp.from(expiresAt));
        success("IdentifierVerificationProof",id);return id;
    }
    public void attestFixtureProof(long proofId,String receiptDigest) {
        lock();internal();
        var previous=jdbc.queryForList("select receipt_digest from t11b7_attestations where proof_id=?",proofId);
        if(!previous.isEmpty()) {
            if(!previous.get(0).get("receipt_digest").equals(receiptDigest))throw new IllegalArgumentException("Different receipt");
            return;
        }
        jdbc.update("insert into t11b7_attestations(proof_id,receipt_digest) values(?,?)",proofId,receiptDigest);
        success("IdentifierVerificationProof",proofId);
    }
    public void revokeFixtureProof(long proofId) {
        lock();internal();
        if(jdbc.update("update t11b7_proofs set status='REVOKED',revoked_at=statement_timestamp() where id=? and status in ('ISSUED','VERIFIED')",proofId)!=1)
            throw new IllegalStateException("Proof absent or terminal");
        success("IdentifierVerificationProof",proofId);
    }
    public GlobalAccessIdentifier activate(long id,long expectedVersion,Long proofId) {
        lock();var identifier=repository.identifier(id);Long actor=actor(identifier.accountId());
        // A retry identifies the exact successful transition, not just the current state.
        if(identifier.status()==Status.ACTIVE && identifier.version()==expectedVersion+1 && Objects.equals(identifier.proofId(),proofId))return identifier;
        version(identifier,expectedVersion);update(id,expectedVersion,"ACTIVE",actor,proofId);
        success("GlobalAccessIdentifier",id);return repository.identifier(id);
    }
    public GlobalAccessIdentifier revokeEmail(long id,long expectedVersion) {
        lock();var identifier=repository.identifier(id);Long actor=actor(identifier.accountId());
        if(identifier.kind()!=Kind.EMAIL)throw new IllegalArgumentException("Only email links can be revoked");
        if(identifier.status()==Status.REVOKED && identifier.version()==expectedVersion+1)return identifier;
        version(identifier,expectedVersion);update(id,expectedVersion,"REVOKED",actor,identifier.proofId());
        success("GlobalAccessIdentifier",id);return repository.identifier(id);
    }
    public GlobalAccessIdentifier changeUsername(long oldId,long oldVersion,long preparedId,long preparedVersion) {
        lock();var old=repository.identifier(oldId);var next=repository.identifier(preparedId);Long actor=actor(old.accountId());
        if(old.accountId()!=next.accountId() || old.kind()!=Kind.USERNAME || next.kind()!=Kind.USERNAME || old.value().equals(next.value()))
            throw new IllegalArgumentException("Username change must preserve the original account and change its value");
        if(old.status()==Status.RESERVED && old.version()==oldVersion+1 && next.status()==Status.ACTIVE && next.version()==preparedVersion+1
            && Objects.equals(next.replacesId(),oldId) && Objects.equals(next.replacesVersion(),oldVersion+1))return next;
        version(old,oldVersion);version(next,preparedVersion);
        if(old.status()!=Status.ACTIVE || next.status()!=Status.PREPARED)throw new IllegalStateException("Invalid username change state");
        update(oldId,oldVersion,"RESERVED",actor,null);
        if(jdbc.update("update t11b7_identifiers set status='ACTIVE',version=version+1,changed_by=?,replaces_id=?,replaces_version=? where id=? and version=?",actor,oldId,oldVersion+1,preparedId,preparedVersion)!=1)
            throw new IllegalStateException("Stale replacement version");
        success("GlobalAccessIdentifier",oldId);success("GlobalAccessIdentifier",preparedId);
        return repository.identifier(preparedId);
    }
    private void update(long id,long version,String status,Long actor,Long proofId) {
        if(jdbc.update("update t11b7_identifiers set status=?,version=version+1,changed_by=?,proof_id=? where id=? and version=?",status,actor,proofId,id,version)!=1)
            throw new IllegalStateException("Stale identifier version");
    }
    private static void version(GlobalAccessIdentifier identifier,long expected) {
        if(identifier.version()!=expected)throw new IllegalStateException("Stale identifier version");
    }
    private void lock(){jdbc.execute("select t11b7_lock()");}
    private void success(String type,long id){audit.recordInfo(LogEventType.ADMIN_ACTION,type,id,null,null,null,null);}
    private static Long actor(long account) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null)return null; // Controlled internal fixture entry; no public caller exists.
        if(!authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof SessionPrincipal p) || p.userId()!=account)
            throw new IllegalStateException("Global identity requires the account itself");
        return p.userId();
    }
    private static void internal() {
        if(SecurityContextHolder.getContext().getAuthentication()!=null)throw new IllegalStateException("Controlled internal fixture maintainer required");
    }
}

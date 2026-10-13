package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.GlobalAccessIdentifierRepository;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.AuditUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.entity.institution.GlobalAccessIdentifier.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlobalAccessIdentifierDbTest {
    final DelegationPersistenceDbTest previous=new DelegationPersistenceDbTest();
    final ResourceReconciliationDbTest r=previous.r;
    final WhiteboardSecurityTransportDbTest f=previous.f;
    GlobalAccessIdentifierMaintenanceService service;
    GlobalAccessIdentifierRepository repository;
    String expansion;
    static final String RECEIPT="a".repeat(64);
    JdbcTemplate sql(){return f.sql();}
    long count(String q,Object... args){return sql().queryForObject(q,Long.class,args);}
    @BeforeAll void start() throws Exception {
        previous.start();service=f.app.getBean(GlobalAccessIdentifierMaintenanceService.class);repository=f.app.getBean(GlobalAccessIdentifierRepository.class);
        expansion=r.script("t11b7-v1");var before=previous.versions();
        previous.previous.base.tx().executeWithoutResult(s->{sql().execute(expansion);s.setRollbackOnly();});
        assertThat(sql().queryForObject("select to_regclass(current_schema()||'.t11b7_schema_version') is null",Boolean.class)).isTrue();
        sql().execute(expansion);sql().execute(expansion);assertThat(previous.versions()).isEqualTo(before);
    }
    @BeforeEach void fixture() throws Exception {previous.fixture();SecurityContextHolder.clearContext();}
    @AfterEach void close(){SecurityContextHolder.clearContext();f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    GlobalAccessIdentifier prepare(long account,Kind kind,String value,Origin origin) {return service.prepare(account,kind,value,origin,null,UUID.randomUUID(),"FIXTURE-MAP");}
    GlobalAccessIdentifier username(UserAccount account){return prepare(account.getId(),Kind.USERNAME,account.getUsername(),Origin.LEGACY);}
    GlobalAccessIdentifier email(UserAccount account,String value){return prepare(account.getId(),Kind.EMAIL,value,Origin.LINK);}
    GlobalAccessIdentifier reviewed(GlobalAccessIdentifier i){service.review(i.id(),i.version(),"FIXTURE-REVIEW");return i;}
    GlobalAccessIdentifier activeUsername(UserAccount account){var i=reviewed(username(account));return service.activate(i.id(),i.version(),null);}
    long proof(GlobalAccessIdentifier i){long id=service.issueFixtureProof(i.id(),i.version(),RECEIPT,Instant.now().plusSeconds(120),"FIXTURE-PROOF");service.attestFixtureProof(id,RECEIPT);return id;}
    GlobalAccessIdentifier activeEmail(UserAccount account,String value){var i=reviewed(email(account,value));return service.activate(i.id(),i.version(),proof(i));}
    Map<String,String> state(){return previous.signatures("t11b7_identifiers","t11b7_proofs","t11b7_attestations","t11b7_claims","t11b7_conflicts","t11b7_reviews","t11b7_events","t11b7_usage","system_logs","storage_usage");}
    @Test void accountWithoutEmailKeepsUsernameAndLegacyIdentity() {
        sql().update("update user_accounts set email=null where id=?",f.student.getId());var before=previous.signatures("user_accounts","account_sessions","student_profiles","evaluation_attempts");
        var i=activeUsername(f.student);assertThat(i.status()).isEqualTo(Status.ACTIVE);assertThat(i.accountId()).isEqualTo(f.student.getId());
        assertThat(previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
    }
    @Test void unverifiedPreparedEmailCannotActivateAndNoFreeBooleanProofExists() {
        var i=reviewed(email(f.student,"unverified-"+f.student.getId()+"@fixture.test"));var before=state();
        assertThatThrownBy(()->service.activate(i.id(),1,null)).isInstanceOf(DataAccessException.class).hasMessageContaining("verified evidence");
        assertThat(state()).isEqualTo(before);assertThat(repository.identifier(i.id()).status()).isEqualTo(Status.PREPARED);
        long p=service.issueFixtureProof(i.id(),1,RECEIPT,Instant.now().plusSeconds(100),"FIXTURE-ISSUE");
        assertThatThrownBy(()->sql().update("update t11b7_proofs set status='VERIFIED',verified_at=statement_timestamp() where id=?",p)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.activate(i.id(),1,p)).isInstanceOf(DataAccessException.class);
    }
    @Test void usernameChangeReservesOldKeyAndHasOnePrimaryAtCommit() {
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"changed-"+old.accountId(),Origin.CHANGE));
        var legacy=previous.signatures("user_accounts","account_sessions","student_profiles","evaluation_attempts");
        var active=service.changeUsername(old.id(),old.version(),next.id(),next.version());
        assertThat(active.status()).isEqualTo(Status.ACTIVE);assertThat(repository.identifier(old.id()).status()).isEqualTo(Status.RESERVED);
        assertThat(count("select count(*) from t11b7_identifiers where account_id=? and kind='USERNAME' and status='ACTIVE'",old.accountId())).isEqualTo(1);
        assertThat(count("select account_id from t11b7_claims where comparison_value=?",old.value())).isEqualTo(old.accountId());
        assertThat(repository.history(old.id())).hasSize(3);assertThat(previous.signatures(legacy.keySet().toArray(String[]::new))).isEqualTo(legacy);
    }
    @Test void reservedUsernameCannotBeReassignedAsOtherAccountsEmail() {
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"reserve-"+old.accountId(),Origin.CHANGE));service.changeUsername(old.id(),2,next.id(),1);
        var other=email(f.peerStudent,old.value());proof(other);
        assertThatThrownBy(()->service.review(other.id(),1,"FIXTURE-REVIEW")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.activate(other.id(),1,null)).isInstanceOf(DataAccessException.class).hasMessageContaining("collision");
    }
    @Test void directSqlCannotLeaveAccountWithoutPrimaryOrTwoActiveUsernames() {
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"direct-"+old.accountId(),Origin.CHANGE));var before=state();
        assertThatThrownBy(()->sql().update("update t11b7_identifiers set status='RESERVED',version=version+1 where id=?",old.id())).isInstanceOf(DataAccessException.class).hasMessageContaining("one primary");
        assertThatThrownBy(()->sql().update("update t11b7_identifiers set status='ACTIVE',version=version+1 where id=?",next.id())).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void crossTypeLegacyCollisionKeepsCandidatesAndB5Conflicts() {
        sql().update("update user_accounts set email=? where id=?",f.student.getUsername(),f.peerStudent.getId());
        var b5=previous.previous.service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,f.student.getUsername(),"FIXTURE-B5");
        long conflicts=count("select count(*) from t11b5_identifier_conflicts where identifier_id=?",b5.id());assertThat(conflicts).isPositive();
        var i=service.prepare(f.student.getId(),Kind.USERNAME,f.student.getUsername(),Origin.LEGACY,b5.id(),UUID.randomUUID(),"FIXTURE-B5-LINK");
        assertThat(count("select jsonb_array_length(observed) from t11b7_conflicts where identifier_id=?",i.id())).isPositive();
        assertThatThrownBy(()->service.review(i.id(),1,"FIXTURE-REVIEW")).isInstanceOf(DataAccessException.class);
        assertThat(count("select count(*) from t11b5_identifier_conflicts where identifier_id=?",b5.id())).isEqualTo(conflicts);
    }
    @Test void preparedAmbiguityIsRetainedAndReviewNeverChoosesOwnerAutomatically() {
        String value="ambiguous-"+f.student.getId()+"@fixture.test";var a=email(f.student,value);var b=email(f.peerStudent,value);long pa=proof(a),pb=proof(b);
        assertThat(count("select count(*) from t11b7_claims where comparison_value=?",value)).isZero();
        assertThatThrownBy(()->service.activate(a.id(),1,pa)).isInstanceOf(DataAccessException.class).hasMessageContaining("review");
        reviewed(a);reviewed(b);service.activate(a.id(),1,pa);
        assertThatThrownBy(()->service.activate(b.id(),1,pb)).isInstanceOf(DataAccessException.class).hasMessageContaining("collision");
        assertThat(repository.identifier(b.id()).status()).isEqualTo(Status.PREPARED);
    }
    @Test void sameAccountCanUseEqualUsernameAndEmailExactValue() {
        var u=activeUsername(f.student);var e=activeEmail(f.student,u.value());assertThat(e.status()).isEqualTo(Status.ACTIVE);
        assertThat(count("select count(*) from t11b7_claims where comparison_value=?",u.value())).isEqualTo(1);
        service.revokeEmail(e.id(),2);assertThat(count("select account_id from t11b7_claims where comparison_value=?",u.value())).isEqualTo(u.accountId());
    }
    @Test void emailReuseNeedsRevocationFreshProofAndNewExplicitReview() {
        String value="reuse-"+f.student.getId()+"@fixture.test";var old=activeEmail(f.student,value);var next=email(f.peerStudent,value);long newProof=proof(next);
        assertThatThrownBy(()->service.review(next.id(),1,"FIXTURE-REVIEW")).isInstanceOf(DataAccessException.class);
        service.revokeEmail(old.id(),2);
        assertThatThrownBy(()->service.activate(next.id(),1,newProof)).isInstanceOf(DataAccessException.class).hasMessageContaining("review");
        reviewed(next);var active=service.activate(next.id(),1,newProof);assertThat(active.accountId()).isEqualTo(f.peerStudent.getId());
        assertThat(repository.identifier(old.id()).status()).isEqualTo(Status.REVOKED);assertThat(repository.history(old.id())).hasSize(3);
        assertThat(count("select count(*) from t11b7_proofs where id=? and status='CONSUMED'",old.proofId())).isEqualTo(1);
    }
    @Test void originalLegacyEmailCanBeExplicitlyRevokedWithoutPermanentReservation() {
        String value="legacy-reuse-"+f.student.getId()+"@fixture.test";sql().update("update user_accounts set email=? where id=?",value,f.student.getId());
        var old=reviewed(prepare(f.student.getId(),Kind.EMAIL,value,Origin.LEGACY));service.activate(old.id(),1,proof(old));
        var next=email(f.peerStudent,value);assertThatThrownBy(()->service.review(next.id(),1,"FIXTURE-REVIEW")).isInstanceOf(DataAccessException.class);
        service.revokeEmail(old.id(),2);reviewed(next);service.activate(next.id(),1,proof(next));
        assertThat(sql().queryForObject("select email from user_accounts where id=?",String.class,f.student.getId())).isEqualTo(value);
    }
    @Test void previousOwnersProofCannotVerifyNewOwnerOrNewOperation() {
        String value="evidence-"+f.student.getId()+"@fixture.test";var old=activeEmail(f.student,value);service.revokeEmail(old.id(),2);
        var next=reviewed(email(f.peerStudent,value));var before=state();
        assertThatThrownBy(()->service.activate(next.id(),1,old.proofId())).isInstanceOf(DataAccessException.class).hasMessageContaining("verified evidence");
        assertThat(state()).isEqualTo(before);service.activate(next.id(),1,proof(next));
        var same=reviewed(email(f.student,"new-operation-"+f.student.getId()));
        assertThatThrownBy(()->service.activate(same.id(),1,old.proofId())).isInstanceOf(DataAccessException.class);
    }
    @Test void revokedExpiredInvalidAndMissingEvidenceAreRejected() throws Exception {
        var i=reviewed(email(f.student,"invalid-"+f.student.getId()));
        long p=service.issueFixtureProof(i.id(),1,RECEIPT,Instant.now().plusSeconds(100),"FIXTURE-INVALID");
        assertThatThrownBy(()->service.attestFixtureProof(p,"b".repeat(64))).isInstanceOf(DataAccessException.class).hasMessageContaining("invalid receipt");
        service.attestFixtureProof(p,RECEIPT);service.revokeFixtureProof(p);
        assertThatThrownBy(()->service.activate(i.id(),1,p)).isInstanceOf(DataAccessException.class);
        long expired=service.issueFixtureProof(i.id(),1,RECEIPT,Instant.now().plusMillis(900),"FIXTURE-EXPIRED");Thread.sleep(1100);
        assertThatThrownBy(()->service.attestFixtureProof(expired,RECEIPT)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.activate(i.id(),1,999999L)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.issueFixtureProof(i.id(),1,"not-a-proof",Instant.now().plusSeconds(100),"FIXTURE-BAD")).isInstanceOf(DataAccessException.class);
    }
    @Test void verifiedEvidenceCanExpireBeforeActivation() throws Exception {
        var i=reviewed(email(f.student,"expiring-"+f.student.getId()));long p=service.issueFixtureProof(i.id(),1,RECEIPT,Instant.now().plusMillis(1500),"FIXTURE-EXPIRING");service.attestFixtureProof(p,RECEIPT);Thread.sleep(1700);
        assertThatThrownBy(()->service.activate(i.id(),1,p)).isInstanceOf(DataAccessException.class).hasMessageContaining("verified evidence");
    }
    @Test void reviewGoesStaleWhenAnotherCandidateOrOwnerChanges() {
        String value="stale-review-"+f.student.getId();var i=reviewed(email(f.student,value));long p=proof(i);email(f.peerStudent,value);
        assertThatThrownBy(()->service.activate(i.id(),1,p)).isInstanceOf(DataAccessException.class).hasMessageContaining("fresh conflict review");
        service.review(i.id(),1,"FIXTURE-REVIEW-NEW");service.activate(i.id(),1,p);
        assertThat(count("select count(*) from t11b7_reviews where identifier_id=?",i.id())).isEqualTo(2);
    }
    @Test void staleLegacyCandidateCannotActivateEvenWithEarlierReview() {
        var i=reviewed(username(f.student));sql().update("update user_accounts set username=? where id=?","drift-"+f.student.getId(),f.student.getId());
        assertThatThrownBy(()->service.activate(i.id(),1,null)).isInstanceOf(DataAccessException.class).hasMessageContaining("stale legacy");
        assertThat(repository.identifier(i.id()).status()).isEqualTo(Status.PREPARED);
    }
    @Test void exactComparisonDoesNotFoldCaseWhitespaceOrUnicode() {
        var a=activeEmail(f.student,"Exact-"+f.student.getId());var b=activeEmail(f.peerStudent,"exact-"+f.student.getId());assertThat(a.value()).isNotEqualTo(b.value());
        activeEmail(f.student," "+a.value());activeEmail(f.student,"é-"+f.student.getId());activeEmail(f.peerStudent,"e\u0301-"+f.student.getId());
    }
    @Test void outdatedVersionsAndCrossAccountRenameAreRejected() {
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"version-"+old.accountId(),Origin.CHANGE));var before=state();
        assertThatThrownBy(()->service.changeUsername(old.id(),1,next.id(),1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Stale");
        var other=username(f.peerStudent);assertThatThrownBy(()->service.changeUsername(old.id(),2,other.id(),1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.identifier(old.id()).status()).isEqualTo(Status.ACTIVE);
        assertThatThrownBy(()->sql().update("update t11b7_identifiers set status='RESERVED' where id=?",old.id())).isInstanceOf(DataAccessException.class).hasMessageContaining("stale version");
    }
    @Test void retriesDoNotDuplicateAuditHistoryReservationsOrEvidence() {
        UUID op=UUID.randomUUID();var i=service.prepare(f.student.getId(),Kind.EMAIL,"retry-"+f.student.getId(),Origin.LINK,null,op,"FIXTURE-RETRY");
        assertThat(service.prepare(f.student.getId(),Kind.EMAIL,i.value(),Origin.LINK,null,op,"FIXTURE-RETRY").id()).isEqualTo(i.id());reviewed(i);reviewed(i);
        Instant expires=Instant.now().plusSeconds(100).truncatedTo(java.time.temporal.ChronoUnit.MICROS);long p=service.issueFixtureProof(i.id(),1,RECEIPT,expires,"FIXTURE-RETRY-PROOF");
        assertThat(service.issueFixtureProof(i.id(),1,RECEIPT,expires,"FIXTURE-RETRY-PROOF")).isEqualTo(p);service.attestFixtureProof(p,RECEIPT);service.attestFixtureProof(p,RECEIPT);
        service.activate(i.id(),1,p);var before=state();service.activate(i.id(),1,p);assertThat(state()).isEqualTo(before);
        service.revokeEmail(i.id(),2);before=state();service.revokeEmail(i.id(),2);assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->service.prepare(f.student.getId(),Kind.EMAIL,"different",Origin.LINK,null,op,"FIXTURE-RETRY")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void changeUsernameRetryUsesExactPairAndKeepsHistory() {
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"retry-name-"+old.accountId(),Origin.CHANGE));service.changeUsername(old.id(),2,next.id(),1);var before=state();
        service.changeUsername(old.id(),2,next.id(),1);assertThat(state()).isEqualTo(before);
    }
    @Test void renameRetryCannotPretendAnotherPredecessorAndOwnerCanReclaimOwnReservation() {
        var first=activeUsername(f.student);var second=reviewed(prepare(first.accountId(),Kind.USERNAME,"pair-"+first.accountId(),Origin.CHANGE));
        service.changeUsername(first.id(),2,second.id(),1);var third=reviewed(prepare(first.accountId(),Kind.USERNAME,"third-"+first.accountId(),Origin.CHANGE));
        var current=service.changeUsername(second.id(),2,third.id(),1);
        assertThat(current.replacesId()).isEqualTo(second.id());assertThat(current.replacesVersion()).isEqualTo(3);
        assertThatThrownBy(()->service.changeUsername(first.id(),2,third.id(),1)).isInstanceOf(IllegalStateException.class);
        var reclaim=reviewed(prepare(first.accountId(),Kind.USERNAME,first.value(),Origin.CHANGE));
        assertThat(service.changeUsername(third.id(),2,reclaim.id(),1).value()).isEqualTo(first.value());
    }
    @Test void historicalB5ConflictsRemainAfterExplicitRevocationAndReviewedResolution() {
        String value="b5-resolution-"+f.student.getId()+"@fixture.test";
        sql().update("update user_accounts set email=? where id=?",value,f.student.getId());
        sql().update("update user_accounts set username=? where id=?",value,f.peerStudent.getId());
        var candidate=previous.previous.service.prepareIdentifier(f.peerStudent.getId(),AccountLoginIdentifier.Kind.USERNAME,value,"FIXTURE-CONFLICT");
        var old=prepare(f.student.getId(),Kind.EMAIL,value,Origin.LEGACY);
        long conflicts=count("select count(*) from t11b5_identifier_conflicts where identifier_id=?",candidate.id());assertThat(conflicts).isPositive();
        var next=service.prepare(f.peerStudent.getId(),Kind.USERNAME,value,Origin.LEGACY,candidate.id(),UUID.randomUUID(),"FIXTURE-RESOLUTION");
        assertThatThrownBy(()->reviewed(next)).isInstanceOf(DataAccessException.class);service.revokeEmail(old.id(),1);
        reviewed(next);service.activate(next.id(),1,null);
        assertThat(count("select count(*) from t11b5_identifier_conflicts where identifier_id=?",candidate.id())).isEqualTo(conflicts);
        assertThat(count("select jsonb_array_length(observed) from t11b7_conflicts where identifier_id=?",next.id())).isPositive();
        assertThat(count("select count(*) from t11b7_reviews where identifier_id=?",next.id())).isEqualTo(1);
    }
    @Test void auditFailureRollsBackRenameWithBothIdentifiersClaimsAndQuota() {
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"audit-"+old.accountId(),Origin.CHANGE));var before=state();
        sql().execute("alter table system_logs rename to unavailable_b7_audit");
        try{assertThatThrownBy(()->service.changeUsername(old.id(),2,next.id(),1)).isInstanceOf(AuditUnavailableException.class);}finally{sql().execute("alter table unavailable_b7_audit rename to system_logs");}
        assertThat(state()).isEqualTo(before);service.changeUsername(old.id(),2,next.id(),1);
    }
    @Test void lateAuditAndQuotaFailuresRevertProofConsumptionAndWholeOperation() {
        var i=reviewed(email(f.student,"quota-"+f.student.getId()));long p=proof(i);var before=state();
        assertThatThrownBy(()->previous.previous.base.tx().executeWithoutResult(s->{service.activate(i.id(),1,p);sql().execute("alter table system_logs rename to unavailable_b7_audit");f.app.getBean(AuditLogService.class).recordInfo(LogEventType.ADMIN_ACTION,"GlobalAccessIdentifier",i.id(),null,null,null,null);})).isInstanceOf(AuditUnavailableException.class);
        assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->previous.previous.base.tx().executeWithoutResult(s->{sql().update("update t11b7_usage set rows_used=100000 where id=1");service.activate(i.id(),1,p);})).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);service.activate(i.id(),1,p);
    }
    @Test void failedEmailRevocationKeepsOwnerAndVerifiedProof() {
        var i=activeEmail(f.student,"revoke-failure-"+f.student.getId());var before=state();sql().execute("alter table system_logs rename to unavailable_b7_audit");
        try{assertThatThrownBy(()->service.revokeEmail(i.id(),2)).isInstanceOf(AuditUnavailableException.class);}finally{sql().execute("alter table unavailable_b7_audit rename to system_logs");}
        assertThat(state()).isEqualTo(before);
    }
    @Test void historyReviewsProofsAndClaimsCannotBeForgedOrRewritten() {
        var i=activeEmail(f.student,"history-"+f.student.getId());
        for(String q:List.of("update t11b7_conflicts set observed='[]'","delete from t11b7_reviews","truncate t11b7_events cascade",
            "update t11b7_attestations set receipt_digest='"+"b".repeat(64)+"'","insert into t11b7_events(target_type,target_id,operation,snapshot) values('t11b7_identifiers',1,'INSERT','{}')",
            "delete from t11b7_claims","update t11b7_identifiers set account_id="+f.peerStudent.getId()+" where id="+i.id()))
            assertThatThrownBy(()->sql().execute(q)).isInstanceOf(DataAccessException.class);
    }
    @Test void institutionRoleCannotControlAnotherAccountsGlobalIdentityOrIssueProofs() {
        var i=reviewed(email(f.student,"actor-"+f.student.getId()));long p=proof(i);previous.authenticate(f.peerStudent);
        assertThatThrownBy(()->service.activate(i.id(),1,p)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.review(i.id(),1,"FIXTURE-ROLE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.issueFixtureProof(i.id(),1,RECEIPT,Instant.now().plusSeconds(100),"FIXTURE-ROLE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->sql().update("update t11b7_identifiers set status='ACTIVE',version=version+1,changed_by=?,proof_id=? where id=?",f.peerStudent.getId(),p,i.id())).isInstanceOf(DataAccessException.class);
        previous.authenticate(f.student);service.activate(i.id(),1,p);
        var log=sql().queryForMap("select * from system_logs where target_type='GlobalAccessIdentifier' and target_id=? order by id desc limit 1",i.id());
        assertThat(log.get("actor_user_id")).isEqualTo(f.student.getId());assertThat(log.get("outcome")).isEqualTo("SUCCESS");assertThat(log.get("correlation_id")).isNotNull();
        assertThat(log.toString()).doesNotContain(i.value(),RECEIPT,"FIXTURE-ROLE",WhiteboardSecurityTransportDbTest.PASSWORD);
    }
    @Test void emailReuseAndUsernameChangeDoNotTransferSessionsOrAcademicHistory() throws Exception {
        String value="preserve-"+f.student.getId();String token=f.login(f.student);var before=previous.signatures("user_accounts","account_sessions","student_profiles","evaluation_attempts","enrollments","t11b5_academic_codes","evaluations","whiteboard_participants");
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"preserve-name-"+old.accountId(),Origin.CHANGE));service.changeUsername(old.id(),2,next.id(),1);
        var e=activeEmail(f.student,value);service.revokeEmail(e.id(),2);activeEmail(f.peerStudent,value);
        assertThat(previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        assertThat(f.login(f.student)).isNotBlank();assertThat(token).isNotBlank();
    }
    @Test void repeatCertifiesAndDivergenceDoesNotRepairLedgerOrDisabledGuard() {
        activeUsername(f.student);var before=state();var versions=previous.signatures("t11b6_schema_version","t11b7_schema_version");sql().execute(expansion);assertThat(state()).isEqualTo(before);
        previous.previous.base.tx().executeWithoutResult(s->{sql().execute("alter table t11b7_identifiers disable trigger t11b7_guard");assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        assertThat(previous.signatures(versions.keySet().toArray(String[]::new))).isEqualTo(versions);sql().execute(expansion);
        previous.previous.base.tx().executeWithoutResult(s->{sql().execute("alter table teaching_assignments disable trigger t11b6_slot_sync");assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
    }
    @Test void twoIndependentProcessesCompeteForEmailActivationWithOneOwner() throws Exception {
        String value="race-"+f.student.getId();var a=email(f.student,value);var b=email(f.peerStudent,value);long pa=proof(a),pb=proof(b);reviewed(a);reviewed(b);
        Path dir=Files.createDirectories(Path.of("target","t11b7-processes",f.schema));Path go=dir.resolve("go");var children=new ArrayList<Process>();
        try(Connection barrier=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            barrier.setAutoCommit(false);
            try {
                barrier.createStatement().execute("select t11b7_lock()");
                for(int i=1;i<=2;i++) {
                    var cmd=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),GlobalIdentifierActivationProcess.class.getName(),
                        f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,""+(i==1?a.id():b.id()),""+(i==1?pa:pb),
                        dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString());
                    children.add(new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready")))&&System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(Files.exists(dir.resolve("1.ready"))).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();Files.writeString(go,"go");
                List<Integer> pids=List.of();deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline){pids=sql().queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query='select t11b7_lock()'",Integer.class);if(pids.size()==2)break;Thread.sleep(20);}
                assertThat(pids).hasSize(2);barrier.commit();
                for(Process p:children){assertThat(p.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
                var results=List.of(Files.readString(dir.resolve("1.result")),Files.readString(dir.resolve("2.result")));
                assertThat(results).containsExactlyInAnyOrder("COMMITTED","REJECTED:23505");
                assertThat(count("select count(*) from t11b7_identifiers where value=? and status='ACTIVE'",value)).isEqualTo(1);
                assertThat(count("select count(*) from t11b7_proofs where id in (?,?) and status='CONSUMED'",pa,pb)).isEqualTo(1);
                Files.writeString(dir.resolve("evidence.json"),f.json.writeValueAsString(Map.of("osPids",children.stream().map(Process::pid).toList(),"blockedPostgresPids",pids,"results",results,"scope","SQL integrity; business audit verified by maintenance service tests")));
            } finally {barrier.rollback();for(Process p:children)if(p.isAlive())p.destroyForcibly();}
        }
    }
    @Test void nativeDumpRestoreCertifiesB7AndPreservesAllTablesAndEvidence() throws Exception {
        var old=activeUsername(f.student);var next=reviewed(prepare(old.accountId(),Kind.USERNAME,"restore-"+old.accountId(),Origin.CHANGE));service.changeUsername(old.id(),2,next.id(),1);
        var e=activeEmail(f.student,"restore-email-"+old.accountId());service.revokeEmail(e.id(),2);activeEmail(f.peerStudent,e.value());
        var tables=sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' order by relname",String.class);var before=previous.signatures(tables.toArray(String[]::new));
        Path dir=Files.createDirectories(Path.of("target","t11b7-native",f.schema));String db="chemicallab_t11b7_restore_"+UUID.randomUUID().toString().replace("-","");
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));boolean created=false;
        try {
            r.nativeTool("pg_dump",args,List.of("-Fc","-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            r.nativeTool("createdb",args,List.of(db),dir);created=true;r.nativeTool("pg_restore",args,List.of("--exit-on-error","--single-transaction","-d",db,dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            try(Connection connection=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement q=connection.createStatement()) {
                q.execute("set search_path to "+f.schema+",public");q.execute(expansion);Map<String,String> recovered=new TreeMap<>();
                for(String table:tables)try(var rows=q.executeQuery(r.signature(table))){rows.next();recovered.put(table,rows.getString(1));}assertThat(recovered).isEqualTo(before);
                for(String mutation:List.of("update system_logs set target_type='REST'","delete from system_logs","truncate system_logs cascade","truncate t11b7_events cascade"))assertThatThrownBy(()->q.execute(mutation)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
                assertThatThrownBy(()->q.execute("delete from t11b7_reviews")).isInstanceOf(SQLException.class).hasMessageContaining("fixtures only");
                assertThatThrownBy(()->q.execute("select t11b7_lock()")).isInstanceOf(SQLException.class).hasMessageContaining("fixtures only");
                q.execute("alter table t11b7_proofs disable trigger t11b7_guard");assertThatThrownBy(()->q.execute(expansion)).isInstanceOf(SQLException.class).hasMessageContaining("divergence");q.execute("alter table t11b7_proofs enable trigger t11b7_guard");q.execute(expansion);
                Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source",before,"restored",recovered,"tables",tables.size(),"certifier","B7 v1 frozen B6 chain; previous ledgers unchanged")));
            }
        } finally {if(created)r.nativeTool("dropdb",args,List.of(db),dir);}
    }
}

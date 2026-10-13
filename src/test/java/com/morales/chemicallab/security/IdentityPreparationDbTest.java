package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.IdentityPreparationRepository;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.AuditUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityPreparationDbTest {
    final BoardActivityReconciliationDbTest base=new BoardActivityReconciliationDbTest();
    final WhiteboardSecurityTransportDbTest f=base.f;
    IdentityPreparationService service;
    IdentityPreparationRepository repository;
    InstitutionMaintenanceService institutions;
    String expansion;
    long a,b,m,other,foreign;
    final Instant t=Instant.parse("2026-01-01T00:00:00Z");
    JdbcTemplate sql(){return f.sql();}
    long count(String q,Object...args){return sql().queryForObject(q,Long.class,args);}
    @BeforeAll void start() throws Exception {
        base.start();service=f.app.getBean(IdentityPreparationService.class);repository=f.app.getBean(IdentityPreparationRepository.class);
        institutions=f.app.getBean(InstitutionMaintenanceService.class);expansion=base.base.script("t11b5-v1");
        var previous=base.versions();
        base.tx().executeWithoutResult(s->{sql().execute(expansion);s.setRollbackOnly();});
        assertThat(sql().queryForObject("select to_regclass(current_schema()||'.t11b5_schema_version') is null",Boolean.class)).isTrue();
        sql().execute(expansion);sql().execute(expansion);assertThat(base.versions()).isEqualTo(previous);
    }
    @BeforeEach void fixture() {
        base.fixture();a=base.base.institution;b=base.base.foreign;m=base.base.member;
        other=institutions.createMembership(a,f.peerStudent.getId(),t).id();
        foreign=sql().queryForObject("select id from institution_memberships where institution_id=? and account_id=?",Long.class,b,f.student.getId());
    }
    @AfterEach void close(){f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    AcademicCodeReservation reserve(long inst,long member,long account,String code) {return service.reserveCode(inst,member,account,code,"FIXTURE-CODE");}
    Map<String,String> signatures(String...tables) {
        Map<String,String> r=new TreeMap<>();for(String table:tables)r.put(table,sql().queryForObject(base.base.signature(table),String.class));return r;
    }
    Map<String,String> identityState() {
        return signatures("user_accounts","account_sessions","institution_memberships","membership_role_grants","membership_events","enrollments","enrollment_events","t11b5_admin_proofs","t11b5_platform_role_grants",
            "t11b5_login_identifiers","t11b5_identifier_conflicts","t11b5_login_bindings","t11b5_academic_codes","t11b5_identity_events","t11b5_usage","system_logs","storage_usage");
    }
    String checksum(long id){return sql().queryForObject("select md5(to_jsonb(u)::text) from user_accounts u where id=?",String.class,id);}
    @Test void sameAcademicCodeAllowedAcrossSchoolsForDifferentAccounts() {
        long foreignOther=institutions.createMembership(b,f.peerStudent.getId(),t).id();
        var first=reserve(a,m,f.student.getId(),"EST0001");var second=reserve(b,foreignOther,f.peerStudent.getId(),"EST0001");
        assertThat(first.accountId()).isNotEqualTo(second.accountId());assertThat(first.institutionId()).isNotEqualTo(second.institutionId());
    }
    @Test void commonAccountHasIndependentSchoolCodesWithoutCredentialChanges() throws Exception {
        f.login(f.student);var before=signatures("user_accounts","account_sessions","student_profiles","evaluation_attempts","whiteboard_participants","storage_usage");
        reserve(a,m,f.student.getId(),"EST0001");reserve(b,foreign,f.student.getId(),"B-XYZ");
        assertThat(signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
    }
    @Test void reassignmentRejectedByServiceAndDirectSqlEvenAfterEnded() {
        reserve(a,m,f.student.getId(),"EST0001");institutions.transitionMembership(m,InstitutionMembership.Status.ENDED,t.plusSeconds(100));
        assertThatThrownBy(()->reserve(a,other,f.peerStudent.getId(),"EST0001")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("insert into t11b5_academic_codes(institution_id,membership_id,account_id,code,evidence) values(?,?,?,'EST0001','FIXTURE-DIRECT')",a,other,f.peerStudent.getId())).isInstanceOf(DataAccessException.class);
        assertThat(count("select count(*) from t11b5_academic_codes where institution_id=? and code='EST0001'",a)).isEqualTo(1);
    }
    @Test void readmissionRetainsIdentityAndReservationAndDoesNotRestoreRoles() {
        var code=reserve(a,m,f.student.getId(),"EST0001");
        institutions.transitionMembership(m,InstitutionMembership.Status.ENDED,t.plusSeconds(100));
        institutions.transitionMembership(m,InstitutionMembership.Status.ACTIVE,t.plusSeconds(200));
        long logs=count("select count(*) from system_logs");assertThat(reserve(a,m,f.student.getId(),"EST0001")).isEqualTo(code);
        assertThat(count("select count(*) from system_logs")).isEqualTo(logs);
        assertThat(count("select count(*) from membership_role_grants where membership_id=? and status='ACTIVE'",m)).isZero();
    }
    @Test void codeReferencesCannotCrossInstitutionsOrAccounts() {
        assertThatThrownBy(()->reserve(b,m,f.student.getId(),"EST0001")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->reserve(a,m,f.peerStudent.getId(),"EST0001")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->reserve(a,Long.MAX_VALUE,f.student.getId(),"EST0001")).isInstanceOf(DataAccessException.class);
    }
    @Test void noCodeRenameDeletionOrTruncateEvenForSameAccount() {
        reserve(a,m,f.student.getId(),"EST0001");
        assertThatThrownBy(()->reserve(a,m,f.student.getId(),"EST0002")).isInstanceOf(IllegalArgumentException.class);
        for(String q:List.of("update t11b5_academic_codes set code='NEW'","delete from t11b5_academic_codes","truncate t11b5_academic_codes cascade"))
            assertThatThrownBy(()->sql().execute(q)).isInstanceOf(DataAccessException.class);
    }
    @Test void platformPreparationRequiresExplicitCurrentLegacyProvenance() {
        var admin=f.account(Role.ADMINISTRADOR,null,"3","A");String expected=checksum(admin.getId());
        assertThatThrownBy(()->service.prepareLegacyAdministrator(admin.getId(),"0".repeat(32),"FIXTURE-WRONG",Instant.now(),null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.prepareLegacyAdministrator(admin.getId(),expected,null,Instant.now(),null)).isInstanceOf(DataAccessException.class);
        var before=signatures("user_accounts","account_sessions");Instant from=Instant.now().plusSeconds(1);
        var grant=service.prepareLegacyAdministrator(admin.getId(),expected,"FIXTURE-ADMIN-"+admin.getId(),from,null);
        assertThat(grant.role()).isEqualTo(PlatformRoleGrant.PlatformRole.ADMINISTRADOR);
        long logs=count("select count(*) from system_logs");assertThat(service.prepareLegacyAdministrator(admin.getId(),expected,"FIXTURE-ADMIN-"+admin.getId(),from,null)).isEqualTo(grant);
        assertThat(count("select count(*) from system_logs")).isEqualTo(logs);assertThat(signatures("user_accounts","account_sessions")).isEqualTo(before);
    }
    @Test void institutionalAdminAndActivityCannotBecomePlatformPrivilege() throws Exception {
        institutions.grantRole(m,MembershipRoleGrant.InstitutionalRole.ADMIN_INSTITUCION,t.plusSeconds(30),null);
        assertThatThrownBy(()->service.prepareLegacyAdministrator(f.student.getId(),checksum(f.student.getId()),"FIXTURE-SCHOOL",Instant.now(),null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.prepareLegacyAdministrator(f.teacher.getId(),checksum(f.teacher.getId()),"FIXTURE-AUTHOR",Instant.now(),null)).isInstanceOf(DataAccessException.class);
        assertThat(count("select count(*) from t11b5_platform_role_grants where account_id=?",f.student.getId())).isZero();
        var token=f.login(f.student);assertThat(f.request("GET","/api/auth/me",token,null).body()).contains("ESTUDIANTE");
        assertThat(f.request("GET","/api/admin/users",token,null).statusCode()).isEqualTo(403);
    }
    @Test void globalGrantSqlRejectsSchoolRoleAndMissingProof() {
        assertThatThrownBy(()->sql().update("insert into t11b5_platform_role_grants(account_id,proof_id,role,valid_from) values(?,0,'ADMIN_INSTITUCION',now())",f.student.getId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("insert into t11b5_platform_role_grants(account_id,proof_id,valid_from) values(?,0,now())",f.teacher.getId())).isInstanceOf(DataAccessException.class);
    }
    @Test void currentLegacyRoleDoesNotProveHistoricPrivilegeOrInvalidInterval() {
        var admin=f.account(Role.ADMINISTRADOR,null,"3","A");var before=identityState();String c=checksum(admin.getId());
        assertThatThrownBy(()->service.prepareLegacyAdministrator(admin.getId(),c,"FIXTURE-PAST-"+admin.getId(),t,null)).isInstanceOf(DataAccessException.class);
        Instant from=Instant.now().plusSeconds(60);
        assertThatThrownBy(()->service.prepareLegacyAdministrator(admin.getId(),c,"FIXTURE-EMPTY-"+admin.getId(),from,from)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.prepareLegacyAdministrator(admin.getId(),c,"FIXTURE-REVERSED-"+admin.getId(),from,from.minusSeconds(1))).isInstanceOf(DataAccessException.class);
        assertThat(identityState()).isEqualTo(before);
    }
    @Test void staleProvenanceAndProofMutationRejectedAlsoByDirectSql() {
        var admin=f.account(Role.ADMINISTRADOR,null,"3","A");String c=checksum(admin.getId());
        long proof=sql().queryForObject("insert into t11b5_admin_proofs(account_id,evidence,source_checksum) values(?,?,?) returning id",Long.class,admin.getId(),"FIXTURE-STALE-"+admin.getId(),c);
        sql().update("update user_accounts set username=? where id=?","drift-"+admin.getId(),admin.getId());
        assertThatThrownBy(()->sql().update("insert into t11b5_platform_role_grants(account_id,proof_id,valid_from) values(?,?,now()+interval '1 second')",admin.getId(),proof)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update t11b5_admin_proofs set source_checksum=? where id=?",checksum(admin.getId()),proof)).isInstanceOf(DataAccessException.class);
        assertThat(count("select count(*) from t11b5_platform_role_grants where proof_id=?",proof)).isZero();
    }
    @Test void preparedCollisionsDoNotReplaceCurrentLoginPriorityOrT04Resolution() throws Exception {
        String value="login-"+f.student.getId()+"@fixture.test";
        sql().update("update user_accounts set username=? where id=?",value,f.student.getId());
        sql().update("update user_accounts set email=? where id=?",value,f.peerStudent.getId());
        service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,value,"FIXTURE-LOGIN");
        var response=f.request("POST","/api/auth/login",null,f.json.writeValueAsString(Map.of("usernameOrEmail",value,"password",WhiteboardSecurityTransportDbTest.PASSWORD)));
        assertThat(response.statusCode()).isEqualTo(200);assertThat(f.json.readTree(response.body()).path("userId").asLong()).isEqualTo(f.student.getId());
        assertThat(count("select count(*) from t11b5_login_bindings where comparison_value=?",value)).isZero();
    }
    @Test void grantsPreserveIntervalsRevocationHistoryAndRejectOverlap() {
        var admin=f.account(Role.ADMINISTRADOR,null,"3","A");Instant from=Instant.now().plusSeconds(1);String c=checksum(admin.getId());
        var grant=service.prepareLegacyAdministrator(admin.getId(),c,"FIXTURE-ONE-"+admin.getId(),from,null);
        assertThatThrownBy(()->service.prepareLegacyAdministrator(admin.getId(),c,"FIXTURE-TWO-"+admin.getId(),from.plusSeconds(2),null)).isInstanceOf(DataAccessException.class);
        Instant at=Instant.now().plusSeconds(1);var revoked=service.revokePlatformRole(grant.id(),at);
        assertThat(revoked.validFrom()).isEqualTo(from.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(revoked.status()).isEqualTo(PlatformRoleGrant.Status.REVOKED);assertThat(service.revokePlatformRole(grant.id(),at)).isEqualTo(revoked);
        service.prepareLegacyAdministrator(admin.getId(),c,"FIXTURE-ADJACENT-"+admin.getId(),at,null);
        assertThat(count("select count(*) from t11b5_identity_events where target_type='t11b5_platform_role_grants' and target_id=?",grant.id())).isEqualTo(2);
        assertThatThrownBy(()->sql().execute("update t11b5_platform_role_grants set status='ACTIVE',revoked_at=null,revocation_reason=null where status='REVOKED'")).isInstanceOf(DataAccessException.class);
    }
    @Test void identifierInventoryPreservesCrossTypeCollisionAndBothClaims() {
        String value="collision-"+f.student.getId()+"@fixture.test";
        sql().update("update user_accounts set username=? where id=?",value,f.student.getId());
        sql().update("update user_accounts set email=? where id=?",value,f.peerStudent.getId());
        var before=signatures("user_accounts","account_sessions");
        var first=service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,value,"FIXTURE-FIRST");
        var second=service.prepareIdentifier(f.peerStudent.getId(),AccountLoginIdentifier.Kind.EMAIL,value,"FIXTURE-SECOND");
        assertThat(first.status()).isEqualTo("CONFLICT");assertThat(second.status()).isEqualTo("CONFLICT");assertThat(second.verified()).isFalse();
        assertThatThrownBy(()->service.reconcileIdentifier(first.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->sql().update("insert into t11b5_login_bindings(comparison_value,account_id,identifier_id) values(?,?,?)",value,first.accountId(),first.id())).isInstanceOf(DataAccessException.class);
        assertThat(count("select count(*) from t11b5_login_identifiers where value=?",value)).isEqualTo(2);
        assertThat(signatures("user_accounts","account_sessions")).isEqualTo(before);
        assertThat(repository.inventory()).anySatisfy(r->{assertThat(r.get("value")).isEqualTo(value);assertThat(r.get("cross_type")).isEqualTo(true);});
        // Conflict evidence survives fixture source drift; it is never silently resolved.
        sql().update("update user_accounts set email=null where id=?",f.peerStudent.getId());
        assertThat(repository.identifier(first.id()).status()).isEqualTo("CONFLICT");
        assertThat(count("select count(*) from t11b5_identifier_conflicts where identifier_id=?",first.id())).isEqualTo(1);
    }
    @Test void asciiCaseWhitespacePreviewBlocksWithoutChangingNormalization() {
        String value="Review-"+f.student.getId();sql().update("update user_accounts set username=? where id=?",value,f.student.getId());
        sql().update("update user_accounts set username=? where id=?"," "+value.toUpperCase(Locale.ROOT)+" ",f.peerStudent.getId());
        var claim=service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,value,"FIXTURE-CASE");
        assertThat(claim.value()).isEqualTo(value);assertThat(claim.comparisonValue()).isEqualTo(value);assertThat(claim.status()).isEqualTo("CONFLICT");
        assertThat(repository.inventory()).anySatisfy(r->{assertThat(r.get("value")).isEqualTo(value);assertThat(r.get("exact_collision")).isEqualTo(false);assertThat(r.get("review_collision")).isEqualTo(true);});
    }
    @Test void exactAliasesForSameAccountArePreparedUnverifiedAndIdempotent() {
        String value="same-"+f.student.getId()+"@fixture.test";sql().update("update user_accounts set username=?,email=? where id=?",value,value,f.student.getId());
        var one=service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,value,"FIXTURE-ALIAS");
        var two=service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.EMAIL,value,"FIXTURE-EMAIL");
        assertThat(one.status()).isEqualTo("PREPARED");assertThat(two.verified()).isFalse();
        long binding=service.reconcileIdentifier(one.id());assertThat(service.reconcileIdentifier(two.id())).isEqualTo(binding);
        assertThat(service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,value,"FIXTURE-ALIAS")).isEqualTo(one);
        assertThatThrownBy(()->service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,value,"FIXTURE-DIFFERENT")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void identifierWrongSourceVerificationMutationAndReuseCannotBeIntroduced() {
        assertThatThrownBy(()->service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.EMAIL,"not-current@fixture.test","FIXTURE-MAP")).isInstanceOf(DataAccessException.class);
        var one=service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,f.student.getUsername(),"FIXTURE-KEEP");
        assertThatThrownBy(()->sql().execute("update t11b5_login_identifiers set verified=true")).isInstanceOf(DataAccessException.class);
        sql().update("update user_accounts set username=? where id=?","new-"+f.student.getId(),f.student.getId());
        assertThat(repository.identifier(one.id()).status()).isEqualTo("STALE");assertThat(repository.identifier(one.id()).value()).isEqualTo(one.value());
        assertThatThrownBy(()->service.reconcileIdentifier(one.id())).isInstanceOf(IllegalStateException.class);
    }
    @Test void membershipTimelineDistinguishesEpisodesAndReactivationWithoutInventedIntervals() {
        var old=sql().queryForList("select * from membership_events where membership_id=? order by access_version",m);
        institutions.transitionMembership(m,InstitutionMembership.Status.SUSPENDED,t.plusSeconds(40));
        institutions.transitionMembership(m,InstitutionMembership.Status.ACTIVE,t.plusSeconds(50));
        institutions.transitionMembership(m,InstitutionMembership.Status.ENDED,t.plusSeconds(100));
        institutions.transitionMembership(m,InstitutionMembership.Status.ACTIVE,t.plusSeconds(200));
        var timeline=repository.membershipTimeline(m);
        assertThat(timeline).anySatisfy(r->{assertThat(r.get("transition")).isEqualTo("REACTIVATED");assertThat(((Number)r.get("episode_number")).longValue()).isEqualTo(1);});
        assertThat(timeline).anySatisfy(r->{assertThat(r.get("transition")).isEqualTo("READMITTED");assertThat(((Number)r.get("episode_number")).longValue()).isEqualTo(2);});
        assertThat(sql().queryForList("select * from membership_events where membership_id=? order by access_version",m)).startsWith(old.toArray(Map[]::new));
        assertThat(timeline).hasSize((int)count("select count(*) from membership_events where membership_id=?",m));
    }
    @Test void mandatoryAuditFailureRollsBackCodesHistoryQuotasAndRelatedChanges() {
        var before=identityState();sql().execute("alter table system_logs rename to unavailable_b5_audit");
        try {assertThatThrownBy(()->base.tx().executeWithoutResult(s->{reserve(a,m,f.student.getId(),"ROLLBACK");institutions.transitionMembership(m,InstitutionMembership.Status.SUSPENDED,t.plusSeconds(40));})).isInstanceOf(AuditUnavailableException.class);}
        finally {sql().execute("alter table unavailable_b5_audit rename to system_logs");}
        assertThat(identityState()).isEqualTo(before);reserve(a,m,f.student.getId(),"ROLLBACK");
        assertThat(count("select count(*) from t11b5_academic_codes where membership_id=?",m)).isEqualTo(1);
    }
    @Test void lateAuditFailureRevertsPrivilegeAliasesReservationAndMembershipTogether() {
        var admin=f.account(Role.ADMINISTRADOR,null,"3","A");var before=identityState();
        assertThatThrownBy(()->base.tx().executeWithoutResult(s->{
            service.prepareLegacyAdministrator(admin.getId(),checksum(admin.getId()),"FIXTURE-COMPOSITE-"+admin.getId(),Instant.now().plusSeconds(1),null);
            service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,f.student.getUsername(),"FIXTURE-COMPOSITE");
            reserve(a,m,f.student.getId(),"COMPOSITE");institutions.transitionMembership(m,InstitutionMembership.Status.SUSPENDED,t.plusSeconds(40));
            sql().execute("alter table system_logs rename to unavailable_b5_audit");
            f.app.getBean(AuditLogService.class).recordInfo(LogEventType.ADMIN_ACTION,"PlatformRoleGrant",1L,null,null,null,null);
        })).isInstanceOf(AuditUnavailableException.class);
        assertThat(identityState()).isEqualTo(before);
    }
    @Test void quotaFailureAndExplicitRollbackLeaveNoPartialPreparation() {
        var before=identityState();base.tx().executeWithoutResult(s->{reserve(a,m,f.student.getId(),"CANCELLED");s.setRollbackOnly();});assertThat(identityState()).isEqualTo(before);
        assertThatThrownBy(()->base.tx().executeWithoutResult(s->{sql().update("update t11b5_usage set rows_used=100000 where id=1");reserve(a,m,f.student.getId(),"QUOTA");})).isInstanceOf(DataAccessException.class);
        assertThat(identityState()).isEqualTo(before);
    }
    @Test void historyCannotBeForgedOrTruncatedAndPredecessorFingerprintsRemainIntact() {
        reserve(a,m,f.student.getId(),"HISTORY");var versions=base.versions();sql().execute(expansion);
        assertThat(base.versions()).isEqualTo(versions);
        for(String q:List.of("insert into t11b5_identity_events(target_type,target_id,operation,snapshot) values('FAKE',1,'INSERT','{}')",
            "update t11b5_identity_events set snapshot='{}'","delete from t11b5_identity_events","truncate t11b5_identity_events cascade"))
            assertThatThrownBy(()->sql().execute(q)).isInstanceOf(DataAccessException.class);
        base.tx().executeWithoutResult(s->{sql().execute("alter table t11b5_academic_codes disable trigger t11b5_guard");assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        sql().execute(expansion);
    }
    @Test void auditActorAndTypedTargetAreSafeAndNoBusinessValuesLeak() {
        var principal=new SessionPrincipal(f.teacher.getId(),f.teacher.getUsername(),UUID.randomUUID(),0,Role.DOCENTE,false);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DOCENTE"))));
        AcademicCodeReservation c;
        try {c=reserve(a,m,f.student.getId(),"PRIVATE-CODE");}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        var log=sql().queryForMap("select * from system_logs where target_type='AcademicCodeReservation' and target_id=?",c.id());
        assertThat(log.get("actor_user_id")).isEqualTo(f.teacher.getId());assertThat(log.get("outcome")).isEqualTo("SUCCESS");assertThat(log.get("correlation_id")).isNotNull();
        assertThat(log.get("metadata")).isEqualTo("institutionId="+a);assertThat(log.toString()).doesNotContain("PRIVATE-CODE","FIXTURE-CODE",WhiteboardSecurityTransportDbTest.PASSWORD);
    }
    @Test void actualReservationRaceUsesTwoProcessesAndOnePermanentOwner() throws Exception {
        Path dir=Files.createDirectories(Path.of("target","t11b5-processes",f.schema));Path go=dir.resolve("go");List<Process> children=new ArrayList<>();
        try(Connection barrier=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            barrier.setAutoCommit(false);
            try {
                for(int i=1;i<=2;i++) {
                    var cmd=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),IdentityReservationProcess.class.getName(),f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,""+a,""+(i==1?m:other),""+(i==1?f.student.getId():f.peerStudent.getId()),dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString());
                    children.add(new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready"))) && System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(Files.exists(dir.resolve("1.ready"))).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();
                barrier.createStatement().execute("select id from institution_memberships where id in ("+m+","+other+") order by id for update");Files.writeString(go,"go");
                List<Integer> pids=List.of();deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline){pids=sql().queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%insert into t11b5_academic_codes%'",Integer.class);if(pids.size()==2)break;Thread.sleep(20);}
                assertThat(pids).hasSize(2);barrier.commit();
                for(Process p:children){assertThat(p.waitFor(25,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
                var results=List.of(Files.readString(dir.resolve("1.result")),Files.readString(dir.resolve("2.result")));
                assertThat(results).containsExactlyInAnyOrder("COMMITTED","REJECTED:23505");assertThat(count("select count(*) from t11b5_academic_codes where institution_id=? and code='RACE'",a)).isEqualTo(1);
                Files.writeString(dir.resolve("evidence.json"),base.json(Map.of("osPids",children.stream().map(Process::pid).toList(),"blockedPostgresPids",pids,"results",results)));
            } finally {barrier.rollback();for(Process p:children)if(p.isAlive())p.destroyForcibly();}
        }
    }
    @Test void nativeDumpRestorePreservesAllDataAndRejectsRealReconciliation() throws Exception {
        reserve(a,m,f.student.getId(),"RESTORE");var admin=f.account(Role.ADMINISTRADOR,null,"3","A");
        service.prepareLegacyAdministrator(admin.getId(),checksum(admin.getId()),"FIXTURE-RESTORE-"+admin.getId(),Instant.now().plusSeconds(1),null);
        service.prepareIdentifier(f.student.getId(),AccountLoginIdentifier.Kind.USERNAME,f.student.getUsername(),"FIXTURE-RESTORE");
        Map<String,String> before=new TreeMap<>();for(String table:sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' order by relname",String.class))before.put(table,sql().queryForObject(base.base.signature(table),String.class));
        Path dir=Files.createDirectories(Path.of("target","t11b5-native",f.schema));String db="chemicallab_t11b5_restore_"+UUID.randomUUID().toString().replace("-","");
        String marker="SELECT md5(string_agg(definition,E'\\n' ORDER BY definition)) INTO actual FROM (";
        String catalog="select definition from ("+expansion.substring(expansion.lastIndexOf(marker)+marker.length(),expansion.lastIndexOf(") definitions;"))+") definitions order by definition";
        List<String> originalCatalog=sql().queryForList(catalog,String.class);Files.writeString(dir.resolve("original-catalog.json"),base.json(originalCatalog));
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));List<String> args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));boolean created=false;
        try {
            base.base.nativeTool("pg_dump",args,List.of("-Fc","-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            base.base.nativeTool("createdb",args,List.of(db),dir);created=true;base.base.nativeTool("pg_restore",args,List.of("--exit-on-error","--single-transaction","-d",db,dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            try(Connection c=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement q=c.createStatement()) {
                q.execute("set search_path to "+f.schema+",public");
                var restoredCatalog=new ArrayList<String>();try(ResultSet rows=q.executeQuery(catalog)){while(rows.next())restoredCatalog.add(rows.getString(1));}
                Files.writeString(dir.resolve("restored-catalog.json"),base.json(restoredCatalog));assertThat(restoredCatalog).isEqualTo(originalCatalog);
                q.execute(expansion);
                Map<String,String> after=new TreeMap<>();for(String table:before.keySet())try(ResultSet rows=q.executeQuery(base.base.signature(table))){rows.next();after.put(table,rows.getString(1));}
                assertThat(after).isEqualTo(before);
                for(String query:List.of("update system_logs set target_type='REST'","delete from system_logs","truncate system_logs cascade"))assertThatThrownBy(()->q.execute(query)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
                assertThatThrownBy(()->q.execute("select t11b5_fixture()")).isInstanceOf(SQLException.class).hasMessageContaining("fixtures only");
                Files.writeString(dir.resolve("signatures.json"),base.json(Map.of("source",before,"restored",after,"tables",before.size())));
            }
        } finally {if(created)base.base.nativeTool("dropdb",args,List.of(db),dir);}
    }
}

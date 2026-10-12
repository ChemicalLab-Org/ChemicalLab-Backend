package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.InstitutionMaintenanceService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL backend processes, fictitious legacy accounts and application/T08 transactions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstitutionPersistenceDbTest {
    final WhiteboardSecurityTransportDbTest f=new WhiteboardSecurityTransportDbTest();
    final Instant t=Instant.parse("2026-01-01T00:00:00Z");
    InstitutionMaintenanceService service;
    long a,b,m;
    int sequence;
    String expansion;
    JdbcTemplate sql(){return f.sql();}
    TransactionTemplate tx(){return new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));}
    @BeforeAll void start() throws Exception {
        f.start();service=f.app.getBean(InstitutionMaintenanceService.class);
        expansion=new ClassPathResource("db/expansion/t11b1-v1.sql").getContentAsString(StandardCharsets.UTF_8);
        sql().execute(expansion);
    }
    @BeforeEach void fixture() {
        f.fixture();
        a=service.createInstitution("DEMO-A-"+(++sequence),"Fictitious A","America/Lima").id();
        b=service.createInstitution("DEMO-B-"+sequence,"Fictitious B","America/Lima").id();
        service.changeInstitutionStatus(a,Institution.Status.ACTIVE);
        m=service.createMembership(a,f.student.getId(),t).id();
        service.transitionMembership(m,InstitutionMembership.Status.ACTIVE,t.plusSeconds(10));
    }
    @AfterEach void close(){f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    long version(long id){return sql().queryForObject("select access_version from institution_memberships where id=?",Long.class,id);}
    void grantSql(long membership,long institution,String role,String from,String until) {
        sql().update("insert into membership_role_grants(membership_id,institution_id,role,valid_from,valid_until) values(?,?,?,?::timestamptz,?::timestamptz)",membership,institution,role,from,until);
    }
    @Test void simultaneousMembershipsAndCombinedRolesStayIndependent() {
        long other=service.createMembership(b,f.student.getId(),t).id();
        service.transitionMembership(other,InstitutionMembership.Status.ACTIVE,t.plusSeconds(10));
        service.grantRole(m,MembershipRoleGrant.InstitutionalRole.ADMIN_INSTITUCION,t.plusSeconds(20),null);
        service.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(20),null);
        service.grantRole(other,MembershipRoleGrant.InstitutionalRole.ESTUDIANTE,t.plusSeconds(20),null);
        long otherVersion=version(other);
        service.transitionMembership(m,InstitutionMembership.Status.SUSPENDED,t.plusSeconds(30));
        assertThat(version(other)).isEqualTo(otherVersion);
        assertThat(sql().queryForObject("select status from institution_memberships where id=?",String.class,other)).isEqualTo("ACTIVE");
        assertThat(sql().queryForObject("select count(*) from institution_memberships where account_id=?",Long.class,f.student.getId())).isEqualTo(2);
    }
    @Test void duplicateMembershipsAndForeignReferencesFailInSql() {
        assertThatThrownBy(()->service.createMembership(a,f.student.getId(),t)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.createMembership(a,Long.MAX_VALUE,t)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.createMembership(Long.MAX_VALUE,f.student.getId(),t)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->grantSql(m,b,"DOCENTE",t.plusSeconds(20).toString(),null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update institution_memberships set institution_id=? where id=?",b,m)).isInstanceOf(DataAccessException.class);
    }
    @Test void platformAdministratorAndInvalidRoleCannotBePersisted() {
        for(String role:List.of("ADMINISTRADOR","ADMIN","INVENTED"))
            assertThatThrownBy(()->grantSql(m,a,role,t.plusSeconds(20).toString(),null)).isInstanceOf(DataAccessException.class);
    }
    @Test void invalidAndInfiniteWindowsAreRejectedDirectly() {
        for(String until:List.of(t.toString(),t.plusSeconds(20).toString(),"infinity"))
            assertThatThrownBy(()->grantSql(m,a,"DOCENTE",t.plusSeconds(20).toString(),until)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->grantSql(m,a,"DOCENTE",t.toString(),null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update institution_memberships set valid_from='infinity' where id=?",m)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.transitionMembership(m,InstitutionMembership.Status.SUSPENDED,t)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.createInstitution("INVALID-"+sequence," ","America/Lima")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.createInstitution("BADZONE-"+sequence,"Fictitious","Invalid/Zone")).isInstanceOf(DataAccessException.class);
    }
    @Test void overlappingAndAdjacentGrantsIncludingRevokedHistory() {
        var g=service.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(20),t.plusSeconds(50));
        assertThatThrownBy(()->grantSql(m,a,"DOCENTE",t.plusSeconds(30).toString(),null)).isInstanceOf(DataAccessException.class);
        service.revokeRole(g.id(),t.plusSeconds(40));
        assertThatThrownBy(()->grantSql(m,a,"DOCENTE",t.plusSeconds(30).toString(),t.plusSeconds(40).toString())).isInstanceOf(DataAccessException.class);
        service.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(40),null);
        assertThatThrownBy(()->sql().update("update membership_role_grants set role='ESTUDIANTE' where id=?",g.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update membership_role_grants set revoked_at=null where id=?",g.id())).isInstanceOf(DataAccessException.class);
    }
    @Test void suspensionReactivationEndingAndReadmissionPreserveEventsAndVersions() {
        var grant=service.grantRole(m,MembershipRoleGrant.InstitutionalRole.ESTUDIANTE,t.plusSeconds(20),null);
        service.transitionMembership(m,InstitutionMembership.Status.SUSPENDED,t.plusSeconds(30));
        service.transitionMembership(m,InstitutionMembership.Status.ACTIVE,t.plusSeconds(40));
        var ended=service.transitionMembership(m,InstitutionMembership.Status.ENDED,t.plusSeconds(50));
        assertThat(ended.validUntil()).isEqualTo(t.plusSeconds(50));
        var readmitted=service.transitionMembership(m,InstitutionMembership.Status.ACTIVE,t.plusSeconds(60));
        assertThat(readmitted.id()).isEqualTo(m);assertThat(readmitted.validFrom()).isEqualTo(t.plusSeconds(60));
        assertThat(readmitted.validUntil()).isNull();assertThat(readmitted.accessVersion()).isEqualTo(7);
        assertThat(sql().queryForList("select access_version from membership_events where membership_id=? order by access_version",Long.class,m)).containsExactly(0L,1L,2L,3L,4L,5L,6L,7L);
        var history=sql().queryForList("select * from membership_events where membership_id=? order by access_version",m);
        assertThat(history.get(6).get("valid_from")).isEqualTo(Timestamp.from(t));
        assertThat(history.get(6).get("valid_until")).isEqualTo(Timestamp.from(t.plusSeconds(50)));
        assertThat(sql().queryForObject("select status from membership_role_grants where id=?",String.class,grant.id())).isEqualTo("REVOKED");
        assertThat(sql().queryForObject("select count(*) from membership_role_grants where membership_id=? and status='ACTIVE'",Long.class,m)).isZero();
        service.grantRole(m,MembershipRoleGrant.InstitutionalRole.ESTUDIANTE,t.plusSeconds(70),null);
    }
    @Test void historyAndIdentityCannotBeRewrittenOrTruncated() {
        for(String table:List.of("institutions","institution_memberships","membership_role_grants","membership_events"))
            assertThatThrownBy(()->sql().execute("truncate "+table+" cascade")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update membership_events set status='ACTIVE' where membership_id=?",m)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("delete from institution_memberships where id=?",m)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update institutions set platform_code='REWRITTEN' where id=?",a)).isInstanceOf(DataAccessException.class);
        service.changeInstitutionStatus(a,Institution.Status.SUSPENDED);
        service.changeInstitutionStatus(a,Institution.Status.ARCHIVED);
        assertThatThrownBy(()->service.changeInstitutionStatus(a,Institution.Status.ACTIVE)).isInstanceOf(DataAccessException.class);
    }
    @Test void pendingAndEndedMembershipsCannotReceiveGrants() {
        long pending=service.createMembership(b,f.student.getId(),t).id();
        assertThatThrownBy(()->service.grantRole(pending,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(20),null)).isInstanceOf(DataAccessException.class);
        service.transitionMembership(m,InstitutionMembership.Status.ENDED,t.plusSeconds(30));
        assertThatThrownBy(()->service.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(40),null)).isInstanceOf(DataAccessException.class);
    }
    @Test void businessRollbackRemovesVersionEventsGrantAndAudit() {
        long v=version(m);long logs=sql().queryForObject("select count(*) from system_logs",Long.class);
        tx().executeWithoutResult(s->{service.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(20),null);s.setRollbackOnly();});
        assertThat(version(m)).isEqualTo(v);
        assertThat(sql().queryForObject("select count(*) from membership_role_grants where membership_id=?",Long.class,m)).isZero();
        assertThat(sql().queryForObject("select count(*) from membership_events where membership_id=?",Long.class,m)).isEqualTo(v+1);
        assertThat(sql().queryForObject("select count(*) from system_logs",Long.class)).isEqualTo(logs);
    }
    @Test void auditFailureRollsBackAllInstitutionalWrites() {
        long v=version(m);
        sql().execute("alter table system_logs rename to fictitious_audit_unavailable");
        try {
            assertThatThrownBy(()->service.transitionMembership(m,InstitutionMembership.Status.SUSPENDED,t.plusSeconds(20)))
                .isInstanceOf(com.morales.chemicallab.security.audit.AuditUnavailableException.class);
            assertThat(version(m)).isEqualTo(v);
            assertThat(sql().queryForObject("select status from institution_memberships where id=?",String.class,m)).isEqualTo("ACTIVE");
        } finally {sql().execute("alter table fictitious_audit_unavailable rename to system_logs");}
    }
    @Test void verifiedActorAndInstitutionAreRecordedWithoutBusinessText() throws Exception {
        String token=f.login(f.teacher);
        var context=org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(f.app.getBean(AccountSessionService.class).authenticate(token));
        try {
            var grant=service.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(20),null);
            assertThat(grant.grantedBy()).isEqualTo(f.teacher.getId());
            var row=sql().queryForMap("select actor_user_id,target_type,metadata,outcome,correlation_id from system_logs where event_type='MEMBERSHIP_ROLE_GRANTED' and target_id=?",grant.id());
            assertThat(row.get("actor_user_id")).isEqualTo(f.teacher.getId());
            assertThat(row.get("metadata")).isEqualTo("institutionId="+a);
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");assertThat(row.get("correlation_id")).isNotNull();
            assertThat(sql().queryForObject("select changed_by from membership_events where membership_id=? order by access_version desc limit 1",Long.class,m)).isEqualTo(f.teacher.getId());
            assertThat(row.toString()).doesNotContain(token,"Fictitious A");
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void repeatExpansionPreservesLegacyAccountsSessionsAndNewAuthoritiesHaveNoEffect() throws Exception {
        String token=f.login(f.student);
        var accounts=sql().queryForList("select * from user_accounts order by id");
        var sessions=sql().queryForList("select * from account_sessions order by id");
        sql().execute(expansion);sql().execute(expansion);
        service.grantRole(m,MembershipRoleGrant.InstitutionalRole.ADMIN_INSTITUCION,t.plusSeconds(20),null);
        service.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,t.plusSeconds(20),null);
        assertThat(sql().queryForList("select * from user_accounts order by id")).isEqualTo(accounts);
        assertThat(sql().queryForList("select * from account_sessions order by id")).isEqualTo(sessions);
        assertThat(f.request("GET","/api/auth/me",token,null).body()).contains("ESTUDIANTE").doesNotContain("ADMIN_INSTITUCION");
        assertThat(f.request("GET","/api/admin/logs",token,null).statusCode()).isEqualTo(403);
        assertThat(f.request("GET","/api/whiteboards/teacher",token,null).statusCode()).isEqualTo(403);
        var peer=f.connect(f.student);peer.subscribe("fictitious-foreign","/topic/whiteboards/"+f.otherBoard);
        assertThat(peer.closed.await(6,TimeUnit.SECONDS)).isTrue();
        assertThat(sql().queryForList("select target_type,metadata,outcome from system_logs where event_type='MEMBERSHIP_ROLE_GRANTED' and target_id in (select id from membership_role_grants where membership_id=?)",m))
            .allSatisfy(r->{assertThat(r.get("target_type")).isEqualTo("MembershipRoleGrant");assertThat(r.get("outcome")).isEqualTo("SUCCESS");assertThat(r.get("metadata")).isEqualTo("institutionId="+a);});
    }
    @Test void divergenceIsDetectedWithoutRepair() {
        sql().execute("alter table institutions disable trigger t11b1_institution_guard");
        try {assertThatThrownBy(()->sql().execute(expansion)).hasStackTraceContaining("schema divergence");}
        finally {sql().execute("alter table institutions enable trigger t11b1_institution_guard");}
        sql().execute("alter table institutions add column fictitious_drift text");
        try {assertThatThrownBy(()->sql().execute(expansion)).hasStackTraceContaining("schema divergence");}
        finally {sql().execute("alter table institutions drop column fictitious_drift");}
        sql().execute(expansion);
    }
    @Test void initialExpansionOnPopulatedLegacySchemaIsAtomicAndRepeatable() throws Exception {
        String legacy="t11b1_old_"+UUID.randomUUID().toString().replace("-","");
        var accounts=sql().queryForList("select * from user_accounts order by id");
        f.login(f.student);
        var sessions=sql().queryForList("select * from account_sessions order by id");
        try {
            tx().executeWithoutResult(s->{
                sql().execute("create schema "+legacy);
                sql().execute("create table "+legacy+".user_accounts (like user_accounts including all)");
                sql().execute("insert into "+legacy+".user_accounts select * from user_accounts");
                sql().execute("create table "+legacy+".account_sessions as select * from account_sessions");
                sql().execute("set local search_path to "+legacy+","+f.schema);
                sql().execute(expansion);sql().execute(expansion);
                assertThat(sql().queryForList("select * from user_accounts order by id")).isEqualTo(accounts);
                assertThat(sql().queryForList("select * from account_sessions order by id")).isEqualTo(sessions);
                assertThat(sql().queryForObject("select count(*) from institution_memberships",Long.class)).isZero();
                assertThat(sql().queryForObject("select count(*) from t11b1_schema_version",Long.class)).isEqualTo(1);
            });
        } finally {sql().execute("drop schema if exists "+legacy+" cascade");}
    }
    @Test void unversionedObjectsStopExpansionAndRollbackLeavesLegacyIntact() {
        String legacy="t11b1_bad_"+UUID.randomUUID().toString().replace("-","");
        tx().executeWithoutResult(s->{
            sql().execute("create schema "+legacy);
            sql().execute("create table "+legacy+".user_accounts(id bigint primary key)");
            sql().execute("insert into "+legacy+".user_accounts values(42)");
            sql().execute("create table "+legacy+".institutions(id bigint)");
        });
        try {
            tx().executeWithoutResult(s->{
                sql().execute("set local search_path to "+legacy+","+f.schema);
                assertThatThrownBy(()->sql().execute(expansion)).hasStackTraceContaining("unversioned objects");
                s.setRollbackOnly();
            });
            assertThat(sql().queryForObject("select id from "+legacy+".user_accounts",Long.class)).isEqualTo(42);
            assertThat(sql().queryForObject("select count(*) from pg_class where relnamespace=?::regnamespace and relname='t11b1_schema_version'",Long.class,legacy)).isZero();
        } finally {sql().execute("drop schema "+legacy+" cascade");}
    }
    @Test void independentPostgresProcessesCannotDuplicateMembership() throws Exception {
        race("insert into institution_memberships(institution_id,account_id,valid_from,state_since,change_reason) values("+b+","+f.student.getId()+",'2026-01-01','2026-01-01','CREATED')");
        assertThat(sql().queryForObject("select count(*) from institution_memberships where institution_id=? and account_id=?",Long.class,b,f.student.getId())).isEqualTo(1);
    }
    @Test void independentPostgresProcessesCannotCreateIncompatibleGrants() throws Exception {
        race("insert into membership_role_grants(membership_id,institution_id,role,valid_from) values("+m+","+a+",'DOCENTE','2026-01-01T00:00:20Z')");
        assertThat(sql().queryForObject("select count(*) from membership_role_grants where membership_id=?",Long.class,m)).isEqualTo(1);
    }
    void race(String statement) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(2);
        try {
            var tasks=new ArrayList<Callable<String>>();
            for(int i=0;i<2;i++)tasks.add(()->{
                try(var c=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,
                    f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
                    c.setAutoCommit(false);try(var s=c.createStatement()){
                        s.execute("set local lock_timeout='5s'");s.execute("set local statement_timeout='8s'");
                        gate.countDown();if(!gate.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Concurrency barrier");
                        try {s.execute(statement);Thread.sleep(100);c.commit();return "committed";}
                        catch(SQLException ex){c.rollback();return ex.getSQLState();}
                    }
                }
            });
            var futures=pool.invokeAll(tasks,15,TimeUnit.SECONDS);
            var results=new ArrayList<String>();for(var future:futures)results.add(future.get());
            assertThat(results).contains("committed");assertThat(results.stream().filter("committed"::equals).count()).isEqualTo(1);
            assertThat(results).anyMatch(r->r.equals("23505")||r.equals("23P01"));
        } finally {pool.shutdownNow();}
    }
}

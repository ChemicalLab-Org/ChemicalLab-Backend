package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real application/HTTP/STOMP and a schema that the guarded T03 fixture owns and removes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditReliabilityDbTest {
    final WhiteboardSecurityTransportDbTest f=new WhiteboardSecurityTransportDbTest();
    @BeforeAll void start() throws Exception {f.start();}
    @BeforeEach void fixture(){f.fixture();f.sql().update("update audit_rejection_budget set used=0,window_start=date_trunc('minute',statement_timestamp()) where id=1");}
    @AfterEach void closePeers(){f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    TransactionTemplate tx(){return new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));}
    long count(String event,long target){return f.sql().queryForObject("select count(*) from system_logs where event_type=? and target_id=?",Long.class,event,target);}
    double signal(String name){return f.app.getBean(MeterRegistry.class).counter("security.audit","signal",name).count();}
    void assertActor(String event,long target,long actor){
        var rows=f.sql().queryForList("select actor_user_id,target_id,outcome,correlation_id from system_logs where event_type=? and target_id=?",event,target);
        assertThat(rows).isNotEmpty().allSatisfy(r->{assertThat(r.get("actor_user_id")).isEqualTo(actor);assertThat(r.get("outcome")).isEqualTo("SUCCESS");assertThat(r.get("correlation_id")).isNotNull();});
    }
    @Test void evidenceCommitsTogetherAndRollbackCannotLeaveDefinitiveSuccess(){
        var audit=f.app.getBean(AuditLogService.class);
        tx().executeWithoutResult(s->{f.sql().update("update user_accounts set email='committed@invalid.test' where id=?",f.student.getId());audit.recordInfo(LogEventType.USER_UPDATED,"UserAccount",f.student.getId(),"secret-label","secret-action","secret-description","token=secret;role=ESTUDIANTE");});
        assertThat(count("USER_UPDATED",f.student.getId())).isEqualTo(1);
        tx().executeWithoutResult(s->{f.sql().update("update user_accounts set email='rollback@invalid.test' where id=?",f.student.getId());audit.recordInfo(LogEventType.USER_UPDATED,"UserAccount",f.student.getId(),null,null,null,null);s.setRollbackOnly();});
        assertThat(count("USER_UPDATED",f.student.getId())).isEqualTo(1);
        assertThat(f.sql().queryForObject("select email from user_accounts where id=?",String.class,f.student.getId())).isEqualTo("committed@invalid.test");
        var row=f.sql().queryForMap("select * from system_logs where event_type='USER_UPDATED' and target_id=?",f.student.getId());
        assertThat(row.toString()).doesNotContain("secret","rollback");assertThat(row.get("outcome")).isEqualTo("SUCCESS");assertThat(signal("required_rolled_back")).isGreaterThan(0);
    }
    @Test void ownPasswordResetDeactivateAndRevocationHaveVerifiedActorsAndSharedCorrelation() throws Exception {
        String token=f.login(f.student);
        var change=f.request("PATCH","/api/auth/change-temporary-password",token,"{\"currentPassword\":\""+f.PASSWORD+"\",\"newPassword\":\"SyntheticNewPassword2026\",\"confirmPassword\":\"SyntheticNewPassword2026\"}");
        assertThat(change.statusCode()).isEqualTo(200);assertActor("PASSWORD_CHANGED",f.student.getId(),f.student.getId());assertActor("SESSIONS_REVOKED",f.student.getId(),f.student.getId());
        String replacement=f.json.readTree(change.body()).get("token").asText();
        assertThat(f.request("POST","/api/auth/logout",replacement,"{}").statusCode()).isEqualTo(204);assertActor("SESSION_REVOKED",f.student.getId(),f.student.getId());
        var admin=f.account(Role.ADMINISTRADOR,null,"3","A");String adminToken=f.login(admin);
        var reset=f.request("PATCH","/api/admin/users/"+f.peerStudent.getId()+"/password/reset",adminToken,"{}");
        assertThat(reset.statusCode()).isEqualTo(200);assertActor("PASSWORD_RESET",f.peerStudent.getId(),admin.getId());
        assertThat(f.request("PATCH","/api/admin/users/"+f.peerStudent.getId()+"/deactivate",adminToken,"{}").statusCode()).isEqualTo(200);assertActor("USER_DEACTIVATED",f.peerStudent.getId(),admin.getId());
        assertThat(f.request("POST","/api/auth/logout-all",adminToken,"{}").statusCode()).isEqualTo(204);assertActor("SESSIONS_REVOKED",admin.getId(),admin.getId());
        assertThat(f.request("GET","/api/auth/me",token,null).statusCode()).isEqualTo(401);
        var correlations=f.sql().queryForList("select correlation_id from system_logs where target_id=? and event_type in ('PASSWORD_CHANGED','SESSIONS_REVOKED')",f.student.getId());
        assertThat(correlations).hasSize(2);assertThat(correlations.get(0)).isEqualTo(correlations.get(1));
        assertThat(change.headers().firstValue("X-Correlation-ID")).contains(correlations.get(0).get("correlation_id").toString());
        assertThat(f.sql().queryForList("select description,metadata,declared_identifier from system_logs").toString()).doesNotContain(replacement,"SyntheticNewPassword2026",f.PASSWORD);
    }
    @Test void failedLoginIsDeclaredNotAuthenticatedAndKeepsUniformResponse() throws Exception {
        String body="{\"usernameOrEmail\":\"synthetic\\r\\ncredential-do-not-store\",\"password\":\"credential-do-not-store\"}";
        var response=f.request("POST","/api/auth/login",null,body);
        assertThat(response.statusCode()).isEqualTo(401);assertThat(f.json.readTree(response.body()).get("code").asText()).isEqualTo("LOGIN_INVALID");
        var row=f.sql().queryForMap("select * from system_logs where event_type='LOGIN_FAILED' and correlation_id=?::uuid",response.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertThat(row.get("actor_user_id")).isNull();assertThat(row.get("actor_username")).isNull();assertThat(row.get("actor_role")).isNull();assertThat(row.get("declared_identifier").toString()).startsWith("sha256:");assertThat(row.get("outcome")).isEqualTo("REJECTED");assertThat(row.toString()).doesNotContain("credential-do-not-store","\r","\n");
        assertThat(f.sql().queryForObject("select count(*) from system_logs where correlation_id=?::uuid",Long.class,response.headers().firstValue("X-Correlation-ID").orElseThrow())).isEqualTo(1);
        var invalid=f.request("POST","/api/auth/login","invalid-synthetic-bearer",body);
        assertThat(invalid.statusCode()).isEqualTo(401);
        String correlation=invalid.headers().firstValue("X-Correlation-ID").orElseThrow();
        for(int i=0;i<40 && f.sql().queryForObject("select count(*) from system_logs where correlation_id=?::uuid",Long.class,correlation)==0;i++)Thread.sleep(50);
        assertThat(f.sql().queryForObject("select event_type from system_logs where correlation_id=?::uuid",String.class,correlation)).isEqualTo("REST_DENIED");
    }
    @Test void destinationFailureRejectsBusinessCommitAndRecoveryNeedsNoQueue() throws Exception {
        String studentToken=f.login(f.student);double before=signal("required_persist_failed");
        long sessions=f.sql().queryForObject("select count(*) from account_sessions where user_id=?",Long.class,f.teacher.getId());
        f.sql().execute("alter table system_logs rename to t08_destination_down");
        try {
            var response=f.request("POST","/api/auth/login",null,"{\"usernameOrEmail\":\""+f.teacher.getUsername()+"\",\"password\":\""+f.PASSWORD+"\"}");
            assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).contains("AUDIT_UNAVAILABLE").doesNotContain("insert into","t08_destination_down");
            assertThat(f.sql().queryForObject("select count(*) from account_sessions where user_id=?",Long.class,f.teacher.getId())).isEqualTo(sessions);
            assertThat(f.request("POST","/api/auth/logout",studentToken,"{}").statusCode()).isEqualTo(503);
            assertThat(f.request("GET","/api/auth/me",studentToken,null).statusCode()).isEqualTo(200);
            var rejection=f.request("POST","/api/auth/login",null,"{\"usernameOrEmail\":\"absent-synthetic\",\"password\":\"synthetic-invalid\"}");
            assertThat(rejection.statusCode()).isEqualTo(401);assertThat(rejection.body()).contains("LOGIN_INVALID");
            assertThat(signal("required_persist_failed")).isGreaterThan(before);assertThat(signal("rejection_persist_failed")).isGreaterThan(0);
        } finally {f.sql().execute("alter table t08_destination_down rename to system_logs");}
        assertThat(f.request("POST","/api/auth/logout",studentToken,"{}").statusCode()).isEqualTo(204);assertActor("SESSION_REVOKED",f.student.getId(),f.student.getId());
    }
    @Test void restStompDenialsAreBoundedAndContainNoPayloadOrClientCorrelation() throws Exception {
        String token=f.login(f.student);var forbidden=f.request("GET","/api/admin/logs",token,null);assertThat(forbidden.statusCode()).isEqualTo(403);
        String correlation=forbidden.headers().firstValue("X-Correlation-ID").orElseThrow();
        for(int i=0;i<40 && f.sql().queryForObject("select count(*) from system_logs where correlation_id=?::uuid",Long.class,correlation)==0;i++)Thread.sleep(50);
        var row=f.sql().queryForMap("select * from system_logs where event_type='REST_DENIED' and correlation_id=?::uuid",correlation);assertThat(row.get("actor_user_id")).isEqualTo(f.student.getId());
        var peer=f.connect(f.outsider);peer.subscribe("synthetic","/topic/whiteboards/"+f.board);assertThat(peer.closed.await(6,TimeUnit.SECONDS)).isTrue();
        assertThat(f.sql().queryForObject("select count(*) from system_logs where event_type='STOMP_DENIED' and actor_user_id=?",Long.class,f.outsider.getId())).isGreaterThan(0);
        assertThat(f.sql().queryForList("select description,metadata from system_logs where event_type='STOMP_DENIED'").toString()).doesNotContain("Authorization","Bearer","synthetic");
    }
    SystemLog rejected(){return SystemLog.builder().eventType(LogEventType.REST_DENIED).category(LogCategory.AUTH).severity(LogSeverity.WARNING).action("REST_DENIED").outcome("REJECTED").correlationId(UUID.randomUUID()).createdAt(LocalDateTime.now(ZoneOffset.UTC)).build();}
    @Test void sharedRejectionLimitCoordinatesConcurrentWritersAndSurvivesRestart() throws Exception {
        var second=f.startApp();ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            // Load the budget after both slow application startups. Give concurrent writers
            // a full clock window; crossing a minute correctly resets the production budget.
            double secondOfMinute=f.sql().queryForObject("select extract(second from statement_timestamp())",Double.class);
            if(secondOfMinute>=45)Thread.sleep((long)((60-secondOfMinute)*1000)+100);
            f.sql().update("update audit_rejection_budget set used=110,window_start=date_trunc('minute',statement_timestamp()) where id=1");
            var a=f.app.getBean(AuditLogWriter.class);var b=second.getBean(AuditLogWriter.class);
            List<Callable<Boolean>> work=new ArrayList<>();for(int i=0;i<20;i++){var writer=i%2==0?a:b;work.add(()->writer.persistRejection(rejected()));}
            long accepted=0;for(var future:pool.invokeAll(work))if(future.get())accepted++;
            assertThat(accepted).isEqualTo(10);assertThat(f.sql().queryForObject("select used from audit_rejection_budget",Integer.class)).isEqualTo(120);
        } finally {pool.shutdownNow();second.close();}
        // Restart preserves the durable counter; a new clock minute may legitimately reopen it.
        var restarted=f.startApp();try {
            assertThat(restarted.getBean(org.springframework.jdbc.core.JdbcTemplate.class).queryForObject("select used from audit_rejection_budget",Integer.class)).isEqualTo(120);
            f.sql().update("update audit_rejection_budget set window_start=statement_timestamp()-interval '2 minutes'");
            assertThat(restarted.getBean(AuditLogWriter.class).persistRejection(rejected())).isTrue();
            assertThat(f.sql().queryForObject("select used from audit_rejection_budget",Integer.class)).isEqualTo(1);
        }finally{restarted.close();}
    }
    @Test void auditApisAreReadOnlyPagedAndDatabaseGuardPreservesHistory() throws Exception {
        String token=f.login(f.account(Role.ADMINISTRADOR,null,"3","A"));
        assertThat(f.request("GET","/api/admin/logs?size=100",token,null).statusCode()).isEqualTo(200);
        assertThat(f.request("GET","/api/admin/logs?size=101",token,null).statusCode()).isEqualTo(400);
        assertThat(f.request("GET","/api/admin/logs?page=-1",token,null).statusCode()).isEqualTo(400);
        var first=f.json.readTree(f.request("GET","/api/admin/logs?eventType=LOGIN_SUCCESS&size=1",token,null).body());
        long id=first.get("content").get(0).get("id").asLong();
        // Existing global advice maps unsupported methods to 500; it still exposes no mutation.
        assertThat(f.request("DELETE","/api/admin/logs/"+id,token,null).statusCode()).isEqualTo(500);
        assertThat(f.request("PATCH","/api/admin/logs/"+id,token,"{}").statusCode()).isEqualTo(500);
        long before=f.sql().queryForObject("select count(*) from system_logs",Long.class);
        assertThatThrownBy(()->f.sql().update("delete from system_logs where id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->f.sql().update("update system_logs set description='synthetic' where id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->f.sql().execute("truncate system_logs")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.sql().queryForObject("select count(*) from system_logs",Long.class)).isEqualTo(before);
    }
    @Test void disabledGuardStopsStartup(){
        f.sql().execute("alter table system_logs disable trigger audit_append_only");
        try {assertThatThrownBy(()->f.startApp()).hasStackTraceContaining("T08 audit schema/guard/budget");}
        finally{f.sql().execute("alter table system_logs enable trigger audit_append_only");}
    }
    @Test void originalSchemaMigrationPreservesHistoricalRowsWithoutInventingOutcomes() throws Exception {
        String schema="t08_old_"+UUID.randomUUID().toString().replace("-","");
        try {
            tx().executeWithoutResult(s->{
                f.sql().execute("create schema "+schema);
                f.sql().execute("set local search_path to "+schema);
                f.sql().execute("create table system_logs(id bigint primary key,event_type varchar(255),created_at timestamp,description text)");
                f.sql().execute("insert into system_logs values(1,'USER_UPDATED','2020-01-01','synthetic history')");
                try {f.sql().execute(new org.springframework.core.io.ClassPathResource("security/t08-audit.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}
                catch(java.io.IOException ex){throw new IllegalStateException(ex);}
                var row=f.sql().queryForMap("select * from system_logs where id=1");
                assertThat(row.get("description")).isEqualTo("synthetic history");assertThat(row.get("outcome")).isNull();assertThat(row.get("correlation_id")).isNull();
                assertThat(f.sql().queryForObject("select count(*) from system_logs",Long.class)).isEqualTo(1);
            });
        } finally {f.sql().execute("drop schema if exists "+schema+" cascade");}
    }
    @Test void combinedScopeAndStateChangeHasOneRevocationAndCorrectActors() throws Exception {
        var profile=f.app.getBean(com.morales.chemicallab.repository.StudentProfileRepository.class).findByUser_Id(f.student.getId()).orElseThrow();
        String token=f.login(f.teacher);
        String payload="{\"names\":\"Synthetic\",\"lastNames\":\"Student\",\"grade\":\"4\",\"section\":\"B\",\"active\":false}";
        var response=f.request("PUT","/api/users/teachers/"+f.teacher.getId()+"/students/"+profile.getId(),token,payload);
        assertThat(response.statusCode()).isEqualTo(200);
        assertActor("USER_UPDATED",f.student.getId(),f.teacher.getId());assertActor("USER_DEACTIVATED",f.student.getId(),f.teacher.getId());assertActor("PERMISSIONS_CHANGED",f.student.getId(),f.teacher.getId());
        assertThat(count("SESSIONS_REVOKED",f.student.getId())).isEqualTo(1);
        assertThat(f.sql().queryForObject("select count(distinct correlation_id) from system_logs where target_id=? and event_type in ('USER_UPDATED','USER_DEACTIVATED','PERMISSIONS_CHANGED','SESSIONS_REVOKED')",Long.class,f.student.getId())).isEqualTo(1);
    }
    @Test void clientCorrelationIsIgnoredAndLoginIndicatorsAreNotDuplicated() throws Exception {
        String forged=UUID.randomUUID().toString();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(f.origin+"/api/auth/login"))
            .header("Content-Type","application/json").header("X-Correlation-ID",forged)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"usernameOrEmail\":\""+f.student.getUsername()+"\",\"password\":\""+f.PASSWORD+"\"}")).build();
        var response=f.http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        String actual=response.headers().firstValue("X-Correlation-ID").orElseThrow();assertThat(actual).isNotEqualTo(forged);
        assertThat(f.sql().queryForObject("select count(*) from system_logs where event_type='LOGIN_SUCCESS' and actor_user_id=?",Long.class,f.student.getId())).isEqualTo(1);
        var log=f.sql().queryForMap("select correlation_id,outcome from system_logs where event_type='LOGIN_SUCCESS' and actor_user_id=?",f.student.getId());assertThat(log.get("correlation_id").toString()).isEqualTo(actual);
        var record=f.app.getBean(StudentUsageRecordService.class).getRecords("ESTUDIANTE",f.student.getUsername(),null,null,null,null,null,null).records();
        assertThat(record).hasSize(1);assertThat(record.get(0).sessionsStarted()).isEqualTo(1);
    }
    @Test void successWithoutBusinessTransactionIsExplicitlyRejected(){
        assertThatThrownBy(()->f.app.getBean(AuditLogService.class).recordInfo(LogEventType.USER_UPDATED,"UserAccount",f.student.getId(),null,null,null,null))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(count("USER_UPDATED",f.student.getId())).isZero();
    }
}

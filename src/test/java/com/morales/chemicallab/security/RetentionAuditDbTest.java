package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.Role;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP and PostgreSQL; only the guarded fixture's disposable schema is mutated. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionAuditDbTest {
    final WhiteboardSecurityTransportDbTest f=new WhiteboardSecurityTransportDbTest(){
        @Override ConfigurableApplicationContext startApp(String... extra){
            var args=new ArrayList<>(List.of("--app.retention.delete-enabled=true","--app.retention.batch-size=2"));
            args.addAll(List.of(extra));return super.startApp(args.toArray(String[]::new));
        }
    };
    String token;long admin;
    static final String PATH="/api/admin/technical-retention?cutoff=2025-01-01T00:00:00&simulation=";
    @BeforeAll void start() throws Exception {f.start();}
    @BeforeEach void fixture() throws Exception {
        f.fixture();
        f.sql().update("delete from usage_events where occurred_at<'2025-01-01'");
        f.sql().update("update technical_retention_state set last_cutoff=null,last_deleted=0 where id=1");
        var user=f.account(Role.ADMINISTRADOR,null,"3","A");admin=user.getId();token=f.login(user);
    }
    @AfterEach void peers(){f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    long evidence(){return f.sql().queryForObject("select count(*) from system_logs where event_type='TECHNICAL_RETENTION_EXECUTED'",Long.class);}
    long quota(){return f.sql().queryForObject("select coalesce((select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='USAGE_COUNT'),0)",Long.class,f.student.getId());}
    Map<String,Object> state(){return f.sql().queryForMap("select * from technical_retention_state where id=1");}
    List<Map<String,Object>> usage(){return f.sql().queryForList("select * from usage_events order by id");}
    void old(int n){
        for(int i=0;i<n;i++)f.sql().update("insert into usage_events(user_id,username,user_role,module,event_type,occurred_at,description) values(?,'synthetic-sensitive-name','ESTUDIANTE','COMPOUNDS','MODULE_ACCESS','2024-01-01','synthetic-sensitive-content')",f.student.getId());
    }
    @Test void committedBatchHasVerifiedAdministratorScopeCorrelationAndProtectedHistory() throws Exception {
        old(3);long before=quota();long logs=evidence();
        var history=f.sql().queryForList("select * from system_logs order by id");
        var academic=f.sql().queryForList("select * from evaluation_attempt_events order by id");
        var response=f.request("POST",PATH+"false",token,"{}");
        assertThat(response.statusCode()).isEqualTo(200);
        var body=f.json.readTree(response.body());assertThat(body.path("deleted").asInt()).isEqualTo(2);assertThat(body.path("candidates").asInt()).isEqualTo(2);
        assertThat(quota()).isEqualTo(before-2);assertThat(evidence()).isEqualTo(logs+1);
        assertThat(state().get("last_deleted")).isEqualTo(2L);assertThat(state().get("last_cutoff").toString()).startsWith("2025-01-01");
        var row=f.sql().queryForMap("select * from system_logs where event_type='TECHNICAL_RETENTION_EXECUTED' order by id desc limit 1");
        assertThat(row.get("actor_user_id")).isEqualTo(admin);assertThat(row.get("actor_role")).isEqualTo("ADMINISTRADOR");
        assertThat(row.get("target_type")).isEqualTo("TechnicalRetention");assertThat(row.get("target_id")).isEqualTo(1L);
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("correlation_id").toString()).isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
        assertThat(row.get("metadata")).isEqualTo("cutoff=2025-01-01T00:00:00;candidates=2;deleted=2;batchSize=2");
        assertThat(row.toString()).doesNotContain("synthetic-sensitive",f.PASSWORD,token);
        assertThat(f.sql().queryForList("select * from system_logs where id<>? order by id",row.get("id"))).containsAll(history);
        assertThat(f.sql().queryForList("select * from evaluation_attempt_events order by id")).isEqualTo(academic);
    }
    @Test void failedMandatoryEvidenceRollsBackDeletionsStateAndQuotaAndRecoveryCommits() throws Exception {
        old(2);var before=usage();var initialState=state();long initialQuota=quota();long logs=evidence();
        f.sql().execute("alter table system_logs rename to system_logs_unavailable");
        try {
            var response=f.request("POST",PATH+"false",token,"{}");
            assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).contains("AUDIT_UNAVAILABLE").doesNotContain("system_logs","synthetic-sensitive",token,f.PASSWORD);
            assertThat(usage()).isEqualTo(before);assertThat(state()).isEqualTo(initialState);assertThat(quota()).isEqualTo(initialQuota);
            assertThat(f.sql().queryForObject("select count(*) from system_logs_unavailable where event_type='TECHNICAL_RETENTION_EXECUTED'",Long.class)).isEqualTo(logs);
        } finally {f.sql().execute("alter table system_logs_unavailable rename to system_logs");}
        assertThat(f.request("POST",PATH+"false",token,"{}").statusCode()).isEqualTo(200);
        assertThat(quota()).isEqualTo(initialQuota-2);assertThat(evidence()).isEqualTo(logs+1);
    }
    @Test void simulationHasCandidatesButNoMutationOrDeletionEvent() throws Exception {
        old(3);var before=usage();var initialState=state();long initialQuota=quota();long logs=evidence();
        var response=f.request("POST",PATH+"true",token,"{}");assertThat(response.statusCode()).isEqualTo(200);
        var body=f.json.readTree(response.body());assertThat(body.path("simulation").asBoolean()).isTrue();assertThat(body.path("candidates").asInt()).isEqualTo(2);assertThat(body.path("deleted").asInt()).isZero();
        assertThat(usage()).isEqualTo(before);assertThat(state()).isEqualTo(initialState);assertThat(quota()).isEqualTo(initialQuota);assertThat(evidence()).isEqualTo(logs);
    }
    @Test void noCandidatesHasNoDeletionEventAndDoesNotAdvanceState() throws Exception {
        var before=usage();var initialState=state();long initialQuota=quota();long logs=evidence();
        var response=f.request("POST",PATH+"false",token,"{}");assertThat(response.statusCode()).isEqualTo(200);
        var body=f.json.readTree(response.body());assertThat(body.path("coordinated").asBoolean()).isTrue();assertThat(body.path("candidates").asInt()).isZero();assertThat(body.path("deleted").asInt()).isZero();
        assertThat(usage()).isEqualTo(before);assertThat(state()).isEqualTo(initialState);assertThat(quota()).isEqualTo(initialQuota);assertThat(evidence()).isEqualTo(logs);
    }
    @Test void uncoordinatedBatchHasNoDeletionEventOrMutation() throws Exception {
        old(2);var before=usage();var initialState=state();long initialQuota=quota();long logs=evidence();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            var lock=pool.submit(()->new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class)).execute(s->{
                f.sql().queryForObject("select id from technical_retention_state where id=1 for update",Integer.class);entered.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture lock timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}return true;
            }));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var response=f.request("POST",PATH+"false",token,"{}");assertThat(response.statusCode()).isEqualTo(200);
            var body=f.json.readTree(response.body());assertThat(body.path("coordinated").asBoolean()).isFalse();assertThat(body.path("deleted").asInt()).isZero();
            release.countDown();lock.get(10,TimeUnit.SECONDS);
            assertThat(usage()).isEqualTo(before);assertThat(state()).isEqualTo(initialState);assertThat(quota()).isEqualTo(initialQuota);assertThat(evidence()).isEqualTo(logs);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void disabledDeletionAndStudentAccessCannotDelete() throws Exception {
        old(2);var before=usage();var initialState=state();long initialQuota=quota();long logs=evidence();
        assertThat(f.request("POST",PATH+"false",f.login(f.student),"{}").statusCode()).isEqualTo(403);
        String previous=f.origin;
        try(var second=f.startApp("--app.retention.delete-enabled=false")){
            f.origin=f.origin(second);
            assertThat(f.request("POST",PATH+"false",token,"{}").statusCode()).isEqualTo(400);
            assertThat(f.request("POST",PATH+"true",token,"{}").statusCode()).isEqualTo(200);
        } finally {f.origin=previous;}
        assertThat(usage()).isEqualTo(before);assertThat(state()).isEqualTo(initialState);assertThat(quota()).isEqualTo(initialQuota);assertThat(evidence()).isEqualTo(logs);
    }
}

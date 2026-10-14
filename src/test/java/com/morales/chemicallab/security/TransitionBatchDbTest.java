package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.service.TransitionBatchMaintenanceService;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL-only P06 tests, including real OS death, unknown commit and competing JVMs. */
class TransitionBatchDbTest {
    ContextReconciliationDbTest b;
    WhiteboardSecurityTransportDbTest f;
    TransitionBatchMaintenanceService service;
    String expansion,ctl;
    UUID plan;
    @BeforeEach void start(TestInfo info) throws Exception {
        b=new ContextReconciliationDbTest();b.start(info);f=b.f;
        service=f.app.getBean(TransitionBatchMaintenanceService.class);
        expansion=b.r.script("t11b11-v1");f.sql().execute(expansion);
        ctl="t06_"+f.schema.substring(4);plan=UUID.randomUUID();
    }
    @AfterEach void stop() throws Exception {
        if(f!=null)f.sql().execute("drop schema "+ctl+" cascade");
        if(b!=null)b.stop();
    }
    UUID op(){return UUID.randomUUID();}
    long count(String sql,Object...args){return b.count(sql,args);}
    long approved() throws Exception {long p=b.proposal();b.approvals(p);return p;}
    void create(long...ids){service.create(plan,Arrays.stream(ids).boxed().toList(),TransitionBatchMaintenanceService.Limits.fixture(),op());}
    JsonNode observation(){return service.observe(plan);}
    UUID unit(int ordinal){return UUID.fromString(observation().get("units").get(ordinal-1).get("operation_id").asText());}
    JsonNode step(int ordinal,long epoch){return service.step(plan,ordinal,epoch,unit(ordinal));}
    Map<String,String> state(){
        var result=new TreeMap<>(b.state());
        for(String t:List.of("schema_version","plans","units","commands","checkpoints"))result.put(ctl+"."+t,f.sql().queryForObject(b.r.signature(ctl+"."+t),String.class));
        return result;
    }
    @Test void pauseResumeAndReplayPreserveAggregatesAndAtomicCommittedCheckpoints() throws Exception {
        long p=approved();long board=b.proposal("whiteboard_sessions",f.board,b.r.foreign,b.r.foreignClass);b.approvals(board);
        var original=b.b.b8.previous.previous.signatures("evaluations","evaluation_assignments","evaluation_attempts","whiteboard_sessions","whiteboard_object_ownership","t11b9_items","t11b9_counters");
        create(board,p);
        assertThat(step(1,1).get("status").asText()).isEqualTo("APPLIED");
        service.pause(plan,1,op());var before=state();
        assertThatThrownBy(()->step(2,1)).hasMessageContaining("obsolete worker");assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->step(2,2)).hasMessageContaining("paused");assertThat(state()).isEqualTo(before);
        service.resume(plan,2,op());
        assertThat(step(2,3).get("state").asText()).isEqualTo("COMPLETED");
        var completed=state();assertThat(step(2,3).get("status").asText()).isEqualTo("APPLIED");assertThat(state()).isEqualTo(completed);
        assertThat(count("select count(*) from "+ctl+".checkpoints")).isEqualTo(2);
        assertThat(count("select count(*) from "+ctl+".checkpoints c join "+ctl+".commands o using(operation_id) join system_logs l on l.id=c.audit_id where c.xid=o.xid and l.outcome='SUCCESS' and l.correlation_id=c.operation_id")).isEqualTo(2);
        assertThat(b.b.b8.previous.previous.signatures(original.keySet().toArray(String[]::new))).isEqualTo(original);
    }
    @Test void sourceChangedBetweenBatchesPreservesStaleAndBothPriorApprovals() throws Exception {
        long p=approved();create(p);service.pause(plan,1,op());
        f.sql().update("update evaluations set title='Changed fixture source' where id=?",b.r.evaluation);
        service.resume(plan,2,op());assertThat(step(1,3).get("status").asText()).isEqualTo("STALE");
        assertThat(count("select count(*) from t11b10_approvals where proposal_id=?",p)).isEqualTo(2);
        assertThat(count("select count(*) from t11b10_versions where proposal_id=?",p)).isZero();
        assertThat(observation().get("state").asText()).isEqualTo("PAUSED");
        assertThat(count("select count(*) from "+ctl+".checkpoints where status='STALE'")).isEqualTo(1);
    }
    @Test void revokedF08PrivilegeCannotBeReusedAfterResume() throws Exception {
        long p=approved();create(p);service.pause(plan,1,op());
        f.sql().update("update membership_role_grants set status='REVOKED',valid_until=statement_timestamp(),changed_at=statement_timestamp() where id=?",b.schoolGrant);
        service.resume(plan,2,op());assertThat(step(1,3).get("status").asText()).isEqualTo("STALE");
        assertThat(count("select count(*) from t11b10_approvals where proposal_id=?",p)).isEqualTo(2);
    }
    @Test void auditFailureRollsBackBusinessMeasurementCheckpointAndSuccess() throws Exception {
        create(approved());var before=state();
        f.sql().execute("create function fixture_p06_fail() returns trigger language plpgsql as $$ begin if NEW.correlation_id='"+unit(1)+"'::uuid then raise exception 'fixture audit unavailable';end if;return NEW;end $$");
        f.sql().execute("create trigger fixture_p06_fail before insert on system_logs for each row execute function fixture_p06_fail()");
        try{assertThatThrownBy(()->step(1,1)).isInstanceOf(DataAccessException.class).hasMessageContaining("audit unavailable");}
        finally{f.sql().execute("drop trigger fixture_p06_fail on system_logs;drop function fixture_p06_fail()");}
        assertThat(state()).isEqualTo(before);assertThat(step(1,1).get("status").asText()).isEqualTo("APPLIED");
    }
    @Test void quotaFailureRollsBackBusinessCheckpointAndAudit() throws Exception {
        create(approved());b.b.service.limit(b.r.foreign,"ORIGINAL",1,1,op());var before=state();
        assertThatThrownBy(()->step(1,1)).isInstanceOf(DataAccessException.class).hasMessageContaining("quota exceeded");assertThat(state()).isEqualTo(before);
    }
    @Test void explicitEnvelopeRejectsBeforeScanningB10AndNeverSplitsAggregate() throws Exception {
        long p=approved();var before=state();
        assertThatThrownBy(()->service.create(plan,List.of(p),new TransitionBatchMaintenanceService.Limits(1,67108864,256,2097152),op())).hasMessageContaining("row fixture envelope");
        assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->service.create(plan,List.of(p),new TransitionBatchMaintenanceService.Limits(20000,67108864,1,2097152),op())).hasMessageContaining("whole aggregate");assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->service.create(plan,List.of(p),new TransitionBatchMaintenanceService.Limits(20001,67108864,256,2097152),op())).hasMessageContaining("invalid bounded");
        assertThat(state()).isEqualTo(before);
    }
    @Test void successorRepeatsWithoutTouchingFrozenLedgersAndRejectsDrift() throws Exception {
        var before=state();f.sql().execute(expansion);assertThat(state()).isEqualTo(before);
        assertThat(count("select count(*) from t11p06_control_usage() where scope='CONTROL' and rows_used=1 and bytes_used>0")).isEqualTo(1);
        b.b.b8.previous.previous.previous.base.tx().executeWithoutResult(s->{
            f.sql().execute("alter table "+ctl+".checkpoints disable trigger p06_immutable");
            assertThatThrownBy(()->f.sql().execute(expansion)).hasMessageContaining("T11-B11 divergence");s.setRollbackOnly();
        });
        f.sql().execute(expansion);assertThat(state()).isEqualTo(before);
        assertThat(count("select count(*) from pg_proc where pronamespace=current_schema()::regnamespace and proname like 't11b11_%'")).isZero();
        assertThatThrownBy(()->f.sql().update("delete from "+ctl+".schema_version")).hasMessageContaining("generated checkpoint");
    }
    @Test void actualProcessDeathBeforeCommitLeavesNoCheckpointOrBusiness() throws Exception {
        create(approved());var before=state();var child=worker("before-commit",1,1);
        try{await(child.ready());child.process().destroyForcibly();assertThat(child.process().waitFor(15,TimeUnit.SECONDS)).isTrue();}
        finally{child.process().destroyForcibly();}
        assertThat(service.lookup(unit(1)).isNull()).isTrue();assertThat(state()).isEqualTo(before);
        assertThat(step(1,1).get("status").asText()).isEqualTo("APPLIED");evidence("killed-before-commit",Map.of("pid",child.process().pid(),"unchanged",true));
    }
    @Test void confirmedCommitWithLostResponseIsQueriedBeforeRetry() throws Exception {
        create(approved());var child=worker("lost-response",1,1);
        try{await(child.ready());child.process().destroyForcibly();assertThat(child.process().waitFor(15,TimeUnit.SECONDS)).isTrue();}
        finally{child.process().destroyForcibly();}
        assertThat(Files.exists(child.result())).isFalse();var persisted=service.lookup(unit(1));assertThat(persisted.get("result").get("status").asText()).isEqualTo("APPLIED");
        var before=state();assertThat(step(1,1)).isEqualTo(persisted.get("result"));assertThat(state()).isEqualTo(before);
        evidence("lost-response",Map.of("pid",child.process().pid(),"persisted",persisted,"duplicateEffects",false));
    }
    @Test void independentExecutorsReturnOneCommittedUnitAndOneSuccess() throws Exception {
        create(approved());var a=worker("race",1,1);var c=worker("race",1,1);
        try {
            await(a.ready());await(c.ready());assertThat(a.process().pid()).isNotEqualTo(c.process().pid());
            try(var barrier=connection()) {
                barrier.setAutoCommit(false);barrier.createStatement().execute("select t11p05_lock()");
                Files.writeString(a.go(),"go");Files.writeString(c.go(),"go");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                while(count("select count(*) from pg_stat_activity where pid in (?,?) and wait_event_type='Lock'",Integer.parseInt(Files.readString(a.ready())),Integer.parseInt(Files.readString(c.ready())))<2&&System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(count("select count(*) from pg_stat_activity where pid in (?,?) and wait_event_type='Lock'",Integer.parseInt(Files.readString(a.ready())),Integer.parseInt(Files.readString(c.ready())))).isEqualTo(2);barrier.commit();
            }
            for(var child:List.of(a,c)){assertThat(child.process().waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(child.process().exitValue()).as(Files.readString(child.log())).isZero();}
            assertThat(Files.readString(a.result())).isEqualTo(Files.readString(c.result()));
            assertThat(count("select count(*) from "+ctl+".checkpoints")).isEqualTo(1);
            assertThat(count("select count(*) from system_logs where correlation_id=?",unit(1))).isEqualTo(1);
            evidence("race",Map.of("pids",List.of(a.process().pid(),c.process().pid()),"result",Files.readString(a.result()),"checkpoints",1,"successes",1));
        } finally {a.process().destroyForcibly();c.process().destroyForcibly();}
    }
    @Test void physicalBackupAndWalRecoverConfirmedLaterWritesAndRetryWithoutDuplication() throws Exception {
        create(approved());new TransitionWalRecoveryFixture(this).exercise();
    }
    Connection connection() throws Exception {return DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));}
    record Child(Process process,Path ready,Path go,Path result,Path log){}
    Child worker(String mode,int ordinal,long epoch) throws Exception {
        Path dir=Files.createDirectories(Path.of("target/t11b11-processes",f.schema,UUID.randomUUID().toString()));
        Path request=dir.resolve("request.json"),ready=dir.resolve("ready"),go=dir.resolve("go"),result=dir.resolve("result.json"),log=dir.resolve("process.log");
        Files.writeString(request,f.json.writeValueAsString(Map.of("operation",unit(ordinal),"action","STEP","request",Map.of("plan",plan,"ordinal",ordinal,"epoch",epoch))));
        var cmd=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),TransitionBatchProcess.class.getName(),f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.schema,request.toAbsolutePath().toString(),ready.toAbsolutePath().toString(),go.toAbsolutePath().toString(),result.toAbsolutePath().toString(),mode);
        var pb=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());pb.environment().put("P06_FIXTURE_PASSWORD",f.setting("spring.datasource.password"));
        return new Child(pb.start(),ready,go,result,log);
    }
    void await(Path path) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!Files.exists(path)&&System.nanoTime()<end)Thread.sleep(25);
        assertThat(Files.exists(path)).as("barrier %s",path).isTrue();
    }
    void evidence(String name,Object value) throws Exception {
        Path dir=Files.createDirectories(Path.of("target/t11b11-evidence",f.schema));Files.writeString(dir.resolve(name+".json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}

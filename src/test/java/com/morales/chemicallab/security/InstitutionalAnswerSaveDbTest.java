package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.HttpResponse;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/T02, PostgreSQL, academic writer, B10/B11 and commit-coupled audit. */
class InstitutionalAnswerSaveDbTest {
    InstitutionalEvaluationReadDbTest h;
    EvaluationDeliveryAccessDbTest e;
    WhiteboardSecurityTransportDbTest f;
    ResourceReconciliationDbTest r;
    Request q;
    long open,choice,option,foreignQuestion,foreignOption;
    String ctl;
    JdbcTemplate sql(){return f.sql();}
    @BeforeEach void start(TestInfo info) throws Exception {
        h=new InstitutionalEvaluationReadDbTest();h.start(info);e=h.e;f=h.f;r=h.r;
        sql().execute(r.script("t12c-v1"));ctl="t12c_"+f.schema.substring(4);
        h.activation.put("app.evaluations.institutional.answer-save-enabled",true);
        q=e.legacy(Action.READ_OWN_ATTEMPT);
        open=question(q.evaluationId(),"OPEN_TEXT",0);choice=question(q.evaluationId(),"MULTIPLE_CHOICE",1);
        option=option(choice);foreignQuestion=question(e.request(true,Action.READ_DELIVERY).evaluationId(),"MULTIPLE_CHOICE",0);foreignOption=option(foreignQuestion);
        sql().update("update evaluation_attempts set status='IN_PROGRESS',grade_closed=false,question_order=?,current_question_index=0,started_at=clock_timestamp() at time zone 'America/Lima' where id=?",open+","+choice,q.attemptId());
        sql().update("update evaluation_assignments set due_at=(clock_timestamp() at time zone 'America/Lima')+interval '1 hour' where id=?",r.assignment);
        e.resolve(q);
    }
    @AfterEach void stop() throws Exception {
        if(ctl!=null && f.app!=null && f.app.isActive())sql().execute("drop schema "+ctl+" cascade");
        if(h!=null)h.stop();
    }
    long question(long evaluation,String type,int order){return sql().queryForObject("insert into evaluation_questions(evaluation_id,question_text,question_type,points,order_index,required,active,expected_answer,explanation) values(?,'Synthetic question',?,1,?,false,true,'PRIVATE-EXPECTED-T12B-1937','PRIVATE-CORRECTION-T12B-64c9') returning id",Long.class,evaluation,type,order);}
    long option(long question){return sql().queryForObject("insert into evaluation_options(question_id,option_text,correct,order_index,active) values(?,'Synthetic option',true,0,true) returning id",Long.class,question);}
    String path(Request request){return h.base(request).replace("evaluation-reads","evaluation-writes")+"/own-attempts/"+request.attemptId()+"/answers";}
    String body(UUID op,long question,Long option,String text) throws Exception {
        Map<String,Object> b=new HashMap<>();b.put("operationId",op);b.put("questionId",question);b.put("selectedOptionId",option);b.put("answerText",text);return f.json.writeValueAsString(b);
    }
    HttpResponse<String> post(String token,Request request,String body) throws Exception {var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create(f.origin+path(request)))
            .timeout(java.time.Duration.ofSeconds(45)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        var response=f.http.send(builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());h.assertNoSecrets(response.body());return response;}
    JsonNode save(UUID op,long question,Long option,String text) throws Exception {var response=post(h.pupil,q,body(op,question,option,text));assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");return f.json.readTree(response.body());}
    long count(String query,Object...args){return e.count(query,args);}
    long receipts(){return count("select count(*) from "+ctl+".saves");}
    void sequential() throws Exception {sql().update("update evaluations set question_display_mode='ONE_BY_ONE' where id=?",q.evaluationId());e.resolve(q);}
    @Test void readSaveReadUsesExactSuccessorWithoutRewritingFrozenApprovalsOrBudgets() throws Exception {
        var before=h.ok(h.pupil,q);assertThat(before.path("answers")).isEmpty();
        var frozen=e.c.b.b8.previous.previous.signatures("t11b10_proposals","t11b10_approvals","t11b10_versions","t11b10_measurements","t11b3_account_attempt_budgets","t11b3_attempt_consumptions","t11b8_budgets");
        JsonNode saved=save(UUID.randomUUID(),open,null,"  My synthetic answer  ");
        assertThat(h.names(saved)).containsExactlyInAnyOrder("operationId","attemptId","questionId","selectedOptionId","answerText","currentQuestionIndex","savedAt");
        assertThat(h.ok(h.pupil,q).path("answers").get(0).path("answerText").asText()).isEqualTo("My synthetic answer");
        assertThat(e.c.b.b8.previous.previous.signatures(frozen.keySet().toArray(String[]::new))).isEqualTo(frozen);
        assertThat(count("select count(*) from t11b10_versions where source_table='evaluations' and source_pk=jsonb_build_object('id',?::bigint) and t11p05_fresh(proposal_id)",q.evaluationId())).isZero();
        assertThat(receipts()).isEqualTo(1);assertThat(count("select count(*) from "+ctl+".saves s join system_logs l on l.id=s.audit_id where l.outcome='SUCCESS' and l.correlation_id=s.operation_id and s.xid is not null")).isEqualTo(1);
        save(UUID.randomUUID(),open,null,"Updated synthetic answer");
        save(UUID.randomUUID(),choice,option,null);
        assertThat(h.ok(h.pupil,q).path("answers")).hasSize(2);
        assertThat(count("select count(*) from evaluation_answers where attempt_id=?",q.attemptId())).isEqualTo(2);
    }
    @Test void sequentialRetryReturnsOriginalReceiptAndAdvancesExactlyOnce() throws Exception {
        sequential();UUID operation=UUID.randomUUID();JsonNode first=save(operation,open,null,"A");
        assertThat(save(operation,open,null,"A")).isEqualTo(first);
        assertThat(receipts()).isEqualTo(1);assertThat(count("select current_question_index from evaluation_attempts where id=?",q.attemptId())).isEqualTo(1);
        assertThat(post(h.pupil,q,body(operation,open,null,"different")).statusCode()).isNotEqualTo(200);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,null,"back")).statusCode()).isEqualTo(403);
        save(UUID.randomUUID(),choice,option,null);
        assertThat(count("select current_question_index from evaluation_attempts where id=?",q.attemptId())).isEqualTo(2);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),choice,option,null)).statusCode()).isEqualTo(403);
        assertThat(receipts()).isEqualTo(2);
    }
    @Test void futureQuestionIsRejectedAndUnrelatedIdentifiersNeverAcquireWriteAuthority() throws Exception {
        sequential();assertThat(post(h.pupil,q,body(UUID.randomUUID(),choice,option,null)).statusCode()).isEqualTo(403);
        for(long id:List.of(foreignQuestion,Long.MAX_VALUE))assertThat(post(h.pupil,q,body(UUID.randomUUID(),id,null,"X")).statusCode()).isEqualTo(403);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,foreignOption,"X")).statusCode()).isEqualTo(403);
        assertThat(post(h.peer,q,body(UUID.randomUUID(),open,null,"X")).statusCode()).isEqualTo(403);
        assertThat(post(h.teacher,q,body(UUID.randomUUID(),open,null,"X")).statusCode()).isEqualTo(403);
        Request other=e.request(true,Action.READ_OWN_ATTEMPT);
        for(Request bad:List.of(e.changed(q,r.foreign,q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),other.attemptId()),other))
            assertThat(post(h.pupil,bad,body(UUID.randomUUID(),open,null,"X")).statusCode()).isEqualTo(403);
        assertThat(receipts()).isZero();
    }
    @Test void revokedSessionTemporaryPasswordAndRemovedPermissionDenyWithoutMutation() throws Exception {
        String command=body(UUID.randomUUID(),open,null,"X");
        SessionPrincipal p=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(h.pupil).getPrincipal();
        sql().update("update account_sessions set revoked_at=clock_timestamp() where id=?",p.sessionId());assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(401);
        f.mutate(f.student,u->u.setTemporaryPassword(true));h.pupil=f.login(f.student);assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(403);
        f.mutate(f.student,u->u.setTemporaryPassword(false));h.pupil=f.login(f.student);e.resolve(q);
        long grant=count("select id from membership_role_grants where membership_id=? and role='ESTUDIANTE'",r.member);
        r.institutions.revokeRole(grant,java.time.Instant.now());assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(403);assertThat(receipts()).isZero();
    }
    @Test void expiredScheduledInactiveAndTerminalAttemptsCannotWriteAlthoughReadMaySucceed() throws Exception {
        String command=body(UUID.randomUUID(),open,null,"X");
        for(String mutation:List.of("update evaluation_attempts set status='GRADED' where id="+q.attemptId(),
            "update evaluation_attempts set status='IN_PROGRESS',started_at='2026-01-01 00:00:00' where id="+q.attemptId(),
            "update evaluation_assignments set due_at=(clock_timestamp() at time zone 'America/Lima')-interval '1 second' where id="+r.assignment)) {
            sql().update(mutation);e.resolve(q);h.ok(h.pupil,q);assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(403);
        }
        sql().update("update evaluation_assignments set due_at=(clock_timestamp() at time zone 'America/Lima')+interval '1 hour',start_at=(clock_timestamp() at time zone 'America/Lima')+interval '10 minutes' where id=?",r.assignment);
        e.resolve(q);assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(403);
        sql().update("update evaluation_assignments set start_at=(clock_timestamp() at time zone 'America/Lima')-interval '1 hour' where id=?",r.assignment);
        sql().update("update evaluation_attempts set started_at=clock_timestamp() at time zone 'America/Lima',active=false where id=?",q.attemptId());
        e.resolve(q);assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(403);
        assertThat(receipts()).isZero();
    }
    @Test void defaultOffRequiresEveryReadGateAndIndependentDatabaseGuard() throws Exception {
        String command=body(UUID.randomUUID(),open,null,"X");
        h.activation.remove("app.evaluations.institutional.answer-save-enabled");assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(404);
        h.activation.put("app.evaluations.institutional.answer-save-enabled",true);h.activation.remove("app.evaluations.institutional.fixture-schema");assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(404);
        h.enable();h.activation.put("app.evaluations.institutional.fixture-schema","t03_"+"a".repeat(32));assertThat(post(h.pupil,q,command).statusCode()).isEqualTo(403);assertThat(receipts()).isZero();
    }
    @Test void auditFailureRollsBackAnswerCursorReceiptAndMeasurement() throws Exception {
        sequential();var before=e.c.state();
        sql().execute("create function fixture_t12c_fail() returns trigger language plpgsql as $$ begin if NEW.action='SAVE_INSTITUTIONAL_ANSWER' then raise exception 'fixture mandatory audit failed';end if;return NEW;end $$");
        sql().execute("create trigger fixture_t12c_fail before insert on system_logs for each row execute function fixture_t12c_fail()");
        try {assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,null,"X")).statusCode()).isEqualTo(503);}finally{sql().execute("drop trigger fixture_t12c_fail on system_logs;drop function fixture_t12c_fail()");}
        assertThat(receipts()).isZero();assertThat(count("select count(*) from evaluation_answers where attempt_id=?",q.attemptId())).isZero();assertThat(count("select current_question_index from evaluation_attempts where id=?",q.attemptId())).isZero();
        assertThat(count("select count(*) from system_logs where action='SAVE_INSTITUTIONAL_ANSWER'")).isZero();
        save(UUID.randomUUID(),open,null,"X");
    }
    @Test void directLegacyWriterAndPostReceiptTamperingCannotBeAutomaticallyLegitimized() throws Exception {
        save(UUID.randomUUID(),open,null,"Original");
        sql().update("update evaluation_answers set answer_text='Outside protocol' where attempt_id=?",q.attemptId());
        assertThat(h.get(h.pupil,q).statusCode()).isEqualTo(403);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),choice,option,null)).statusCode()).isEqualTo(403);
        assertThat(receipts()).isEqualTo(1);
    }
    @Test void originalWriterBeforeFirstReceiptIsStaleAndCannotBeAdoptedBySavingAgain() throws Exception {
        sql().update("insert into evaluation_answers(attempt_id,question_id,answer_text,answered_at) values(?,?,'Unadopted',clock_timestamp() at time zone 'America/Lima')",q.attemptId(),open);
        assertThat(h.get(h.pupil,q).statusCode()).isEqualTo(403);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,null,"Institutional retry")).statusCode()).isEqualTo(403);
        assertThat(receipts()).isZero();
    }
    @Test void existingInstitutionalQuotaRejectsGrowthAndRollsBackEverySaveEffect() throws Exception {
        sequential();
        e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,UUID.randomUUID());e.resolve(q);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,null,"Growing answer")).statusCode()).isEqualTo(503);
        assertThat(receipts()).isZero();assertThat(count("select current_question_index from evaluation_attempts where id=?",q.attemptId())).isZero();
        assertThat(count("select count(*) from evaluation_answers where attempt_id=?",q.attemptId())).isZero();
        assertThat(count("select count(*) from system_logs where action='SAVE_INSTITUTIONAL_ANSWER'")).isZero();
    }
    @Test void additiveCertificationAndNativeRecoveryPreserveReceiptAndLostResponseRetry() throws Exception {
        sequential();UUID op=UUID.randomUUID();JsonNode first=save(op,open,null,"Durable answer");
        sql().execute(r.script("t12c-v1"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(f.app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(s->{sql().execute("alter table "+ctl+".saves disable trigger immutable");
            assertThatThrownBy(()->sql().execute(rUncheckedScript())).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        java.nio.file.Path dir=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/t12c-recovery",f.schema));
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));
        sql().update("update authentication_limits set expires_at=greatest(expires_at,floor(extract(epoch from clock_timestamp())*1000)::bigint+3600000)");
        Map<String,String> before=new TreeMap<>();
        for(String schema:List.of(f.schema,"t06_"+f.schema.substring(4),ctl))for(String table:sql().queryForList("select tablename from pg_tables where schemaname=? order by tablename",String.class,schema))
            before.put(schema+"."+table,sql().queryForObject(r.signature(schema+"."+table),String.class));
        r.nativeTool("pg_dump",args,List.of("-Fc","-n",f.schema,"-n","t06_"+f.schema.substring(4),"-n",ctl,"-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
        f.app.close();
        try(var statement=f.control.createStatement()) {
            statement.execute("drop schema "+ctl+" cascade;drop schema t06_"+f.schema.substring(4)+" cascade;drop schema "+f.schema+" cascade");
        }
        r.nativeTool("pg_restore",args,List.of("--single-transaction","--exit-on-error","-d","chemicallab_t01_test",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
        Map<String,String> restored=new TreeMap<>();
        try(var statement=f.control.createStatement()) {
            statement.execute("set search_path to "+f.schema+",public");statement.execute(r.script("t12c-v1"));
            for(String table:before.keySet())try(var rows=statement.executeQuery(r.signature(table))){rows.next();restored.put(table,rows.getString(1));}
        }
        assertThat(restored).isEqualTo(before);
        java.nio.file.Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("before",before,"restored",restored)));
        f.app=f.startApp("--spring.jpa.hibernate.ddl-auto=none");f.origin=f.origin(f.app);
        assertThat(save(op,open,null,"Durable answer")).isEqualTo(first);
        assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("Durable answer");assertThat(receipts()).isEqualTo(1);
    }
    String rUncheckedScript(){try{return r.script("t12c-v1");}catch(Exception ex){throw new IllegalStateException(ex);}}
    @Test void freshF08CorrectionPrecedesAllOldReceiptsAndNewSavesBindToNewMap() throws Exception {
        save(UUID.randomUUID(),open,null,"Original");long original=count("select proposal_id from "+ctl+".saves");
        e.resolve(q,"UNRESOLVED");assertThat(h.get(h.pupil,q).statusCode()).isEqualTo(403);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,null,"X")).statusCode()).isEqualTo(403);
        assertThat(sql().queryForObject("select "+ctl+".fresh(?)",Boolean.class,original)).isFalse();
        e.resolve(q);save(UUID.randomUUID(),open,null,"Under successor map");assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("Under successor map");
        assertThat(count("select count(distinct proposal_id) from "+ctl+".saves")).isEqualTo(2);
    }
    @Test void historicalOrderP2AndUncertainQuestionOrOptionStillDenyBeforeWrite() throws Exception {
        sql().update("update evaluation_attempts set question_order=? where id=?",foreignQuestion+","+open,q.attemptId());e.resolve(q);
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,null,"X")).statusCode()).isEqualTo(403);
        sql().update("update evaluation_attempts set question_order=? where id=?",open+","+choice,q.attemptId());e.resolve(q,"UNRESOLVED","evaluation_questions");
        assertThat(post(h.pupil,q,body(UUID.randomUUID(),open,null,"X")).statusCode()).isEqualTo(403);
        e.resolve(q,"UNRESOLVED","evaluation_options");assertThat(post(h.pupil,q,body(UUID.randomUUID(),choice,option,null)).statusCode()).isEqualTo(403);assertThat(receipts()).isZero();
    }
    @Test void concurrentSameOperationHasOneCommitAndOneSequentialAdvance() throws Exception {
        sequential();String command=body(UUID.randomUUID(),open,null,"Concurrent");
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> a=threads.submit(()->post(h.pupil,q,command)),b=threads.submit(()->post(h.pupil,q,command));
            var first=a.get(25,TimeUnit.SECONDS);var second=b.get(25,TimeUnit.SECONDS);
            assertThat(first.statusCode()).as(first.body()).isEqualTo(200);assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
            assertThat(f.json.readTree(first.body())).isEqualTo(f.json.readTree(second.body()));assertThat(receipts()).isEqualTo(1);
            assertThat(count("select current_question_index from evaluation_attempts where id=?",q.attemptId())).isEqualTo(1);
        }finally{threads.shutdownNow();}
    }
    Connection connection() throws Exception{return h.connection();}
    void race(String mutation,int nextStatus) throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var saves=f.app.getBean(InstitutionalAnswerSaveService.class);JdbcTemplate spy=spy(sql());
        doAnswer(call->{Object result=call.callRealMethod();locked.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return result;})
            .when(spy).queryForObject(contains(".begin_save("),eq(String.class),any(Object[].class));
        ReflectionTestUtils.setField(saves,"jdbc",spy);ExecutorService threads=Executors.newFixedThreadPool(2);
        try(Connection writer=connection()) {
            int pid;try(var s=writer.createStatement();var rows=s.executeQuery("select pg_backend_pid()")){rows.next();pid=rows.getInt(1);}
            String command=body(UUID.randomUUID(),open,null,"Before concurrent change");
            Future<HttpResponse<String>> saving=threads.submit(()->post(h.pupil,q,command));assertThat(locked.await(12,TimeUnit.SECONDS)).isTrue();
            Future<?> change=threads.submit(()->{try(var s=writer.createStatement()){s.execute(mutation);}return null;});h.waitForBlocked(pid);
            release.countDown();var result=saving.get(25,TimeUnit.SECONDS);assertThat(result.statusCode()).as(result.body()).isEqualTo(200);change.get(25,TimeUnit.SECONDS);
            assertThat(post(h.pupil,q,body(UUID.randomUUID(),choice,option,null)).statusCode()).isEqualTo(nextStatus);assertThat(receipts()).isEqualTo(1);
        }finally{release.countDown();threads.shutdownNow();ReflectionTestUtils.setField(saves,"jdbc",sql());}
    }
    @Test void revocationWaitsForSaveAndNextRequestIsUnauthorized() throws Exception {
        SessionPrincipal p=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(h.pupil).getPrincipal();
        race("update account_sessions set revoked_at=clock_timestamp() where id='"+p.sessionId()+"'",401);
    }
    @Test void contextChangeWaitsForSaveAndMakesSuccessorStale() throws Exception {race("update evaluations set title='Outside protocol context' where id="+q.evaluationId(),403);}
    @Test void terminalWriterWaitsForSaveAndThenPreventsFurtherAnswers() throws Exception {race("update evaluation_attempts set status='GRADED' where id="+q.attemptId(),403);}
    @Test void actualLegacySubmissionAndT12BReadSerializeWithInstitutionalSave() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        var saves=f.app.getBean(InstitutionalAnswerSaveService.class);JdbcTemplate spy=spy(sql());
        doAnswer(call->{Object result=call.callRealMethod();locked.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return result;})
            .when(spy).queryForObject(contains(".begin_save("),eq(String.class),any(Object[].class));
        ReflectionTestUtils.setField(saves,"jdbc",spy);ExecutorService threads=Executors.newFixedThreadPool(3);
        try {
            Future<?> saving=threads.submit(()->save(UUID.randomUUID(),open,null,"Before submit"));assertThat(locked.await(15,TimeUnit.SECONDS)).isTrue();
            Future<HttpResponse<String>> reading=threads.submit(()->h.get(h.pupil,q));
            Future<HttpResponse<String>> submission=threads.submit(()->f.request("POST","/api/evaluations/student/attempts/"+q.attemptId()+"/submit",h.pupil,null));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);boolean waiting=false;
            while(System.nanoTime()<until){waiting=Boolean.TRUE.equals(sql().queryForObject("select count(*)>=2 from pg_stat_activity where wait_event_type='Lock' and query ilike '%evaluations%'",Boolean.class));if(waiting)break;Thread.sleep(20);}
            assertThat(waiting).isTrue();release.countDown();saving.get(35,TimeUnit.SECONDS);
            var submitted=submission.get(25,TimeUnit.SECONDS);assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);h.assertNoSecrets(submitted.body());
            var observed=reading.get(25,TimeUnit.SECONDS);assertThat(observed.statusCode()).isIn(200,403);h.assertNoSecrets(observed.body());
            if(observed.statusCode()==200)assertThat(observed.body()).contains("Before submit");
            assertThat(receipts()).isEqualTo(1);assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'",q.attemptId())).isEqualTo(1);
        }finally{release.countDown();threads.shutdownNow();ReflectionTestUtils.setField(saves,"jdbc",sql());}
    }
    @Test void revocationCommittedFirstIsObservedAfterWaitingForTableLocks() throws Exception {
        ExecutorService thread=Executors.newSingleThreadExecutor();
        try(Connection writer=connection()) {
            writer.setAutoCommit(false);try(var s=writer.createStatement()){s.execute("lock table evaluations in exclusive mode");s.execute("update evaluation_attempts set status='GRADED' where id="+q.attemptId());}
            String command=body(UUID.randomUUID(),open,null,"X");Future<HttpResponse<String>> saving=thread.submit(()->post(h.pupil,q,command));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);boolean waiting=false;
            while(System.nanoTime()<until){waiting=Boolean.TRUE.equals(sql().queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query='lock table evaluations in exclusive mode')",Boolean.class));if(waiting)break;Thread.sleep(20);}
            assertThat(waiting).isTrue();writer.commit();assertThat(saving.get(25,TimeUnit.SECONDS).statusCode()).isEqualTo(403);assertThat(receipts()).isZero();
        }finally{thread.shutdownNow();}
    }
}



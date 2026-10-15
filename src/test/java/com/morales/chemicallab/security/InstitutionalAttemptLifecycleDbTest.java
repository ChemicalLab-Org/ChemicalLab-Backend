package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL + HTTP + T02 + frozen B11/C and the additive D successor. */
class InstitutionalAttemptLifecycleDbTest {
    InstitutionalAnswerSaveDbTest s;
    InstitutionalEvaluationReadDbTest h;
    EvaluationDeliveryAccessDbTest e;
    WhiteboardSecurityTransportDbTest f;
    ResourceReconciliationDbTest r;
    Request q;
    String ctl;
    JdbcTemplate sql(){return f.sql();}
    long count(String query,Object...args){return e.count(query,args);}
    @BeforeEach void start(TestInfo info) throws Exception {
        s=new InstitutionalAnswerSaveDbTest();s.start(info);h=s.h;e=s.e;f=s.f;r=s.r;q=s.q;
        sql().execute(r.script("t12d-v1"));ctl="t12d_"+f.schema.substring(4);
        h.activation.put("app.evaluations.institutional.attempt-lifecycle-enabled",true);
        sql().update("update evaluation_attempts set status='GRADED',active=false where id=?",q.attemptId());
        sql().update("update evaluations set max_attempts=3 where id=?",q.evaluationId());
        e.resolve(q);
    }
    @AfterEach void stop() throws Exception {
        if(ctl!=null && f.app!=null && f.app.isActive())sql().execute("drop schema "+ctl+" cascade");
        if(s!=null)s.stop();
    }
    Request own(long attempt){return new Request(q.contract(),Action.READ_OWN_ATTEMPT,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.student.getId(),attempt);}
    String base(Request request){return h.base(request).replace("evaluation-reads","evaluation-writes")+"/own-attempts";}
    String startBody(UUID op){return "{\"operationId\":\""+op+"\"}";}
    String submitBody(UUID op,List<Map<String,Object>> answers) throws Exception {var b=new HashMap<String,Object>();b.put("operationId",op);b.put("answers",answers);return f.json.writeValueAsString(b);}
    Map<String,Object> answer(long question,Long option,String text){var b=new HashMap<String,Object>();b.put("questionId",question);b.put("selectedOptionId",option);b.put("answerText",text);return b;}
    HttpResponse<String> post(String token,String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(java.net.URI.create(f.origin+path)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        var response=f.http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());h.assertNoSecrets(response.body());return response;
    }
    JsonNode ok(HttpResponse<String> response) throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");return f.json.readTree(response.body());}
    JsonNode begin(UUID op) throws Exception {return ok(post(h.pupil,base(q),startBody(op)));}
    JsonNode finish(long attempt,UUID op,List<Map<String,Object>> answers) throws Exception {return ok(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(op,answers)));}
    long begun() throws Exception{return begin(UUID.randomUUID()).path("attemptId").asLong();}
    long receipts(){return count("select count(*) from "+ctl+".saves");}
    @Test void completeJourneyUsesOneBudgetAndRecoveryReadsArePure() throws Exception {
        h.ok(h.pupil,e.legacy(Action.READ_DELIVERY));
        var frozen=e.c.b.b8.previous.previous.signatures("t11b10_approvals","t11b10_versions","t11b3_account_attempt_budgets","t11b3_attempt_consumptions","t11b8_budgets");
        UUID start=UUID.randomUUID();JsonNode first=begin(start);long attempt=first.path("attemptId").asLong();
        assertThat(h.names(first)).containsExactlyInAnyOrder("operationId","attemptId","status","attemptNumber","startedAt","submittedAt");
        assertThat(first.path("attemptNumber").asInt()).isEqualTo(2);assertThat(begin(start)).isEqualTo(first);
        q=own(attempt);s.q=q;JsonNode recovered=h.ok(h.pupil,q);
        assertThat(Instant.parse(recovered.path("startedAt").asText())).isEqualTo(LocalDateTime.parse(first.path("startedAt").asText()).atZone(EvaluationAccessPolicy.ACADEMIC_ZONE).toInstant());
        String before=sql().queryForObject(r.signature("evaluation_attempts"),String.class);
        assertThat(h.ok(h.pupil,q).path("questionOrder")).isEqualTo(recovered.path("questionOrder"));
        assertThat(sql().queryForObject(r.signature("evaluation_attempts"),String.class)).isEqualTo(before);
        s.save(UUID.randomUUID(),s.open,null,"Saved before submit");
        UUID submit=UUID.randomUUID();var terminal=finish(attempt,submit,List.of(answer(s.choice,s.option,null)));
        assertThat(terminal.path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(finish(attempt,submit,List.of(answer(s.choice,s.option,null)))).isEqualTo(terminal);
        assertThat(h.ok(h.pupil,q).path("answers")).hasSize(2);
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isEqualTo(2);
        assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_STARTED'",attempt)).isEqualTo(1);
        assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'",attempt)).isEqualTo(1);
        assertThat(receipts()).isEqualTo(3);
        assertThat(count("select count(*) from evaluation_answers where attempt_id=? and question_id=? and reviewed and points_awarded=1",attempt,s.choice)).isEqualTo(1);
        assertThat(e.c.b.b8.previous.previous.signatures(frozen.keySet().toArray(String[]::new))).isEqualTo(frozen);
        Request correction=new Request(q.contract(),Action.READ_CORRECTION,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.student.getId(),attempt);
        h.forbidden(h.pupil,correction);
        assertThat(begin(start)).isEqualTo(first); // replay after a subsequent terminal transition
        assertThat(begun()).isNotEqualTo(attempt); // closing frees active, never refunds consumption
        assertThat(receipts()).isEqualTo(4);
    }
    @Test void optionalAnswersAndRequiredOpenQuestionsKeepLegacySemantics() throws Exception {
        sql().update("update evaluation_questions set required=true where id=?",s.open);e.resolve(q);
        long attempt=begun();
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),null)).statusCode()).isEqualTo(403);
        assertThat(count("select count(*) from evaluation_answers where attempt_id=?",attempt)).isZero();assertThat(receipts()).isEqualTo(1);
        finish(attempt,UUID.randomUUID(),List.of(answer(s.open,null,"Required answer")));
        assertThat(count("select count(*) from evaluation_attempts where status='PENDING_MANUAL_REVIEW' and id=?",attempt)).isEqualTo(1);
    }
    @Test void expiredAcademicWindowClosesOnlySavedAnswersAndNeverMutatesOnGet() throws Exception {
        sql().update("update evaluation_questions set required=true where id=?",s.open);e.resolve(q);
        long attempt=begun();q=own(attempt);s.q=q;s.save(UUID.randomUUID(),s.choice,s.option,null);
        sql().update("update evaluation_assignments set due_at=(clock_timestamp() at time zone 'America/Lima')-interval '1 second' where id=?",r.assignment);e.resolve(q);
        String before=sql().queryForObject(r.signature("evaluation_attempts"),String.class);h.ok(h.pupil,q);
        assertThat(sql().queryForObject(r.signature("evaluation_attempts"),String.class)).isEqualTo(before);
        UUID op=UUID.randomUUID();var result=finish(attempt,op,List.of(answer(s.open,null,"MUST BE IGNORED"),answer(s.choice,null,null)));
        assertThat(count("select count(*) from evaluation_answers where attempt_id=? and answer_text is not null",attempt)).isZero();
        assertThat(count("select selected_option_id from evaluation_answers where attempt_id=? and question_id=?",attempt,s.choice)).isEqualTo(s.option);
        assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='TIME_EXPIRED'",attempt)).isEqualTo(1);
        assertThat(finish(attempt,op,List.of(answer(s.open,null,"MUST BE IGNORED"),answer(s.choice,null,null)))).isEqualTo(result);
        assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        h.ok(h.pupil,q);
    }
    @Test void oneByOneDoesNotReprocessSubmissionAndPreservesCursorAndOrder() throws Exception {
        sql().update("update evaluations set question_display_mode='ONE_BY_ONE' where id=?",q.evaluationId());e.resolve(q);
        long attempt=begun();q=own(attempt);s.q=q;
        s.save(UUID.randomUUID(),s.open,null,"Original");s.save(UUID.randomUUID(),s.choice,s.option,null);
        var before=h.ok(h.pupil,q);finish(attempt,UUID.randomUUID(),List.of(answer(s.open,null,"Ignored"),answer(s.foreignQuestion,s.foreignOption,null)));
        var after=h.ok(h.pupil,q);assertThat(after.path("questionOrder")).isEqualTo(before.path("questionOrder"));
        assertThat(after.path("currentQuestionIndex").asInt()).isEqualTo(2);
        assertThat(after.path("answers").toString()).contains("Original").doesNotContain("Ignored");
    }
    @Test void historicalInactiveAndUnassignedRowsConsumeTheSameLastOpportunity() throws Exception {
        r.attempt(2,AttemptStatus.GRADED,null,false);e.resolve(q);
        var result=begin(UUID.randomUUID());assertThat(result.path("attemptNumber").asInt()).isEqualTo(3);
        finish(result.path("attemptId").asLong(),UUID.randomUUID(),null);
        assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isEqualTo(3);
    }
    @Test void activeOtherContextIsNeitherReturnedNorReassigned() throws Exception {
        sql().update("update evaluation_attempts set status='IN_PROGRESS',assignment_id=null where id=?",q.attemptId());
        // Keep uncertainty for that historical row; the requested delivery itself is resolved.
        e.resolve(q,"UNRESOLVED","evaluation_attempts");
        assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        assertThat(count("select count(*) from evaluation_attempts where assignment_id is null and status='IN_PROGRESS' and id=?",q.attemptId())).isEqualTo(1);assertThat(receipts()).isZero();
    }
    @Test void scheduledUnavailableAndInactiveContextsCannotStartOrClose() throws Exception {
        sql().update("update evaluation_assignments set start_at=(clock_timestamp() at time zone 'America/Lima')+interval '10 minutes' where id=?",r.assignment);e.resolve(q);
        assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        sql().update("update evaluation_assignments set start_at=(clock_timestamp() at time zone 'America/Lima')-interval '1 hour' where id=?",r.assignment);e.resolve(q);
        long attempt=begun();
        sql().update("update evaluation_assignments set active=false where id=?",r.assignment);e.resolve(q);
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),null)).statusCode()).isEqualTo(403);
        assertThat(count("select count(*) from evaluation_attempts where id=? and status='IN_PROGRESS'",attempt)).isEqualTo(1);
    }
    @Test void replaysRevalidateSessionRolesAndContextAndBindContent() throws Exception {
        UUID op=UUID.randomUUID();long attempt=begin(op).path("attemptId").asLong();
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(op,null)).statusCode()).isEqualTo(409);
        UUID submit=UUID.randomUUID();finish(attempt,submit,null);
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(submit,List.of(answer(s.open,null,"Changed")))).statusCode()).isEqualTo(409);
        SessionPrincipal principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(h.pupil).getPrincipal();
        sql().update("update account_sessions set revoked_at=clock_timestamp() where id=?",principal.sessionId());
        assertThat(post(h.pupil,base(q),startBody(op)).statusCode()).isEqualTo(401);
        h.pupil=f.login(f.student);f.mutate(f.student,u->u.setTemporaryPassword(true));h.pupil=f.login(f.student);
        assertThat(post(h.pupil,base(q),startBody(op)).statusCode()).isEqualTo(403);
        f.mutate(f.student,u->u.setTemporaryPassword(false));h.pupil=f.login(f.student);e.resolve(q);
        long grant=count("select id from membership_role_grants where membership_id=? and role='ESTUDIANTE'",r.member);r.institutions.revokeRole(grant,Instant.now());
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(submit,null)).statusCode()).isEqualTo(403);
        assertThat(receipts()).isEqualTo(2);
    }
    @Test void crossInstitutionIdentifiersAndOtherAccountsCannotStartOrSubmit() throws Exception {
        Request other=e.request(true,Action.READ_DELIVERY);e.resolve(other);
        for(Request bad:List.of(other,e.changed(q,r.foreign,q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.student.getId(),null),e.changed(q,q.institutionId(),other.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.student.getId(),null),e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),other.deliveryId(),q.revisionId(),f.student.getId(),null)))
            assertThat(post(h.pupil,base(bad),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        for(String actor:List.of(h.peer,h.teacher,h.admin))assertThat(post(actor,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        long attempt=begun();assertThat(post(h.peer,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),null)).statusCode()).isEqualTo(403);
    }
    @Test void independentFlagsAndTransportBoundsRejectBeforeBusinessMutation() throws Exception {
        h.activation.remove("app.evaluations.institutional.attempt-lifecycle-enabled");assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(404);
        h.activation.put("app.evaluations.institutional.attempt-lifecycle-enabled",true);
        assertThat(post(h.pupil,base(q),"{\"operationId\":\""+UUID.randomUUID()+"\",\"padding\":\""+"X".repeat(1048576)+"\"}").statusCode()).isEqualTo(413);
        long attempt=begun();var many=new ArrayList<Map<String,Object>>();for(int i=0;i<201;i++)many.add(answer(s.open,null,"A"));
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),many)).statusCode()).isEqualTo(400);
        assertThat(receipts()).isEqualTo(1);
    }
    void failAudit(String action,boolean start) throws Exception {
        long attempt=start?0:begun();long before=receipts();
        sql().execute("create function fixture_t12d_fail() returns trigger language plpgsql as $$ begin if NEW.action='"+action+"' then raise exception 'fixture audit failure';end if;return NEW;end $$");
        sql().execute("create trigger fixture_t12d_fail before insert on system_logs for each row execute function fixture_t12d_fail()");
        String signature=sql().queryForObject(r.signature("evaluation_attempts"),String.class);
        try {assertThat(post(h.pupil,base(q)+(start?"":"/"+attempt+"/submit"),start?startBody(UUID.randomUUID()):submitBody(UUID.randomUUID(),List.of(answer(s.choice,s.option,null)))).statusCode()).isEqualTo(503);}
        finally{sql().execute("drop trigger fixture_t12d_fail on system_logs;drop function fixture_t12d_fail()");}
        assertThat(receipts()).isEqualTo(before);assertThat(sql().queryForObject(r.signature("evaluation_attempts"),String.class)).isEqualTo(signature);
        if(!start){assertThat(count("select count(*) from evaluation_answers where attempt_id=?",attempt)).isZero();assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'",attempt)).isZero();}
    }
    @Test void mandatoryAuditFailureRollsBackStartAndItsEvent() throws Exception {failAudit("START_INSTITUTIONAL_ATTEMPT",true);}
    @Test void mandatoryAuditFailureRollsBackSubmitAnswersGradesAndEvents() throws Exception {failAudit("SUBMIT_INSTITUTIONAL_ATTEMPT",false);}
    @Test void institutionalQuotaFailureRollsBackWholeStart() throws Exception {
        e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,UUID.randomUUID());e.resolve(q);
        assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(503);
        assertThat(receipts()).isZero();assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isEqualTo(1);
    }
    @Test void latestUncertainF08AndDirectDriftCannotBeLegitimizedByReceipts() throws Exception {
        UUID op=UUID.randomUUID();long attempt=begin(op).path("attemptId").asLong();q=own(attempt);
        e.resolve(q,"UNRESOLVED",null);assertThat(post(h.pupil,base(q),startBody(op)).statusCode()).isEqualTo(403);
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),null)).statusCode()).isEqualTo(403);
        e.resolve(q);h.ok(h.pupil,q);
        sql().update("update evaluations set title='Outside protocol' where id=?",q.evaluationId());
        h.forbidden(h.pupil,q);assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),null)).statusCode()).isEqualTo(403);
    }
    @Test void additiveCertificationAndPhysicalRecoveryKeepNewAttemptTerminalAndReceipts() throws Exception {
        UUID start=UUID.randomUUID();JsonNode first=begin(start);long attempt=first.path("attemptId").asLong();q=own(attempt);s.q=q;
        UUID op=UUID.randomUUID();JsonNode terminal=finish(attempt,op,List.of(answer(s.open,null,"Durable answer"),answer(s.choice,s.option,null)));
        sql().execute(r.script("t12d-v1"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(f.app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(s->{sql().execute("alter table "+ctl+".saves disable trigger immutable");
            assertThatThrownBy(()->sql().execute(rUncheckedScript())).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        java.nio.file.Path dir=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/t12d-recovery",f.schema)).toAbsolutePath();
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));
        sql().update("update authentication_limits set expires_at=greatest(expires_at,floor(extract(epoch from clock_timestamp())*1000)::bigint+3600000)");
        Map<String,String> before=new TreeMap<>();
        for(String schema:List.of(f.schema,"t06_"+f.schema.substring(4),"t12c_"+f.schema.substring(4),ctl))for(String table:sql().queryForList("select tablename from pg_tables where schemaname=? order by tablename",String.class,schema))
            before.put(schema+"."+table,sql().queryForObject(r.signature(schema+"."+table),String.class));
        var backup=dir.resolve("copy");
        r.nativeTool("pg_basebackup",args,List.of("-D",backup.toString(),"--wal-method=stream","--checkpoint=fast"),dir);
        r.nativeTool("pg_verifybackup",List.of(),List.of(backup.toString()),dir);
        int copyPort=TransitionWalRecoveryFixture.port();
        java.nio.file.Files.writeString(backup.resolve("postgresql.auto.conf"),"listen_addresses='127.0.0.1'\nport="+copyPort+"\nmax_connections=20\nmax_locks_per_transaction=512\nshared_buffers='16MB'\n");
        boolean started=false;String sourceOrigin=f.origin;
        org.springframework.context.ConfigurableApplicationContext copyApp=null;
        try {
            r.nativeTool("pg_ctl",List.of(),List.of("-D",backup.toString(),"-l",dir.resolve("copy-postgres.log").toString(),"-w","start"),dir);started=true;
            String copyUrl="jdbc:postgresql://127.0.0.1:"+copyPort+"/chemicallab_t01_test";
            try(Connection copy=DriverManager.getConnection(copyUrl,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));var statement=copy.createStatement()) {
                statement.execute("set search_path to "+f.schema+",public");statement.execute(r.script("t12d-v1"));
                Map<String,String> restored=new TreeMap<>();
                for(String table:before.keySet())try(var rows=statement.executeQuery(r.signature(table))){rows.next();restored.put(table,rows.getString(1));}
                assertThat(restored).isEqualTo(before);
                java.nio.file.Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("before",before,"restored",restored,"copyPort",copyPort,"method","physical backup after institutional start and submit, not PITR")));
                copyApp=f.startApp("--spring.datasource.url="+copyUrl+"?currentSchema="+f.schema,"--spring.jpa.hibernate.ddl-auto=none",
                    "--app.evaluations.institutional.enabled=true","--app.evaluations.institutional.attempt-lifecycle-enabled=true","--app.evaluations.institutional.answer-save-enabled=true","--app.evaluations.institutional.fixture-schema="+f.schema);
                f.origin=f.origin(copyApp);
                assertThat(begin(start)).isEqualTo(first);
                assertThat(finish(attempt,op,List.of(answer(s.open,null,"Durable answer"),answer(s.choice,s.option,null)))).isEqualTo(terminal);
                assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("Durable answer");
                try(var rows=statement.executeQuery("select count(*) from "+ctl+".saves")){rows.next();assertThat(rows.getInt(1)).isEqualTo(2);}
                try(var rows=statement.executeQuery("select count(*) from evaluation_attempts where id="+q.attemptId()+" and status='PENDING_MANUAL_REVIEW'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
            }
        }finally{
            f.origin=sourceOrigin;if(copyApp!=null)copyApp.close();
            if(started)r.nativeTool("pg_ctl",List.of(),List.of("-D",backup.toString(),"-m","fast","-w","stop"),dir);
        }
    }
    @Test void secondDeliveryAndTechnicalRevisionNeverRefundTheLogicalAccountBudget() throws Exception {
        long first=begun();finish(first,UUID.randomUUID(),null);
        sql().update("update evaluation_assignments set grade='3',section='A' where id=?",r.secondAssignment);
        long secondDelivery=count("select id from t11b3_evaluation_deliveries where legacy_assignment_id=?",r.secondAssignment);
        Request original=q;
        q=new Request(Contract.LEGACY,Action.READ_DELIVERY,r.institution,r.otherClass,r.evaluation,secondDelivery,
            count("select revision_id from t11b3_evaluation_deliveries where id=?",secondDelivery),f.student.getId(),null);
        e.resolve(q);var last=begin(UUID.randomUUID());assertThat(last.path("attemptNumber").asInt()).isEqualTo(3);
        assertThat(count("select assignment_id from evaluation_attempts where id=?",last.path("attemptId").asLong())).isEqualTo(r.secondAssignment);
        // Reassigning that active row to the first delivery would falsify provenance.
        assertThat(post(h.pupil,base(original),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        finish(last.path("attemptId").asLong(),UUID.randomUUID(),null);
        r.service.technicalRevision(ResourceReconciliationService.SourceKind.EVALUATION,q.revisionId());e.resolve(q);
        assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        q=original;assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",r.evaluation)).isEqualTo(3);
    }
    @Test void twoInstitutionLegacyRootsKeepSharedAccountAndDerivedMeasurementsSeparate() throws Exception {
        long first=begun();finish(first,UUID.randomUUID(),List.of(answer(s.open,null,"In A")));
        Request a=own(first);
        // A new explicit F08 correction of the root to B takes precedence over A receipts.
        q=e.changed(a,r.foreign,r.foreignClass,a.evaluationId(),a.deliveryId(),a.revisionId(),f.student.getId(),null);e.resolve(q);
        long second=begun();q=own(second);s.q=q;s.save(UUID.randomUUID(),s.open,null,"In B");finish(second,UUID.randomUUID(),null);
        h.forbidden(h.pupil,a);assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("In B").doesNotContain("In A");
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",r.evaluation)).isEqualTo(3);
        assertThat(post(h.pupil,base(q),startBody(UUID.randomUUID())).statusCode()).isEqualTo(403);
        Request b=q;
        var teacher=f.app.getBean(com.morales.chemicallab.repository.TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        long root=f.app.getBean(com.morales.chemicallab.repository.EvaluationRepository.class).saveAndFlush(
            Evaluation.builder().title("Independent legacy A").status(EvaluationStatus.PUBLISHED).maxAttempts(2).timeLimitMinutes(30).createdByTeacher(teacher).build()).getId();
        long assignment=r.assignment(root,teacher,LocalDateTime.now(ZoneId.of("America/Lima")).plusHours(1));
        long question=s.question(root,"OPEN_TEXT",0);
        long prepared=r.approved(ResourceReconciliationService.SourceKind.EVALUATION,root,r.map(List.of(r.delivery(assignment,r.classId,r.episode)),List.of()));
        assertThat(r.service.apply(prepared)).isEqualTo("APPLIED");
        long delivery=count("select id from t11b3_evaluation_deliveries where legacy_assignment_id=?",assignment);
        q=new Request(Contract.LEGACY,Action.READ_DELIVERY,r.institution,r.classId,root,delivery,
            count("select revision_id from t11b3_evaluation_deliveries where id=?",delivery),f.student.getId(),null);e.resolve(q);
        long independent=begun();q=own(independent);finish(independent,UUID.randomUUID(),List.of(answer(question,null,"Independent in A")));
        assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("Independent in A").doesNotContain("In B");
        assertThat(h.ok(h.pupil,b).path("answers").toString()).contains("In B").doesNotContain("Independent in A");
        assertThat(count("select (x->>'owner_id')::bigint from "+ctl+".saves receipt cross join lateral jsonb_array_elements(receipt.dataset) x where receipt.ordinal=(select max(ordinal) from "+ctl+".saves) and x->>'source_table'='evaluation_attempts' and x->'source_pk'=jsonb_build_object('id',?::bigint)",second)).isEqualTo(r.foreign);
    }
    @Test void startReplayCannotExposeAttemptMadeUncertainByLatestF08() throws Exception {
        UUID op=UUID.randomUUID();long attempt=begin(op).path("attemptId").asLong();q=own(attempt);
        // Delivery remains readable/start-authorized while this attempt alone is uncertain.
        uncertainAttempts();
        assertThat(post(h.pupil,base(q),startBody(op)).statusCode()).isEqualTo(403);
        assertThat(receipts()).isEqualTo(1);
    }
    @Test void automaticChoiceGradingClosesWithoutRevealingCorrection() throws Exception {
        sql().update("update evaluation_questions set active=false where id=?",s.open);e.resolve(q);
        long attempt=begun();var result=finish(attempt,UUID.randomUUID(),List.of(answer(s.choice,s.option,null)));
        assertThat(result.path("status").asText()).isEqualTo("GRADED");
        assertThat(count("select count(*) from evaluation_attempts where id=? and grade_closed and score=1 and graded_at is not null",attempt)).isEqualTo(1);
        q=own(attempt);h.ok(h.pupil,q);
        Request correction=new Request(q.contract(),Action.READ_CORRECTION,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.student.getId(),attempt);
        h.forbidden(h.pupil,correction);
    }
    void uncertainAttempts() throws Exception {
        var map=f.json.readTree(e.c.mapping("evaluations",q.evaluationId(),q.institutionId(),q.classId()));
        for(var item:map.get("resolutions"))if(Set.of("evaluation_attempts","evaluation_answers","evaluation_attempt_events","evaluation_attempt_adjustments").contains(item.path("source").asText())) {
            var row=(com.fasterxml.jackson.databind.node.ObjectNode)item;row.remove(List.of("institutionId","classId","accountId","enrollmentId","sourceTimezone"));row.put("scope","UNRESOLVED");
        }
        long proposal=e.c.service.propose(e.c.root("evaluations",q.evaluationId()),map.toString(),"FIXTURE-UNCERTAIN-ATTEMPTS",UUID.randomUUID());
        e.c.approve(proposal,"PLATFORM",e.c.platform,e.c.platformGrant);e.c.approve(proposal,"INSTITUTION",e.c.school,e.c.foreignSchoolGrant);
        assertThat(e.c.service.apply(proposal,UUID.randomUUID())).isEqualTo("APPLIED");
    }
    @Test void finalizationQuotaFailureRollsBackNewAnswersGradesAndSubmissionEvent() throws Exception {
        long attempt=begun();e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,UUID.randomUUID());e.resolve(q);
        assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),List.of(answer(s.choice,s.option,null)))).statusCode()).isEqualTo(503);
        assertThat(receipts()).isEqualTo(1);
        assertThat(count("select count(*) from evaluation_answers where attempt_id=?",attempt)).isZero();
        assertThat(count("select count(*) from evaluation_attempts where id=? and status='IN_PROGRESS' and submitted_at is null and score is null",attempt)).isEqualTo(1);
        assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'",attempt)).isZero();
    }
    @Test void certifiedSuccessorRetainsPredecessorSaveReceiptAndItsNewAnswerContext() throws Exception {
        sql().execute("drop schema "+ctl+" cascade");
        sql().update("update evaluation_attempts set active=true,status='IN_PROGRESS' where id=?",q.attemptId());e.resolve(q);
        UUID op=UUID.randomUUID();var prior=s.save(op,s.open,null,"Saved under C");
        assertThat(count("select count(*) from "+s.ctl+".saves")).isEqualTo(1);
        sql().execute(r.script("t12d-v1"));assertThat(s.save(op,s.open,null,"Saved under C")).isEqualTo(prior);
        finish(q.attemptId(),UUID.randomUUID(),null);
        assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("Saved under C");
        assertThat(count("select count(*) from "+s.ctl+".saves")).isEqualTo(1);assertThat(receipts()).isEqualTo(1);
    }
    @Test void deadlineCrossedAfterPersistingSubmissionRollsBackItsEntireUnit() throws Exception {
        long attempt=begun();
        sql().update("update evaluation_assignments set due_at=(clock_timestamp() at time zone 'America/Lima')+interval '20 seconds' where id=?",r.assignment);e.resolve(q);
        var service=f.app.getBean(InstitutionalAttemptLifecycleService.class);JdbcTemplate spy=spy(sql());
        doAnswer(call->{
            Object value=call.callRealMethod();
            long remaining=count("select greatest(0,ceil(extract(epoch from ((due_at at time zone 'America/Lima')-clock_timestamp()))*1000))::bigint from evaluation_assignments where id=?",r.assignment);
            assertThat(remaining).isPositive();Thread.sleep(remaining+50);return value;
        }).when(spy).queryForObject(contains(".finish_operation("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);
        try {
            assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),List.of(answer(s.open,null,"Late unit")))).statusCode()).isEqualTo(403);
            assertThat(receipts()).isEqualTo(1);assertThat(count("select count(*) from evaluation_answers where attempt_id=?",attempt)).isZero();
            assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'",attempt)).isZero();
            assertThat(count("select count(*) from system_logs where action in ('SUBMIT_INSTITUTIONAL_ATTEMPT','PENDING_INSTITUTIONAL_REVIEW')")).isZero();
            assertThat(count("select count(*) from evaluation_attempts where id=? and status='IN_PROGRESS' and submitted_at is null",attempt)).isEqualTo(1);
        }finally{ReflectionTestUtils.setField(service,"jdbc",sql());}
        finish(attempt,UUID.randomUUID(),List.of(answer(s.open,null,"Ignored after timeout")));
        assertThat(count("select count(*) from evaluation_answers where attempt_id=? and answer_text is not null",attempt)).isZero();
    }
    @Test void approvedF08CorrectionWaitsForCloseAndBecomesStaleInsteadOfTransferringIt() throws Exception {
        long attempt=begun();q=own(attempt);
        long proposal=e.c.service.propose(e.c.root("evaluations",q.evaluationId()),e.c.mapping("evaluations",q.evaluationId(),r.foreign,r.foreignClass),"FIXTURE-CONCURRENT-CORRECTION",UUID.randomUUID());
        e.c.approve(proposal,"PLATFORM",e.c.platform,e.c.platformGrant);e.c.approve(proposal,"INSTITUTION",e.c.school,e.c.schoolGrant);
        var service=f.app.getBean(InstitutionalAttemptLifecycleService.class);JdbcTemplate spy=spy(sql());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{Object value=call.callRealMethod();locked.countDown();assertThat(release.await(12,TimeUnit.SECONDS)).isTrue();return value;})
            .when(spy).queryForObject(contains(".begin_save("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);var threads=Executors.newFixedThreadPool(2);
        try {
            Future<?> closing=threads.submit(()->finish(attempt,UUID.randomUUID(),null));assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
            Future<String> correction=threads.submit(()->e.c.service.apply(proposal,UUID.randomUUID()));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);boolean blocked=false;
            while(System.nanoTime()<until){blocked=Boolean.TRUE.equals(sql().queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query ilike '%t11p05%')",Boolean.class));if(blocked)break;Thread.sleep(20);}
            assertThat(blocked).isTrue();release.countDown();closing.get(45,TimeUnit.SECONDS);
            assertThat(correction.get(30,TimeUnit.SECONDS)).isEqualTo("STALE");h.ok(h.pupil,q);
            assertThat(count("select count(*) from t11b10_versions where proposal_id=?",proposal)).isZero();
        }finally{release.countDown();threads.shutdownNow();ReflectionTestUtils.setField(service,"jdbc",sql());}
    }
    void raceMutation(String mutation,int subsequent) throws Exception {
        long attempt=begun();q=own(attempt);
        var service=f.app.getBean(InstitutionalAttemptLifecycleService.class);JdbcTemplate spy=spy(sql());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{Object value=call.callRealMethod();locked.countDown();assertThat(release.await(12,TimeUnit.SECONDS)).isTrue();return value;})
            .when(spy).queryForObject(contains(".begin_save("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);var threads=Executors.newFixedThreadPool(2);UUID op=UUID.randomUUID();
        String body=submitBody(op,List.of(answer(s.open,null,"Before change")));
        try(Connection writer=h.connection()) {
            int pid;try(var st=writer.createStatement();var rows=st.executeQuery("select pg_backend_pid()")){rows.next();pid=rows.getInt(1);}
            Future<HttpResponse<String>> closing=threads.submit(()->post(h.pupil,base(q)+"/"+attempt+"/submit",body));assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
            Future<?> change=threads.submit(()->{try(var st=writer.createStatement()){st.execute(mutation);}return null;});h.waitForBlocked(pid);
            release.countDown();ok(closing.get(45,TimeUnit.SECONDS));change.get(25,TimeUnit.SECONDS);
            assertThat(post(h.pupil,base(q)+"/"+attempt+"/submit",body).statusCode()).isEqualTo(subsequent);
            assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'",attempt)).isEqualTo(1);
        } finally{release.countDown();threads.shutdownNow();ReflectionTestUtils.setField(service,"jdbc",sql());}
    }
    @Test void revocationWaitsForFinalizationAndThenRejectsItsReplay() throws Exception {
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(h.pupil).getPrincipal();
        raceMutation("update account_sessions set revoked_at=clock_timestamp() where id='"+principal.sessionId()+"'",401);
    }
    @Test void contextWriterWaitsForFinalizationAndThenInvalidatesSuccessor() throws Exception {
        raceMutation("update evaluations set title='Concurrent context change' where id="+q.evaluationId(),403);
    }
    @Test void saveAndFinalizationSerializeWithoutLosingTheWinningSavedAnswer() throws Exception {
        long attempt=begun();q=own(attempt);s.q=q;
        var saves=f.app.getBean(InstitutionalAnswerSaveService.class);JdbcTemplate spy=spy(sql());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{Object value=call.callRealMethod();locked.countDown();assertThat(release.await(12,TimeUnit.SECONDS)).isTrue();return value;})
            .when(spy).queryForObject(contains(".begin_save("),eq(String.class),any(Object[].class));
        ReflectionTestUtils.setField(saves,"jdbc",spy);var threads=Executors.newFixedThreadPool(2);
        try {
            Future<?> saving=threads.submit(()->s.save(UUID.randomUUID(),s.open,null,"Winning answer"));assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
            Future<HttpResponse<String>> closing=threads.submit(()->post(h.pupil,base(q)+"/"+attempt+"/submit",submitBody(UUID.randomUUID(),null)));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);boolean blocked=false;
            while(System.nanoTime()<until){blocked=Boolean.TRUE.equals(sql().queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query='lock table evaluations in exclusive mode')",Boolean.class));if(blocked)break;Thread.sleep(20);}
            assertThat(blocked).isTrue();release.countDown();saving.get(45,TimeUnit.SECONDS);ok(closing.get(45,TimeUnit.SECONDS));
            assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("Winning answer");
            assertThat(s.post(h.pupil,q,s.body(UUID.randomUUID(),s.open,null,"Too late")).statusCode()).isEqualTo(403);
        }finally{release.countDown();threads.shutdownNow();ReflectionTestUtils.setField(saves,"jdbc",sql());}
    }
    @Test void lastOpportunityIsSharedByTwoIndependentJvmsAndLegacyWriter() throws Exception {
        sql().update("update evaluations set max_attempts=2 where id=?",q.evaluationId());e.resolve(q);
        var processes=new ArrayList<Process>();var stops=new ArrayList<java.nio.file.Path>();var origins=new ArrayList<String>();
        var dir=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/t12d-processes",f.schema)).toAbsolutePath();
        var threads=Executors.newFixedThreadPool(3);
        try {
            for(int i=1;i<=2;i++) {
                var ready=dir.resolve(i+".ready");var stop=dir.resolve(i+".stop");stops.add(stop);
                var command=List.of(java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx384m","-Dspring.devtools.restart.enabled=false","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstitutionalLifecycleServer.class.getName(),
                    f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,Base64.getEncoder().encodeToString(f.key),ready.toString(),stop.toString());
                var child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start();processes.add(child);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);while(!java.nio.file.Files.exists(ready)&&child.isAlive()&&System.nanoTime()<until)Thread.sleep(50);
                assertThat(java.nio.file.Files.exists(ready)).as("Child JVM ready: %s",dir).isTrue();origins.add(java.nio.file.Files.readString(ready));
            }
            UUID op=UUID.randomUUID();String command=startBody(op);String original=f.origin;
            try(Connection barrier=h.connection()) {
                barrier.setAutoCommit(false);barrier.createStatement().execute("lock table evaluations in exclusive mode");
                var pending=new ArrayList<Future<HttpResponse<String>>>();
                for(String origin:origins)pending.add(threads.submit(()->f.http.send(HttpRequest.newBuilder(java.net.URI.create(origin+base(q))).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+h.pupil).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(command)).build(),HttpResponse.BodyHandlers.ofString())));
                pending.add(threads.submit(()->f.http.send(HttpRequest.newBuilder(java.net.URI.create(original+"/api/evaluations/student/"+q.evaluationId()+"/attempts")).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+h.pupil).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString())));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);long blocked=0;
                while(System.nanoTime()<until){blocked=count("select count(*) from pg_stat_activity where wait_event_type='Lock' and query ilike '%evaluations%'");if(blocked>=3)break;Thread.sleep(20);}
                assertThat(blocked).isGreaterThanOrEqualTo(3);barrier.rollback();
                var results=new ArrayList<Integer>();for(int index=0;index<pending.size();index++){
                    var response=pending.get(index).get(60,TimeUnit.SECONDS);
                    if(index<2)h.assertNoSecrets(response.body());
                    else {assertThat(response.body()).doesNotContain("PRIVATE-EXPECTED-T12B-1937","PRIVATE-CORRECTION-T12B-64c9");
                        if(response.statusCode()==201)assertThat(f.json.readTree(response.body()).path("score").isNull()).isTrue();}
                    results.add(response.statusCode());
                }
                assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isEqualTo(2);
                assertThat(count("select count(*) from evaluation_attempts where evaluation_id=? and status='IN_PROGRESS'",q.evaluationId())).isEqualTo(1);
                assertThat(count("select max(attempt_number) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isEqualTo(2);
                assertThat(results).anyMatch(code->code==200||code==201);
                assertThat(receipts()).isLessThanOrEqualTo(1);
                java.nio.file.Files.writeString(dir.resolve("evidence.json"),f.json.writeValueAsString(Map.of("osPids",processes.stream().map(Process::pid).toList(),"blocked",blocked,"statuses",results,"attempts",2,"active",1)));
            }finally{f.origin=original;}
        }finally{threads.shutdownNow();for(var stop:stops)java.nio.file.Files.writeString(stop,"stop");for(var child:processes)if(!child.waitFor(15,TimeUnit.SECONDS))child.destroyForcibly();}
    }
    String rUncheckedScript(){try{return r.script("t12d-v1");}catch(Exception ex){throw new IllegalStateException(ex);}}
}

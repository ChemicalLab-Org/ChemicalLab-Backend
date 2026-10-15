package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP, T02, disposable PostgreSQL and frozen B1-B11/C/D/E protocol. */
class InstitutionalAttemptReviewDbTest {
    InstitutionalAttemptLifecycleDbTest d;
    InstitutionalAnswerSaveDbTest s;
    InstitutionalEvaluationReadDbTest h;
    EvaluationDeliveryAccessDbTest e;
    WhiteboardSecurityTransportDbTest f;
    ResourceReconciliationDbTest r;
    Request q;
    String ctl,teacher;
    long answer,responsible;
    JdbcTemplate sql(){return f.sql();}
    long count(String query,Object...args){return sql().queryForObject(query,Long.class,args);}
    @BeforeEach void start(TestInfo info) throws Exception {
        d=new InstitutionalAttemptLifecycleDbTest();d.start(info);s=d.s;h=d.h;e=d.e;f=d.f;r=d.r;
        // First create C/D receipts before installing E, then consume them through E.
        long attempt=d.begun();d.q=d.own(attempt);s.q=d.q;
        s.save(UUID.randomUUID(),s.open,null,"Student text");
        d.finish(attempt,UUID.randomUUID(),List.of(d.answer(s.choice,s.option,null)));
        q=d.own(attempt);answer=count("select id from evaluation_answers where attempt_id=? and question_id=?",attempt,s.open);
        sql().execute(r.script("t12e-v1"));ctl="t12e_"+f.schema.substring(4);
        h.activation.put("app.evaluations.institutional.review-enabled",true);
        long member=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,f.otherTeacher.getId());
        responsible=r.academic.replaceResponsible(e.responsible,member,Instant.now().minusMillis(1),r.until).id();
        teacher=f.login(f.otherTeacher);
        // Assignment changes are independently authoritative. The academic root remains original.
    }
    @AfterEach void stop() throws Exception {
        SecurityContextHolder.clearContext();
        if(ctl!=null && f.app!=null && f.app.isActive())sql().execute("drop schema "+ctl+" cascade");
        if(d!=null)d.stop();
    }
    String base(Request x){return h.base(x).replace("evaluation-reads","evaluation-writes")+"/attempts/"+x.attemptId()+"/review";}
    String body(UUID id,Object...fields) throws Exception {Map<String,Object>b=new LinkedHashMap<>();b.put("operationId",id);for(int i=0;i<fields.length;i+=2)b.put((String)fields[i],fields[i+1]);return f.json.writeValueAsString(b);}
    HttpResponse<String> send(String method,String token,Request x,String suffix,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(f.origin+base(x)+suffix)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return f.http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String token,String suffix,String body) throws Exception{return send("POST",token,q,suffix,body);}
    JsonNode ok(HttpResponse<String> response) throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");return f.json.readTree(response.body());}
    JsonNode read() throws Exception{return ok(send("GET",teacher,q,"",null));}
    JsonNode command(String suffix,Object...fields) throws Exception{return ok(post(teacher,suffix,body(UUID.randomUUID(),fields)));}
    JsonNode grade() throws Exception{return command("/answers/"+answer,"score",1,"feedback"," \tTeacher text\n ");}
    Map<String,String> state(){
        var state=new TreeMap<>(e.c.b.b8.previous.previous.signatures("evaluation_attempts","evaluation_answers","evaluation_attempt_adjustments","evaluation_attempt_events","t11b9_counters",ctl+".saves"));
        // T08 intentionally records bounded REJECTED/FAILED evidence after business
        // rollback. Only SUCCESS participates in the business atomicity assertion.
        state.put("successAudit",sql().queryForObject("select md5(coalesce(jsonb_agg(to_jsonb(x) order by id)::text,'')) from system_logs x where outcome='SUCCESS'",String.class));return state;
    }
    long receipts(){return count("select count(*) from "+ctl+".saves");}
    void forbidden(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(403);h.assertNoSecrets(response.body());}

    @Test void completeJourneyKeepsStudentOwnerHistoricalAuthorAndActualTeacher() throws Exception {
        var frozen=e.c.b.b8.previous.previous.signatures("t11b10_approvals","t11b10_versions","t11b3_account_attempt_budgets","t11b3_attempt_consumptions","t11b8_budgets");
        var before=state();var review=read();assertThat(state()).isEqualTo(before);
        assertThat(review.path("recipientAccountId").asLong()).isEqualTo(f.student.getId());
        assertThat(review.path("openAnswers").get(0).path("expectedAnswer").asText()).isEqualTo("PRIVATE-EXPECTED-T12B-1937");
        forbidden(post(teacher,"/complete",body(UUID.randomUUID())));
        grade();command("/complete");assertThat(read().path("gradeClosed").asBoolean()).isFalse();
        UUID add=UUID.randomUUID();String addBody=body(add,"amount",-1.235,"reason"," \tPrivate reason\n ");
        var adjustment=ok(post(teacher,"/adjustments",addBody));assertThat(ok(post(teacher,"/adjustments",addBody))).isEqualTo(adjustment);
        assertThat(read().path("finalScore").decimalValue()).isEqualByComparingTo("18.76");
        command("/adjustments/"+adjustment.path("childId").asLong()+"/void");
        command("/feedback","overallFeedback"," \tGeneral teacher text\n ");
        UUID close=UUID.randomUUID();String closeBody=body(close);var closed=ok(post(teacher,"/close",closeBody));
        assertThat(ok(post(teacher,"/close",closeBody))).isEqualTo(closed);
        review=read();assertThat(review.path("gradeClosedByAccountId").asLong()).isEqualTo(f.otherTeacher.getId());
        assertThat(review.path("openAnswers").get(0).path("reviewedByAccountId").asLong()).isEqualTo(f.otherTeacher.getId());
        assertThat(count("select t.user_id from evaluations v join teacher_profiles t on t.id=v.created_by_teacher_id where v.id=?",q.evaluationId())).isEqualTo(f.teacher.getId());
        assertThat(count("select s.user_id from evaluation_attempts a join student_profiles s on s.id=a.student_id where a.id=?",q.attemptId())).isEqualTo(f.student.getId());
        assertThat(e.c.b.b8.previous.previous.signatures(frozen.keySet().toArray(String[]::new))).isEqualTo(frozen);
        assertThat(count("select count(*) from system_logs where correlation_id=?",close)).isEqualTo(1);
        assertThat(count("select count(*) from system_logs where correlation_id=? and actor_user_id=? and description='CLOSE_INSTITUTIONAL_REVIEW'",close,f.otherTeacher.getId())).isEqualTo(1);
        for(String suffix:List.of("/complete","/close","/adjustments/"+adjustment.path("childId").asLong()+"/void"))forbidden(post(teacher,suffix,body(UUID.randomUUID())));
        forbidden(post(teacher,"/answers/"+answer,body(UUID.randomUUID(),"score",0)));
        forbidden(post(teacher,"/feedback",body(UUID.randomUUID(),"overallFeedback","After close")));
        forbidden(post(teacher,"/adjustments",body(UUID.randomUUID(),"amount",1,"reason","After close")));
        h.ok(h.pupil,q);h.forbidden(h.pupil,new Request(q.contract(),Action.READ_CORRECTION,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.student.getId(),q.attemptId()));
    }
    @Test void teacherAndAdminFacultiesRequireCurrentAssignmentAndDistinctAccount() throws Exception {
        for(String actor:List.of(h.teacher,h.admin,h.peer,h.pupil)) {
            forbidden(send("GET",actor,q,"",null));forbidden(post(actor,"/answers/"+answer,body(UUID.randomUUID(),"score",1)));
        }
        var delegation=f.app.getBean(DelegationMaintenanceService.class);
        long member=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,e.c.school.getId());
        r.institutions.grantRole(member,DOCENTE,r.from.plusSeconds(20),null);
        var co=delegation.planCoTeacher(r.institution,r.classId,member,r.from.plusSeconds(30),r.until,r.from.plusSeconds(21));
        delegation.transitionCoTeacher(co.id(),TeachingAssignment.Status.ACTIVE,r.from.plusSeconds(30));
        boolean fresh=sql().queryForObject("select "+ctl+".fresh(proposal_id) from t11b10_versions v where source_table='evaluations' and source_pk=jsonb_build_object('id',?::bigint) and not exists(select 1 from t11b10_versions n where n.predecessor_id=v.id)",Boolean.class,q.evaluationId());
        java.nio.file.Path diagnostics=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/t12e-diagnostics"));
        java.nio.file.Files.writeString(diagnostics.resolve(f.schema+"-co-teacher.json"),f.json.writeValueAsString(Map.of("freshBeforeExplicitFixtureReapproval",fresh)));
        // Same fixture setup contract as T12-A: changes to the approving school
        // account's membership/grants are not silently accepted by an old photograph.
        e.resolve(q);
        UUID actorBound=UUID.randomUUID();String grading=body(actorBound,"score",1);
        ok(send("GET",h.admin,q,"",null));ok(post(h.admin,"/answers/"+answer,grading));
        assertThat(post(teacher,"/answers/"+answer,grading).statusCode()).isEqualTo(409);
        assertThat(count("select t.user_id from evaluation_answers a join teacher_profiles t on t.id=a.reviewed_by where a.id=?",answer)).isEqualTo(e.c.school.getId());
        delegation.transitionCoTeacher(co.id(),TeachingAssignment.Status.ENDED,Instant.now());forbidden(send("GET",h.admin,q,"",null));
        r.institutions.grantRole(r.member,DOCENTE,r.from.plusSeconds(20),null);
        var self=delegation.planCoTeacher(r.institution,r.classId,r.member,r.from.plusSeconds(30),r.until,r.from.plusSeconds(21));
        delegation.transitionCoTeacher(self.id(),TeachingAssignment.Status.ACTIVE,r.from.plusSeconds(30));
        e.resolve(q);
        forbidden(post(h.pupil,"/answers/"+answer,body(UUID.randomUUID(),"score",1)));forbidden(send("GET",h.pupil,q,"",null));
    }
    @Test void scoreBoundsPendingOpenAdjustmentsAndClosedGradeKeepLegacyRules() throws Exception {
        var before=state();
        assertThat(post(teacher,"/answers/"+answer,body(UUID.randomUUID(),"score",-1)).statusCode()).isEqualTo(400);
        forbidden(post(teacher,"/answers/"+answer,body(UUID.randomUUID(),"score",2)));
        forbidden(post(teacher,"/close",body(UUID.randomUUID())));
        forbidden(post(teacher,"/adjustments",body(UUID.randomUUID(),"amount",0.001,"reason","Rounds to zero")));
        assertThat(post(teacher,"/adjustments",body(UUID.randomUUID(),"amount",21,"reason","Limit")).statusCode()).isEqualTo(400);
        assertThat(post(teacher,"/adjustments",body(UUID.randomUUID(),"amount",1,"reason"," ")).statusCode()).isEqualTo(400);
        assertThat(state()).isEqualTo(before);
        grade();command("/adjustments","amount",20,"reason","Clamp high");assertThat(read().path("finalScore").decimalValue()).isEqualByComparingTo("20");
        command("/adjustments","amount",-20,"reason","Penalty");command("/adjustments","amount",-20,"reason","Clamp low");assertThat(read().path("finalScore").decimalValue()).isEqualByComparingTo("0");
    }
    @Test void crossInstitutionSelectorsForeignAnswersAndAdjustmentsDeny() throws Exception {
        Request other=e.request(true,Action.READ_OWN_ATTEMPT);e.resolve(other);
        long foreignAnswer=count("insert into evaluation_answers(attempt_id,question_id,answer_text,reviewed) values(?,?,'Other',false) returning id",e.legacyAttempt,s.open);
        long foreignAdjustment=count("insert into evaluation_attempt_adjustments(attempt_id,amount,type,reason,created_by,created_at,active) values(?,1,'BONUS','Other',?,clock_timestamp(),true) returning id",e.legacyAttempt,count("select id from teacher_profiles where user_id=?",f.teacher.getId()));
        e.resolve(q);var before=state();
        for(Request bad:List.of(other,e.changed(q,r.foreign,q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),e.changed(q,q.institutionId(),r.foreignClass,q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),other.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),other.revisionId(),q.recipientAccountId(),q.attemptId())))
            forbidden(send("GET",teacher,bad,"",null));
        forbidden(post(teacher,"/answers/"+foreignAnswer,body(UUID.randomUUID(),"score",1)));
        forbidden(post(teacher,"/adjustments/"+foreignAdjustment+"/void",body(UUID.randomUUID())));
        assertThat(state()).isEqualTo(before);
    }
    @Test void replaysBindActorContentAndCurrentSessionAndAssignment() throws Exception {
        UUID op=UUID.randomUUID();String body=body(op,"score",1,"feedback","Bound");var first=ok(post(teacher,"/answers/"+answer,body));
        assertThat(ok(post(teacher,"/answers/"+answer,body))).isEqualTo(first);
        assertThat(post(teacher,"/answers/"+answer,body(op,"score",0,"feedback","Bound")).statusCode()).isEqualTo(409);
        assertThat(post(teacher,"/complete",body(op)).statusCode()).isEqualTo(409);
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(teacher).getPrincipal();
        sql().update("update account_sessions set revoked_at=clock_timestamp() where id=?",principal.sessionId());
        assertThat(post(teacher,"/answers/"+answer,body).statusCode()).isEqualTo(401);teacher=f.login(f.otherTeacher);
        r.academic.transitionAssignment(responsible,TeachingAssignment.Status.ENDED,Instant.now());
        forbidden(post(teacher,"/answers/"+answer,body));assertThat(receipts()).isEqualTo(1);
    }
    @Test void libraryPermissionsNeverRestoreRevokedTeacherAuthority() throws Exception {
        var grants=f.app.getBean(DelegationMaintenanceService.class);
        long m=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,f.teacher.getId());
        long admin=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,e.c.school.getId());
        Instant at=Instant.now().minusSeconds(2);e.c.authenticate(e.c.school);
        try{grants.grantResource(r.institution,ResourceManagerGrant.Kind.EVALUATION,r.evaluation,m,ResourceManagerGrant.Faculty.PUBLISH,admin,at.plusSeconds(1),r.until,at);}finally{SecurityContextHolder.clearContext();}
        e.resolve(q);forbidden(send("GET",h.teacher,q,"",null));forbidden(post(h.teacher,"/close",body(UUID.randomUUID())));read();
    }
    @Test void uncertainF08AndDirectDriftDenyReadWriteAndReplay() throws Exception {
        UUID id=UUID.randomUUID();String body=body(id,"amount",1,"reason","Original");ok(post(teacher,"/adjustments",body));
        e.resolve(q,"UNRESOLVED","evaluation_attempt_adjustments");forbidden(send("GET",teacher,q,"",null));forbidden(post(teacher,"/adjustments",body));
        e.resolve(q);read();sql().update("update evaluation_answers set answer_text='Outside protocol' where id=?",answer);
        forbidden(send("GET",teacher,q,"",null));forbidden(post(teacher,"/close",body(UUID.randomUUID())));
    }
    @Test void originalCReceiptReplaysAfterEAndItsAnswerRetainsStudentProvenance(TestInfo info) throws Exception {
        var original=new InstitutionalAnswerSaveDbTest();original.start(info);
        String successor=null,lifecycle=null;
        try {
            UUID id=UUID.randomUUID();var saved=original.save(id,original.open,null,"Original C answer");
            String frozen=original.sql().queryForObject(original.r.signature(original.ctl+".saves"),String.class);
            original.sql().execute(original.r.script("t12d-v1"));lifecycle="t12d_"+original.f.schema.substring(4);
            original.sql().execute(original.r.script("t12e-v1"));successor="t12e_"+original.f.schema.substring(4);
            original.h.activation.put("app.evaluations.institutional.attempt-lifecycle-enabled",true);
            original.h.activation.put("app.evaluations.institutional.review-enabled",true);
            assertThat(original.save(id,original.open,null,"Original C answer")).isEqualTo(saved);
            assertThat(original.sql().queryForObject(original.r.signature(original.ctl+".saves"),String.class)).isEqualTo(frozen);
            Request target=original.q;
            String submit=original.h.base(target).replace("evaluation-reads","evaluation-writes")+"/own-attempts/"+target.attemptId()+"/submit";
            var builder=HttpRequest.newBuilder(URI.create(original.f.origin+submit)).timeout(Duration.ofSeconds(60))
                .header("Content-Type","application/json").header("Authorization","Bearer "+original.h.pupil);
            var submitted=original.f.http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body(UUID.randomUUID()))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(200);
            String review=original.h.base(target).replace("evaluation-reads","evaluation-writes")+"/attempts/"+target.attemptId()+"/review";
            var response=original.f.http.send(HttpRequest.newBuilder(URI.create(original.f.origin+review)).timeout(Duration.ofSeconds(60)).header("Authorization","Bearer "+original.h.teacher).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(original.f.json.readTree(response.body()).path("recipientAccountId").asLong()).isEqualTo(original.f.student.getId());
            assertThat(response.body()).contains("Original C answer");
            original.h.ok(original.h.pupil,target);
            assertThat(original.sql().queryForObject(original.r.signature(original.ctl+".saves"),String.class)).isEqualTo(frozen);
        }finally {
            if(successor!=null)original.sql().execute("drop schema "+successor+" cascade");
            if(lifecycle!=null)original.sql().execute("drop schema "+lifecycle+" cascade");
            original.stop();
        }
    }
    @Test void dReceiptsSurviveTeacherClosureAndNewCAndDWritesUseTheSameSuccessor() throws Exception {
        UUID start=UUID.fromString(sql().queryForObject("select operation_id::text from "+d.ctl+".saves where request->>'operation'='START'",String.class));
        UUID submit=UUID.fromString(sql().queryForObject("select operation_id::text from "+d.ctl+".saves where request->>'operation'='SUBMIT'",String.class));
        String frozen=sql().queryForObject(r.signature(d.ctl+".saves"),String.class);
        grade();command("/close");var first=d.begin(start);
        assertThat(first.path("attemptId").asLong()).isEqualTo(q.attemptId());
        assertThat(d.finish(q.attemptId(),submit,List.of(d.answer(s.choice,s.option,null))).path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(sql().queryForObject(r.signature(d.ctl+".saves"),String.class)).isEqualTo(frozen);
        long next=d.begun();d.q=d.own(next);s.q=d.q;s.save(UUID.randomUUID(),s.open,null,"Successor answer");
        d.finish(next,UUID.randomUUID(),List.of(d.answer(s.choice,s.option,null)));
        assertThat(h.ok(h.pupil,d.q).path("answers").toString()).contains("Successor answer");
        assertThat(ok(send("GET",teacher,d.q,"",null)).path("recipientAccountId").asLong()).isEqualTo(f.student.getId());
        assertThat(count("select count(*) from "+ctl+".saves where request->>'operation' in ('START','SAVE','SUBMIT')")).isEqualTo(3);
        assertThat(sql().queryForObject(r.signature(d.ctl+".saves"),String.class)).isEqualTo(frozen);
    }
    @Test void defaultFlagsProfileSchemaAndTransportBoundsRemainIndependent() throws Exception {
        h.activation.remove("app.evaluations.institutional.review-enabled");assertThat(send("GET",teacher,q,"",null).statusCode()).isEqualTo(404);
        h.activation.put("app.evaluations.institutional.review-enabled",true);
        assertThat(post(teacher,"/feedback",body(UUID.randomUUID(),"overallFeedback","X".repeat(1501))).statusCode()).isEqualTo(400);
        assertThat(post(teacher,"/feedback",body(UUID.randomUUID(),"padding","X".repeat(1048576))).statusCode()).isEqualTo(413);
        assertThat(send("GET",null,q,"",null).statusCode()).isEqualTo(401);
        Request newer=new Request(Contract.NEW,q.action(),q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId());forbidden(send("GET",teacher,newer,"",null));
        assertThat(receipts()).isZero();
    }
    @Test void mandatoryAuditFailureRollsBackGradeAttributionAndReceipt() throws Exception {
        var before=state();
        sql().execute("create function fixture_t12e_fail() returns trigger language plpgsql as $$ begin if NEW.action='GRADE_INSTITUTIONAL_REVIEW' then raise exception 'fixture audit failure';end if;return NEW;end $$");
        sql().execute("create trigger fixture_t12e_fail before insert on system_logs for each row execute function fixture_t12e_fail()");
        try{assertThat(post(teacher,"/answers/"+answer,body(UUID.randomUUID(),"score",1)).statusCode()).isEqualTo(503);}
        finally{sql().execute("drop trigger fixture_t12e_fail on system_logs;drop function fixture_t12e_fail()");}
        assertThat(state()).isEqualTo(before);read();
    }
    @Test void institutionalQuotaFailureRollsBackNewAdjustmentAndFinalScore() throws Exception {
        e.c.b.service.limit(q.institutionId(),"AUXILIARY",1,1,UUID.randomUUID());var before=state();
        assertThat(post(teacher,"/adjustments",body(UUID.randomUUID(),"amount",1,"reason","Quota fail")).statusCode()).isEqualTo(503);
        assertThat(state()).isEqualTo(before);read();
    }
    @Test void mandatoryAuditFailureRollsBackClosureAndClosingActor() throws Exception {
        grade();var before=state();
        sql().execute("create function fixture_t12e_close_fail() returns trigger language plpgsql as $$ begin if NEW.action='CLOSE_INSTITUTIONAL_REVIEW' then raise exception 'fixture close audit failure';end if;return NEW;end $$");
        sql().execute("create trigger fixture_t12e_close_fail before insert on system_logs for each row execute function fixture_t12e_close_fail()");
        try{assertThat(post(teacher,"/close",body(UUID.randomUUID())).statusCode()).isEqualTo(503);}
        finally{sql().execute("drop trigger fixture_t12e_close_fail on system_logs;drop function fixture_t12e_close_fail()");}
        assertThat(state()).isEqualTo(before);assertThat(read().path("gradeClosed").asBoolean()).isFalse();
    }
    @Test void institutionalQuotaFailureRollsBackClosureAndClosingActor() throws Exception {
        grade();e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,UUID.randomUUID());var before=state();
        assertThat(post(teacher,"/close",body(UUID.randomUUID())).statusCode()).isEqualTo(503);
        assertThat(state()).isEqualTo(before);assertThat(read().path("gradeClosed").asBoolean()).isFalse();
    }
    @Test void finalSessionRevalidationRollsBackAlreadyFinishedJournalAndGrade() throws Exception {
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(teacher).getPrincipal();
        sql().update("update account_sessions set expires_at=clock_timestamp()+interval '15 seconds' where id=?",principal.sessionId());
        var before=state();String sessionBefore=sql().queryForObject(r.signature("account_sessions"),String.class);
        var service=f.app.getBean(InstitutionalAttemptReviewService.class);JdbcTemplate spy=spy(sql());
        var finished=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{Object value=call.callRealMethod();finished.set(true);
            long remaining=count("select greatest(0,ceil(extract(epoch from (expires_at-clock_timestamp()))*1000))::bigint from account_sessions where id=?",principal.sessionId());
            Thread.sleep(remaining+50);return value;})
            .when(spy).queryForObject(contains(".finish_operation("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);
        try{assertThat(post(teacher,"/answers/"+answer,body(UUID.randomUUID(),"score",1)).statusCode()).isEqualTo(401);}
        finally{ReflectionTestUtils.setField(service,"jdbc",sql());}
        assertThat(finished).isTrue();assertThat(state()).isEqualTo(before);assertThat(sql().queryForObject(r.signature("account_sessions"),String.class)).isEqualTo(sessionBefore);
        teacher=f.login(f.otherTeacher);grade();
    }
    @Test void studentCorrectionRequiresGlobalClosureAndRemainsSafeBeforeIt() throws Exception {
        grade();command("/close");
        Request correction=new Request(q.contract(),Action.READ_CORRECTION,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.student.getId(),q.attemptId());
        h.forbidden(h.pupil,correction);h.assertNoSecrets(h.get(h.pupil,q).body());
        // Other assignments include future, empty and undated groups. Individual
        // grade closure cannot release any of them; T05's global due boundary can.
        sql().update("update evaluation_assignments set start_at=(clock_timestamp() at time zone 'America/Lima')-interval '2 hours',due_at=(clock_timestamp() at time zone 'America/Lima')-interval '1 hour' where evaluation_id=?",q.evaluationId());
        e.resolve(q);var result=h.ok(h.pupil,correction);
        assertThat(result.path("finalScore").decimalValue()).isEqualByComparingTo("20");
        assertThat(result.path("answers").toString()).contains("Teacher text").doesNotContain("PRIVATE-EXPECTED");
        h.assertNoSecrets(h.get(h.pupil,q).body());
    }
    @Test void expiredAndRevokedDocenteGrantsCutReviewWithoutChangingAuthorship() throws Exception {
        long member=count("select membership_id from teaching_assignments where id=?",responsible);
        long grant=count("select id from membership_role_grants where membership_id=? and role='DOCENTE' and status='ACTIVE'",member);
        r.institutions.revokeRole(grant,Instant.now());forbidden(send("GET",teacher,q,"",null));forbidden(post(teacher,"/close",body(UUID.randomUUID())));
        r.institutions.grantRole(member,DOCENTE,Instant.now(),Instant.now().plusSeconds(1));
        Thread.sleep(1100);forbidden(send("GET",teacher,q,"",null));assertThat(receipts()).isZero();
    }
    @Test void expiredTeachingAssignmentCannotReviewDespiteActiveDocenteGrant() throws Exception {
        r.academic.transitionAssignment(responsible,TeachingAssignment.Status.ENDED,Instant.now());
        long member=count("select membership_id from teaching_assignments where id=?",responsible);
        Instant at=Instant.now();var next=r.academic.planAssignment(r.institution,r.classId,member,at,at.plusSeconds(1),at);
        r.academic.transitionAssignment(next.id(),TeachingAssignment.Status.ACTIVE,at);
        Thread.sleep(1100);forbidden(send("GET",teacher,q,"",null));forbidden(post(teacher,"/close",body(UUID.randomUUID())));
    }
    @Test void simultaneousAdjustmentRetriesCreateOneAdjustmentAndOneSuccess() throws Exception {
        UUID op=UUID.randomUUID();String body=body(op,"amount",1,"reason","Concurrent");var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->post(teacher,"/adjustments",body));var b=pool.submit(()->post(teacher,"/adjustments",body));
            var first=a.get(60,TimeUnit.SECONDS);var second=b.get(60,TimeUnit.SECONDS);
            assertThat(first.statusCode()).as(first.body()).isIn(200,503);assertThat(second.statusCode()).as(second.body()).isIn(200,503);
            assertThat(List.of(first.statusCode(),second.statusCode())).contains(200);
            var retry=ok(post(teacher,"/adjustments",body));assertThat(retry).isEqualTo(ok(first.statusCode()==200?first:second));
            assertThat(count("select count(*) from evaluation_attempt_adjustments where attempt_id=?",q.attemptId())).isEqualTo(1);
            assertThat(count("select count(*) from system_logs where correlation_id=?",op)).isEqualTo(1);assertThat(receipts()).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    void raceMutation(String mutation,int nextStatus) throws Exception {
        var service=f.app.getBean(InstitutionalAttemptReviewService.class);JdbcTemplate spy=spy(sql());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{Object value=call.callRealMethod();locked.countDown();assertThat(release.await(12,TimeUnit.SECONDS)).isTrue();return value;})
            .when(spy).queryForObject(contains(".begin_save("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);var pool=Executors.newFixedThreadPool(2);UUID id=UUID.randomUUID();String body=body(id,"score",1);
        try(Connection writer=h.connection()) {
            int pid;try(var st=writer.createStatement();var rows=st.executeQuery("select pg_backend_pid()")){rows.next();pid=rows.getInt(1);}
            var grading=pool.submit(()->post(teacher,"/answers/"+answer,body));assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
            var changing=pool.submit(()->{try(var st=writer.createStatement()){st.execute(mutation);}return null;});h.waitForBlocked(pid);
            release.countDown();ok(grading.get(60,TimeUnit.SECONDS));changing.get(30,TimeUnit.SECONDS);
            assertThat(post(teacher,"/answers/"+answer,body).statusCode()).isEqualTo(nextStatus);assertThat(receipts()).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();ReflectionTestUtils.setField(service,"jdbc",sql());}
    }
    @Test void revocationWaitsForGradingAndNextReplayIsUnauthorized() throws Exception {
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(teacher).getPrincipal();
        raceMutation("update account_sessions set revoked_at=clock_timestamp() where id='"+principal.sessionId()+"'",401);
    }
    @Test void directContextWriterWaitsForGradingAndInvalidatesTheSuccessor() throws Exception {
        raceMutation("update evaluations set title='Outside concurrent protocol' where id="+q.evaluationId(),403);
    }
    @Test void twoAssignedTeachersSerializeGradeClosureAndRejectTheLosingEdit() throws Exception {
        long member=r.authorMember;var delegates=f.app.getBean(DelegationMaintenanceService.class);
        Instant at=Instant.now();var co=delegates.planCoTeacher(r.institution,r.classId,member,at,r.until,at);
        delegates.transitionCoTeacher(co.id(),TeachingAssignment.Status.ACTIVE,Instant.now());grade();
        var service=f.app.getBean(InstitutionalAttemptReviewService.class);JdbcTemplate spy=spy(sql());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{Object value=call.callRealMethod();locked.countDown();assertThat(release.await(12,TimeUnit.SECONDS)).isTrue();return value;})
            .when(spy).queryForObject(contains(".begin_save("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);var pool=Executors.newFixedThreadPool(2);
        try {
            String editing=body(UUID.randomUUID(),"score",0);
            var close=pool.submit(()->post(teacher,"/close",body(UUID.randomUUID())));assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
            var edit=pool.submit(()->post(h.teacher,"/answers/"+answer,editing));
            waitForInstitutionalWriter();release.countDown();ok(close.get(60,TimeUnit.SECONDS));
            var losing=edit.get(60,TimeUnit.SECONDS);assertThat(losing.statusCode()).as(losing.body()).isIn(403,503);
            if(losing.statusCode()==503)forbidden(post(h.teacher,"/answers/"+answer,editing));else forbidden(losing);
            assertThat(read().path("openAnswers").get(0).path("pointsAwarded").asInt()).isEqualTo(1);
        }finally{release.countDown();pool.shutdownNow();ReflectionTestUtils.setField(service,"jdbc",sql());}
    }
    void waitForInstitutionalWriter() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);boolean blocked=false;
        while(System.nanoTime()<until){blocked=Boolean.TRUE.equals(sql().queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query='lock table evaluations in exclusive mode')",Boolean.class));if(blocked)break;Thread.sleep(20);}
        assertThat(blocked).isTrue();
    }
    @Test void approvedF08CorrectionWaitsForGradingAndCannotTransferItsResult() throws Exception {
        long proposal=e.c.service.propose(e.c.root("evaluations",q.evaluationId()),e.c.mapping("evaluations",q.evaluationId(),r.foreign,r.foreignClass),"FIXTURE-TEACHER-CORRECTION",UUID.randomUUID());
        e.c.approve(proposal,"PLATFORM",e.c.platform,e.c.platformGrant);e.c.approve(proposal,"INSTITUTION",e.c.school,e.c.schoolGrant);
        var service=f.app.getBean(InstitutionalAttemptReviewService.class);JdbcTemplate spy=spy(sql());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{Object value=call.callRealMethod();locked.countDown();assertThat(release.await(12,TimeUnit.SECONDS)).isTrue();return value;})
            .when(spy).queryForObject(contains(".begin_save("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);var pool=Executors.newFixedThreadPool(2);
        try {
            var grading=pool.submit(()->post(teacher,"/answers/"+answer,body(UUID.randomUUID(),"score",1)));assertThat(locked.await(20,TimeUnit.SECONDS)).isTrue();
            var correction=pool.submit(()->e.c.service.apply(proposal,UUID.randomUUID()));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);boolean blocked=false;
            while(System.nanoTime()<until){blocked=Boolean.TRUE.equals(sql().queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query ilike '%t11p05%')",Boolean.class));if(blocked)break;Thread.sleep(20);}
            assertThat(blocked).isTrue();release.countDown();ok(grading.get(60,TimeUnit.SECONDS));assertThat(correction.get(30,TimeUnit.SECONDS)).isEqualTo("STALE");
            assertThat(count("select count(*) from t11b10_versions where proposal_id=?",proposal)).isZero();read();h.ok(h.pupil,q);
        }finally{release.countDown();pool.shutdownNow();ReflectionTestUtils.setField(service,"jdbc",sql());}
    }
    @Test void additiveCertificationAndPhysicalRecoveryKeepTeacherAndStudentContextAndReceipts() throws Exception {
        grade();command("/adjustments","amount",-1,"reason","Durable adjustment");
        UUID op=UUID.randomUUID();JsonNode terminal=ok(post(teacher,"/close",body(op)));
        UUID start=UUID.fromString(sql().queryForObject("select operation_id::text from "+d.ctl+".saves where request->>'operation'='START'",String.class));
        JsonNode first=f.json.readTree(sql().queryForObject("select result::text from "+d.ctl+".saves where operation_id=?",String.class,start));
        sql().execute(r.script("t12e-v1"));
        String expansion=r.script("t12e-v1");
        var tx=new org.springframework.transaction.support.TransactionTemplate(f.app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(s->{sql().execute("alter table "+ctl+".saves disable trigger immutable");
            assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        java.nio.file.Path dir=java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/t12e-recovery",f.schema)).toAbsolutePath();
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));
        sql().update("update authentication_limits set expires_at=greatest(expires_at,floor(extract(epoch from clock_timestamp())*1000)::bigint+3600000)");
        Map<String,String> before=new TreeMap<>();
        for(String schema:List.of(f.schema,"t06_"+f.schema.substring(4),"t12c_"+f.schema.substring(4),d.ctl,ctl))for(String table:sql().queryForList("select tablename from pg_tables where schemaname=? order by tablename",String.class,schema))
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
                statement.execute("set search_path to "+f.schema+",public");statement.execute(r.script("t12e-v1"));
                Map<String,String> restored=new TreeMap<>();
                for(String table:before.keySet())try(var rows=statement.executeQuery(r.signature(table))){rows.next();restored.put(table,rows.getString(1));}
                assertThat(restored).isEqualTo(before);
                java.nio.file.Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("before",before,"restored",restored,"copyPort",copyPort,"method","physical backup after institutional teacher grade, adjustment and closure; not PITR")));
                copyApp=f.startApp("--spring.datasource.url="+copyUrl+"?currentSchema="+f.schema,"--spring.jpa.hibernate.ddl-auto=none",
                    "--app.evaluations.institutional.enabled=true","--app.evaluations.institutional.review-enabled=true","--app.evaluations.institutional.attempt-lifecycle-enabled=true","--app.evaluations.institutional.answer-save-enabled=true","--app.evaluations.institutional.fixture-schema="+f.schema);
                f.origin=f.origin(copyApp);
                assertThat(d.begin(start)).isEqualTo(first);
                assertThat(ok(post(teacher,"/close",body(op)))).isEqualTo(terminal);
                assertThat(read().path("gradeClosed").asBoolean()).isTrue();
                assertThat(h.ok(h.pupil,q).path("answers").toString()).contains("Student text");
                try(var rows=statement.executeQuery("select count(*) from "+ctl+".saves")){rows.next();assertThat(rows.getInt(1)).isEqualTo(3);}
                try(var rows=statement.executeQuery("select count(*) from evaluation_attempts where id="+q.attemptId()+" and status='GRADED' and grade_closed")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
            }
        }finally{
            f.origin=sourceOrigin;if(copyApp!=null)copyApp.close();
            if(started)r.nativeTool("pg_ctl",List.of(),List.of("-D",backup.toString(),"-m","fast","-w","stop"),dir);
        }
    }

}

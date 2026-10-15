package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.*;
import static org.assertj.core.api.Assertions.*;

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
    JsonNode grade() throws Exception{return command("/answers/"+answer,"score",1,"feedback","Teacher text");}
    Map<String,String> state(){return e.c.b.b8.previous.previous.signatures("evaluation_attempts","evaluation_answers","evaluation_attempt_adjustments","evaluation_attempt_events","system_logs","t11b9_counters",ctl+".saves");}
    long receipts(){return count("select count(*) from "+ctl+".saves");}
    void forbidden(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(403);h.assertNoSecrets(response.body());}

    @Test void completeJourneyKeepsStudentOwnerHistoricalAuthorAndActualTeacher() throws Exception {
        var frozen=e.c.b.b8.previous.previous.signatures("t11b10_approvals","t11b10_versions","t11b3_account_attempt_budgets","t11b3_attempt_consumptions","t11b8_budgets");
        var before=state();var review=read();assertThat(state()).isEqualTo(before);
        assertThat(review.path("recipientAccountId").asLong()).isEqualTo(f.student.getId());
        assertThat(review.path("openAnswers").get(0).path("expectedAnswer").asText()).isEqualTo("PRIVATE-EXPECTED-T12B-1937");
        forbidden(post(teacher,"/complete",body(UUID.randomUUID())));
        grade();command("/complete");assertThat(read().path("gradeClosed").asBoolean()).isFalse();
        UUID add=UUID.randomUUID();String addBody=body(add,"amount",-1.235,"reason","Private reason");
        var adjustment=ok(post(teacher,"/adjustments",addBody));assertThat(ok(post(teacher,"/adjustments",addBody))).isEqualTo(adjustment);
        assertThat(read().path("finalScore").decimalValue()).isEqualByComparingTo("18.76");
        command("/adjustments/"+adjustment.path("childId").asLong()+"/void");
        command("/feedback","overallFeedback","General teacher text");
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
        f.app.getBean(TeacherProfileRepository.class).saveAndFlush(TeacherProfile.builder().user(e.c.school).names("Fixture").lastNames("Co teacher").build());
        var co=delegation.planCoTeacher(r.institution,r.classId,member,r.from.plusSeconds(30),r.until,r.from.plusSeconds(21));
        delegation.transitionCoTeacher(co.id(),TeachingAssignment.Status.ACTIVE,r.from.plusSeconds(30));
        ok(send("GET",h.admin,q,"",null));ok(post(h.admin,"/answers/"+answer,body(UUID.randomUUID(),"score",1)));
        assertThat(count("select t.user_id from evaluation_answers a join teacher_profiles t on t.id=a.reviewed_by where a.id=?",answer)).isEqualTo(e.c.school.getId());
        delegation.transitionCoTeacher(co.id(),TeachingAssignment.Status.ENDED,Instant.now());forbidden(send("GET",h.admin,q,"",null));
        r.institutions.grantRole(r.member,DOCENTE,r.from.plusSeconds(20),null);
        var self=delegation.planCoTeacher(r.institution,r.classId,r.member,r.from.plusSeconds(30),r.until,r.from.plusSeconds(21));
        delegation.transitionCoTeacher(self.id(),TeachingAssignment.Status.ACTIVE,r.from.plusSeconds(30));
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
        long foreignAnswer=count("insert into evaluation_answers(attempt_id,question_id,answer_text,reviewed) values(?,?,'Other',false) returning id",e.legacyAttempt,s.foreignQuestion);
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
        e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,UUID.randomUUID());var before=state();
        assertThat(post(teacher,"/adjustments",body(UUID.randomUUID(),"amount",1,"reason","Quota fail")).statusCode()).isEqualTo(503);
        assertThat(state()).isEqualTo(before);read();
    }
}

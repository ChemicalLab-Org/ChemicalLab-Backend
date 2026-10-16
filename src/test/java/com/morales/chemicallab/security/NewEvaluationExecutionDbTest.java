package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Every request uses real HTTP/T02, PostgreSQL and certified predecessor fixtures. */
class NewEvaluationExecutionDbTest {
    InstitutionalEvaluationReadDbTest h;
    EvaluationDeliveryAccessDbTest e;
    WhiteboardSecurityTransportDbTest f;
    ResourceReconciliationDbTest r;
    AcademicRevisionMaintenanceService revisions;
    Request q;
    String ctl;
    long resource;
    JdbcTemplate sql(){return f.sql();}
    long count(String query,Object...args){return sql().queryForObject(query,Long.class,args);}
    UUID op(){return UUID.randomUUID();}
    @BeforeEach void start(TestInfo info) throws Exception {
        h=new InstitutionalEvaluationReadDbTest();h.start(info);e=h.e;f=h.f;r=h.r;revisions=e.revisions;
        sql().execute(r.script("t12c-v1"));sql().execute(r.script("t12d-v1"));sql().execute(r.script("t12e-v1"));sql().execute(r.script("t12f-v1"));
        ctl="t12f_"+f.schema.substring(4);h.activation.put("app.evaluations.institutional.new-execution-enabled",true);
        q=prepare(r.institution,r.classId,r.episode,3,"ALL_AT_ONCE",false,Instant.now().plusSeconds(900));resolve(q,null);
    }
    @AfterEach void stop() throws Exception {
        SecurityContextHolder.clearContext();
        if(f!=null && f.app!=null && f.app.isActive())for(String prefix:List.of("t12f_","t12e_","t12d_","t12c_"))sql().execute("drop schema if exists "+prefix+f.schema.substring(4)+" cascade");
        if(h!=null)h.stop();
    }
    String payload(int maximum,String mode,boolean random,int offset) {
        return """
         {"resource":{"title":"Published chemistry %d","max_attempts":%d,"time_limit_minutes":30,
          "allow_chemical_calculator":false,"allow_periodic_table":false,"track_tab_exit":false,
          "question_display_mode":"%s","randomize_questions":%s},
          "questions":[{"id":%d,"question_text":"Explain %d","question_type":"OPEN_TEXT","points":2,"order_index":0,"required":true,"expected_answer":"PRIVATE-F-CRITERION"},
            {"id":%d,"question_text":"Select %d","question_type":"MULTIPLE_CHOICE","points":3,"order_index":1,"required":true,"explanation":"PRIVATE-F-EXPLANATION"}],
          "options":[{"id":%d,"question_id":%d,"option_text":"Correct %d","order_index":0,"correct":true},
           {"id":%d,"question_id":%d,"option_text":"Incorrect","order_index":1,"correct":false}]}
         """.formatted(offset,maximum,mode,random,17+offset,offset,18+offset,offset,31+offset,18+offset,offset,32+offset,18+offset);
    }
    Request prepare(long institution,long clazz,long episode,int maximum,String mode,boolean random,Instant due) {
        long member=count("select id from institution_memberships where institution_id=? and account_id=?",institution,f.teacher.getId());
        long res=revisions.createNew(AcademicRevisionMaintenanceService.Kind.EVALUATION,institution,f.teacher.getId(),member,payload(maximum,mode,random,0),op());resource=res;
        long rev=count("select id from t11b8_revisions where resource_id=?",res);revisions.publish(rev,1,op());
        return deliver(res,rev,institution,clazz,episode,Instant.now().minusSeconds(60),due);
    }
    Request deliver(long res,long rev,long institution,long clazz,long episode,Instant starts,Instant due) {
        long d=revisions.assign(rev,institution,clazz,starts,due,op());revisions.recipient(d,f.student.getId(),episode,0,true,op());
        return new Request(Contract.NEW,Action.READ_DELIVERY,institution,clazz,count("select evaluation_id from t11b8_resources where id=?",res),d,rev,f.student.getId(),null);
    }
    void resolve(Request x,String uncertainty) throws Exception {
        JsonNode mapping=f.json.readTree(e.c.mapping("evaluations",x.evaluationId(),x.institutionId(),x.classId()));
        for(JsonNode item:mapping.get("resolutions")) {
            ObjectNode row=(ObjectNode)item;String source=row.path("source").asText();long pk=row.path("pk").path("id").asLong();
            if(source.equals("t11b8_deliveries"))row.put("classId",count("select class_id from t11b8_deliveries where id=?",pk));
            if(source.equals("t11b8_recipients")) {
                var rec=sql().queryForMap("select r.enrollment_id,d.class_id from t11b8_recipients r join t11b8_deliveries d on d.id=r.delivery_id where r.id=?",pk);
                row.put("classId",((Number)rec.get("class_id")).longValue());row.put("enrollmentId",((Number)rec.get("enrollment_id")).longValue());row.put("sourceTimezone","America/Lima");
            }
            // Roots/revisions span classes; only deliveries/recipients have a mandatory class.
            if(!Set.of("t11b8_deliveries","t11b8_recipients").contains(source))row.remove("classId");
            if(uncertainty!=null){row.remove(List.of("institutionId","classId","accountId","enrollmentId","sourceTimezone"));row.put("scope",uncertainty);}
        }
        long p=e.c.service.propose(e.c.root("evaluations",x.evaluationId()),mapping.toString(),"FIXTURE-NEW-EXECUTION",op());
        e.c.approve(p,"PLATFORM",e.c.platform,e.c.platformGrant);e.c.approve(p,"INSTITUTION",e.c.school,x.institutionId()==r.foreign?e.c.schoolGrant:e.c.foreignSchoolGrant);
        assertThat(e.c.service.apply(p,op())).isEqualTo("APPLIED");
    }
    String base(Request x){return h.base(x).replace("evaluation-reads/NEW","evaluation-execution/NEW/v1");}
    String body(UUID id,Object...fields) throws Exception {Map<String,Object>b=new LinkedHashMap<>();b.put("operationId",id);for(int i=0;i<fields.length;i+=2)b.put((String)fields[i],fields[i+1]);return f.json.writeValueAsString(b);}
    Map<String,Object> answer(Request x,long question,Long option,String text){Map<String,Object>a=new LinkedHashMap<>();a.put("revisionId",x.revisionId());a.put("questionId",question);a.put("selectedOptionId",option);a.put("answerText",text);return a;}
    HttpResponse<String> send(String method,String token,Request x,String suffix,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(f.origin+base(x)+suffix)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);
        return f.http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(HttpResponse<String> response) throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");return f.json.readTree(response.body());}
    void denied(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(403);assertThat(response.body()).doesNotContain("PRIVATE-F-","finalScore","expectedAnswer","teacherFeedback","correctOptionText");}
    JsonNode begin(Request x,UUID id) throws Exception{return ok(send("POST",h.pupil,x,"/own-attempts",body(id)));}
    long begun() throws Exception{return begin(q,op()).path("attemptId").asLong();}
    JsonNode save(Request x,long attempt,UUID id,long question,Long option,String text) throws Exception{return ok(send("POST",h.pupil,x,"/own-attempts/"+attempt+"/answers",body(id,"answer",answer(x,question,option,text))));}
    JsonNode submit(Request x,long attempt,UUID id,List<Map<String,Object>> answers) throws Exception{return ok(send("POST",h.pupil,x,"/own-attempts/"+attempt+"/submit",body(id,"answers",answers)));}
    JsonNode own(Request x,long attempt) throws Exception{return ok(send("GET",h.pupil,x,"/own-attempts/"+attempt,null));}
    String review(long attempt){return "/attempts/"+attempt+"/review";}
    JsonNode teacher(long attempt,String suffix,Object...fields) throws Exception{return ok(send("POST",h.teacher,q,review(attempt)+suffix,body(op(),fields)));}
    Map<String,String> state(){var result=new TreeMap<>(e.c.b.b8.previous.previous.signatures(ctl+".attempts",ctl+".operations","evaluation_attempts","evaluation_answers","t11b3_account_attempt_budgets","t11b9_counters"));result.put("success",sql().queryForObject("select md5(coalesce(jsonb_agg(to_jsonb(x) order by id)::text,'')) from system_logs x where outcome='SUCCESS'",String.class));return result;}

    @Test void completeStudentTeacherJourneyKeepsExactPublicationAndPureRecovery() throws Exception {
        var frozen=e.c.b.b8.previous.previous.signatures("t11b8_budgets","t11b8_fixture_attempts","t11b3_account_attempt_budgets","t11b10_versions","t11b10_approvals");
        assertThat(ok(send("GET",h.pupil,q,"",null)).toString()).doesNotContain("PRIVATE-F-");
        UUID start=op();var receipt=begin(q,start);long attempt=receipt.path("attemptId").asLong();assertThat(begin(q,start)).isEqualTo(receipt);
        var before=state();var recovered=own(q,attempt);assertThat(own(q,attempt)).isEqualTo(recovered);assertThat(state()).isEqualTo(before);
        UUID save=op();var saved=save(q,attempt,save,17,null,"Student original");assertThat(save(q,attempt,save,17,null,"Student original")).isEqualTo(saved);
        UUID close=op();var terminal=submit(q,attempt,close,List.of(answer(q,18,31L,null)));assertThat(terminal.path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(submit(q,attempt,close,List.of(answer(q,18,31L,null)))).isEqualTo(terminal);
        assertThat(own(q,attempt).toString()).doesNotContain("score","pointsAwarded","PRIVATE-F-","teacherFeedback");
        denied(send("GET",h.pupil,q,"/own-attempts/"+attempt+"/correction",null));
        var review=ok(send("GET",h.teacher,q,review(attempt),null));assertThat(review.path("openAnswers").get(0).path("expectedAnswer").asText()).isEqualTo("PRIVATE-F-CRITERION");
        denied(send("POST",h.teacher,q,review(attempt)+"/close",body(op())));
        teacher(attempt,"/answers/17","score",2,"feedback","Teacher feedback");teacher(attempt,"/complete");
        teacher(attempt,"/adjustments","amount",-1.235,"reason","Adjustment");teacher(attempt,"/feedback","overallFeedback","General");
        UUID closed=op();var result=ok(send("POST",h.teacher,q,review(attempt)+"/close",body(closed)));
        assertThat(ok(send("POST",h.teacher,q,review(attempt)+"/close",body(closed)))).isEqualTo(result);
        assertThat(ok(send("GET",h.teacher,q,review(attempt),null)).path("finalScore").decimalValue()).isEqualByComparingTo("18.76");
        denied(send("GET",h.pupil,q,"/own-attempts/"+attempt+"/correction",null));
        denied(send("POST",h.teacher,q,review(attempt)+"/answers/17",body(op(),"score",0)));
        assertThat(e.c.b.b8.previous.previous.signatures(frozen.keySet().toArray(String[]::new))).isEqualTo(frozen);
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isZero();
        assertThat(count("select count(*) from "+ctl+".scan() where source_table='t12f_attempts' and classification='RESOLVED'")).isEqualTo(1);
        JsonNode measured=f.json.readTree(sql().queryForObject("select "+ctl+".measure()::text",String.class));
        assertThat(measured.findValuesAsText("source_table")).contains("t12f_attempts");
        assertThat(count("select count(*) from system_logs where correlation_id=? and outcome='SUCCESS'",closed)).isEqualTo(1);
        assertThat(save(q,attempt,save,17,null,"Student original")).isEqualTo(saved); // replay after terminal/grade close
    }

    long resource(Request x){return count("select resource_id from t11b8_deliveries where id=?",x.deliveryId());}
    Request successor(Request x,long clazz,long episode,int offset,String mode,boolean random) {
        var draft=revisions.nextDraft(x.revisionId(),op());draft=revisions.edit(draft.id(),1,payload(3,mode,random,offset),op());
        var published=revisions.publish(draft.id(),draft.version(),op());
        return deliver(resource(x),published.id(),x.institutionId(),clazz,episode,Instant.now().minusSeconds(30),Instant.now().plusSeconds(900));
    }
    @Test void twoPublicationsAndDraftChangesPreserveContentAndShareNumberingAcrossClasses() throws Exception {
        Request next=successor(q,r.otherClass,r.otherEpisode,100,"ALL_AT_ONCE",true);resolve(q,null);
        UUID firstKey=op();long first=begin(q,firstKey).path("attemptId").asLong();
        denied(send("POST",h.pupil,next,"/own-attempts",body(op())));
        denied(send("GET",h.pupil,next,"/own-attempts/"+first,null));
        denied(send("POST",h.pupil,q,"/own-attempts/"+first+"/answers",body(op(),"answer",answer(next,117,null,"Wrong publication"))));
        denied(send("POST",h.pupil,q,"/own-attempts/"+first+"/answers",body(op(),"answer",answer(q,18,131L,null))));
        save(q,first,op(),17,null,"Revision one");submit(q,first,op(),List.of(answer(q,18,31L,null)));
        long second=begin(next,op()).path("attemptId").asLong();assertThat(own(next,second).path("attemptNumber").asInt()).isEqualTo(2);
        var order=own(next,second).path("questionOrder");assertThat(own(next,second).path("questionOrder")).isEqualTo(order);
        var draft=revisions.nextDraft(next.revisionId(),op());revisions.edit(draft.id(),1,payload(3,"ONE_BY_ONE",false,200),op());resolve(q,null);
        assertThat(ok(send("GET",h.pupil,q,"",null)).toString()).contains("Explain 0").doesNotContain("Explain 100","Explain 200");
        assertThat(ok(send("GET",h.pupil,next,"",null)).toString()).contains("Explain 100").doesNotContain("Explain 200");
        assertThat(own(q,first).path("answers").toString()).contains("Revision one");
        save(next,second,op(),117,null,"Revision two");submit(next,second,op(),List.of(answer(next,118,131L,null)));
        assertThat(begin(q,op()).path("attemptNumber").asInt()).isEqualTo(3);
        assertThat(count("select count(*) from "+ctl+".attempts where resource_id=? and account_id=?",resource(q),f.student.getId())).isEqualTo(3);
        assertThat(count("select count(*) from "+ctl+".attempts where resource_id=? and status='IN_PROGRESS'",resource(q))).isEqualTo(1);
    }
    @Test void b8SimulationExecutionAndLegacyHaveDistinctNamespacesEvenForEqualIds() throws Exception {
        long attempt=begun();
        assertThat(attempt).isEqualTo(e.aAttempt); // independent identity sequences deliberately collide
        assertThat(h.ok(h.pupil,e.request(false,Action.READ_OWN_ATTEMPT)).path("status").asText()).isEqualTo("FINAL");
        assertThat(own(q,attempt).path("status").asText()).isEqualTo("IN_PROGRESS");
        denied(send("GET",h.pupil,e.request(false,Action.READ_DELIVERY),"/own-attempts/"+e.aAttempt,null));
        denied(send("POST",h.pupil,e.request(false,Action.READ_DELIVERY),"/own-attempts",body(op())));
        assertThat(send("POST",h.pupil,q,"/own-attempts",body(op())).statusCode()).isEqualTo(403);
        assertThat(f.request("POST",h.base(q).replace("evaluation-reads","evaluation-writes")+"/own-attempts",h.pupil,body(op())).statusCode()).isEqualTo(403);
        denied(send("GET",h.pupil,e.legacy(Action.READ_DELIVERY),"/own-attempts/"+e.legacyAttempt,null));
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isZero();
        assertThat(count("select count(*) from evaluation_assignments where evaluation_id=?",q.evaluationId())).isZero();
        // A later simulator is never silently counted as an execution opportunity.
        long rec=count("select id from t11b8_recipients where delivery_id=?",q.deliveryId());revisions.reserveFixtureAttempt(rec,op());resolve(q,null);
        denied(send("GET",h.pupil,q,"/own-attempts/"+attempt,null));
    }
    @Test void institutionsAndSelectorsAreBilateralAndCannotChangeThePrincipal() throws Exception {
        Request a=q,b=prepare(r.foreign,r.foreignClass,e.c.foreignEpisode,3,"ALL_AT_ONCE",false,Instant.now().plusSeconds(900));resolve(b,null);
        long first=begin(a,op()).path("attemptId").asLong(),second=begin(b,op()).path("attemptId").asLong();
        assertThat(own(a,first).path("attemptNumber").asInt()).isEqualTo(1);assertThat(own(b,second).path("attemptNumber").asInt()).isEqualTo(1);
        for(Request bad:List.of(e.changed(a,r.foreign,a.classId(),a.evaluationId(),a.deliveryId(),a.revisionId(),f.student.getId(),null),
          e.changed(a,a.institutionId(),r.foreignClass,a.evaluationId(),a.deliveryId(),a.revisionId(),f.student.getId(),null),
          e.changed(a,a.institutionId(),a.classId(),b.evaluationId(),a.deliveryId(),a.revisionId(),f.student.getId(),null),
          e.changed(a,a.institutionId(),a.classId(),a.evaluationId(),b.deliveryId(),a.revisionId(),f.student.getId(),null),
          e.changed(a,a.institutionId(),a.classId(),a.evaluationId(),a.deliveryId(),b.revisionId(),f.student.getId(),null)))
            denied(send("GET",h.pupil,bad,"/own-attempts/"+first,null));
        denied(send("GET",h.peer,a,"/own-attempts/"+first+"?recipientAccountId="+f.student.getId()+"&role=DOCENTE",null));
        denied(send("GET",h.teacher,a,"/own-attempts/"+first,null));
        var metadata=ok(send("GET",h.admin,a,"?recipientAccountId="+f.student.getId(),null));
        assertThat(metadata.path("projection").asText()).isEqualTo("SUPERVISION_METADATA");assertThat(metadata.toString()).doesNotContain("questions","PRIVATE-F-");
        r.institutions.transitionMembership(r.member,InstitutionMembership.Status.SUSPENDED,Instant.now());
        denied(send("GET",h.pupil,a,"/own-attempts/"+first,null));own(b,second);
    }
    @Test void oneByOnePreservesCursorAndIgnoresSubmissionPayload() throws Exception {
        q=prepare(r.institution,r.classId,r.episode,3,"ONE_BY_ONE",false,Instant.now().plusSeconds(900));resolve(q,null);long a=begun();
        denied(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(op(),"answer",answer(q,18,31L,null))));
        denied(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(op(),"answer",answer(q,17,null,"  "))));
        UUID key=op();var result=save(q,a,key,17,null,"Required");assertThat(save(q,a,key,17,null,"Required")).isEqualTo(result);
        denied(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(op(),"answer",answer(q,17,null,"Backwards"))));
        save(q,a,op(),18,31L,null);var before=own(q,a);submit(q,a,op(),List.of(answer(q,17,null,"Ignored"),answer(q,999,999L,null)));
        var after=own(q,a);assertThat(after.path("currentQuestionIndex").asInt()).isEqualTo(2);assertThat(after.path("questionOrder")).isEqualTo(before.path("questionOrder"));
        assertThat(after.path("answers").toString()).contains("Required").doesNotContain("Ignored");
    }
    @Test void expiredDeadlineAcceptsOnlySavedContentAndStartReplayOutlivesItsWindow() throws Exception {
        Instant due=Instant.now().plusSeconds(18);q=prepare(r.institution,r.classId,r.episode,3,"ALL_AT_ONCE",false,due);resolve(q,null);
        UUID start=op();var receipt=begin(q,start);long a=receipt.path("attemptId").asLong();UUID saving=op();var saved=save(q,a,saving,18,31L,null);
        Instant deadline=Instant.parse(own(q,a).path("deadline").asText());assertThat(deadline).isEqualTo(due.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        long millis=Duration.between(Instant.now(),deadline.plusMillis(50)).toMillis();if(millis>0)Thread.sleep(millis);
        var before=state();own(q,a);assertThat(state()).isEqualTo(before);
        denied(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(op(),"answer",answer(q,17,null,"Late"))));
        submit(q,a,op(),List.of(answer(q,17,null,"Late"),answer(q,18,32L,null)));
        var actual=own(q,a);assertThat(actual.path("answers").toString()).doesNotContain("Late");
        assertThat(ok(send("GET",h.teacher,q,review(a),null)).path("score").asInt()).isEqualTo(3);
        assertThat(begin(q,start)).isEqualTo(receipt);assertThat(save(q,a,saving,18,31L,null)).isEqualTo(saved);
        denied(send("POST",h.pupil,q,"/own-attempts",body(op())));
    }
    @Test void requiredOpenAndExactQuestionOptionAndRevisionValidationRollback() throws Exception {
        long a=begun();var before=state();
        denied(send("POST",h.pupil,q,"/own-attempts/"+a+"/submit",body(op())));
        for(var value:List.of(answer(q,999,null,"Unknown"),answer(q,18,999L,null),answer(q,17,31L,"Mixed"),answer(q,18,31L,"Mixed")))
            denied(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(op(),"answer",value)));
        var wrong=answer(q,17,null,"Revision");wrong.put("revisionId",e.aRevision);denied(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(op(),"answer",wrong)));
        assertThat(state()).isEqualTo(before);
        submit(q,a,op(),List.of(answer(q,17,null,"Required")));assertThat(own(q,a).path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
    }
    @Test void coTeachingRevocationAdminAndSelfGradingRequireCurrentFaculties() throws Exception {
        long a=begun();submit(q,a,op(),List.of(answer(q,17,null,"Original")));
        for(String token:List.of(h.pupil,h.peer,h.admin)) {denied(send("GET",token,q,review(a),null));denied(send("POST",token,q,review(a)+"/answers/17",body(op(),"score",1)));}
        var delegation=f.app.getBean(DelegationMaintenanceService.class);
        long member=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,f.otherTeacher.getId());
        Instant at=Instant.now();var co=delegation.planCoTeacher(r.institution,r.classId,member,at,r.until,at);delegation.transitionCoTeacher(co.id(),TeachingAssignment.Status.ACTIVE,Instant.now());
        String token=f.login(f.otherTeacher);UUID grade=op();String body=body(grade,"score",1);
        ok(send("GET",token,q,review(a),null));ok(send("POST",token,q,review(a)+"/answers/17",body));
        assertThat(ok(send("GET",token,q,review(a),null)).path("openAnswers").get(0).path("reviewedByAccountId").asLong()).isEqualTo(f.otherTeacher.getId());
        delegation.transitionCoTeacher(co.id(),TeachingAssignment.Status.ENDED,Instant.now());denied(send("POST",token,q,review(a)+"/answers/17",body));
        r.institutions.grantRole(r.member,DOCENTE,r.from.plusSeconds(20),null);at=Instant.now();var self=delegation.planCoTeacher(r.institution,r.classId,r.member,at,r.until,at);delegation.transitionCoTeacher(self.id(),TeachingAssignment.Status.ACTIVE,Instant.now());resolve(q,null);
        denied(send("GET",h.pupil,q,review(a),null));denied(send("POST",h.pupil,q,review(a)+"/close",body(op())));
    }
    @Test void globalExposureIncludesFutureEmptyUndatedDeliveriesAndAllExecutionGrades() throws Exception {
        long future=revisions.assign(q.revisionId(),r.institution,r.otherClass,Instant.now().plusSeconds(600),null,op());
        Request other=successor(q,r.classId,r.episode,100,"ALL_AT_ONCE",false);resolve(q,null);
        long a=begun();submit(q,a,op(),List.of(answer(q,17,null,"First")));teacher(a,"/answers/17","score",2);teacher(a,"/close");
        revisions.closeDelivery(q.deliveryId(),1,op());revisions.closeDelivery(other.deliveryId(),1,op());resolve(q,null);
        denied(send("GET",h.pupil,q,"/own-attempts/"+a+"/correction",null));
        revisions.closeDelivery(future,1,op());resolve(q,null);
        var correction=ok(send("GET",h.pupil,q,"/own-attempts/"+a+"/correction",null));assertThat(correction.toString()).contains("Correct 0");
        assertThat(correction.path("finalScore").decimalValue()).isEqualByComparingTo("8");
        assertThat(count("select count(*) from t11b8_fixture_attempts where resource_id=?",resource(q))).isZero();
    }
    @Test void idempotenceBindsContentActorSessionAndNeverDuplicatesAdjustmentsOrClosures() throws Exception {
        UUID start=op();long a=begin(q,start).path("attemptId").asLong();UUID key=op();save(q,a,key,17,null,"Saved");
        assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(key,"answer",answer(q,17,null,"Changed"))).statusCode()).isEqualTo(409);
        submit(q,a,op(),null);teacher(a,"/answers/17","score",2);
        UUID add=op();String addition=body(add,"amount",-1,"reason","Bound");var result=ok(send("POST",h.teacher,q,review(a)+"/adjustments",addition));
        assertThat(ok(send("POST",h.teacher,q,review(a)+"/adjustments",addition))).isEqualTo(result);assertThat(result.path("childId").asLong()).isEqualTo(1);
        teacher(a,"/adjustments/1/void");teacher(a,"/close");assertThat(ok(send("POST",h.teacher,q,review(a)+"/adjustments",addition))).isEqualTo(result);
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(h.pupil).getPrincipal();sql().update("update account_sessions set revoked_at=clock_timestamp() where id=?",principal.sessionId());
        assertThat(send("POST",h.pupil,q,"/own-attempts",body(start)).statusCode()).isEqualTo(401);
        assertThat(count("select count(*) from "+ctl+".operations where operation_id=?",add)).isEqualTo(1);
    }
    @Test void mandatoryAuditFailureRollsBackConsumptionAnswersGradesAndClosure() throws Exception {
        for(String action:List.of("START","SAVE","SUBMIT","GRADE","CLOSE")) {
            long a=action.equals("START")?0:begun();
            if(Set.of("GRADE","CLOSE").contains(action))submit(q,a,op(),List.of(answer(q,17,null,"Before failure")));
            if(action.equals("CLOSE"))teacher(a,"/answers/17","score",2);
            String suffix=switch(action){case "START"->"/own-attempts";case "SAVE"->"/own-attempts/"+a+"/answers";case "SUBMIT"->"/own-attempts/"+a+"/submit";case "GRADE"->review(a)+"/answers/17";default->review(a)+"/close";};
            String body=switch(action){case "SAVE"->body(op(),"answer",answer(q,17,null,"Atomic"));case "SUBMIT"->body(op(),"answers",List.of(answer(q,17,null,"Atomic")));case "GRADE"->body(op(),"score",1);default->body(op());};
            var before=state();
            sql().execute("create function fixture_new_audit_fail() returns trigger language plpgsql as $$ begin if NEW.action='"+action+"_NEW_EXECUTION' then raise exception 'fixture audit failure';end if;return NEW;end $$");
            sql().execute("create trigger fixture_new_audit_fail before insert on system_logs for each row execute function fixture_new_audit_fail()");
            try{assertThat(send("POST",Set.of("GRADE","CLOSE").contains(action)?h.teacher:h.pupil,q,suffix,body).statusCode()).isEqualTo(503);}
            finally{sql().execute("drop trigger fixture_new_audit_fail on system_logs;drop function fixture_new_audit_fail()");}
            assertThat(state()).as(action).isEqualTo(before);
            // Each iteration owns a genuinely independent academic resource, not refunded attempts.
            q=prepare(r.institution,r.classId,r.episode,3,"ALL_AT_ONCE",false,Instant.now().plusSeconds(900));resolve(q,null);
        }
    }
    @Test void institutionalQuotaFailureRollsBackEachGrowingAggregateAndItsReceipt() throws Exception {
        e.c.b.service.limit(q.institutionId(),"ORIGINAL",1000000,268435456,op());long a=begun();
        var before=state();e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,op());
        assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/submit",body(op(),"answers",List.of(answer(q,17,null,"Quota")))).statusCode()).isEqualTo(503);
        assertThat(state()).isEqualTo(before);e.c.b.service.limit(q.institutionId(),"ORIGINAL",1000000,268435456,op());
        submit(q,a,op(),List.of(answer(q,17,null,"Quota")));teacher(a,"/answers/17","score",2);before=state();
        e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,op());assertThat(send("POST",h.teacher,q,review(a)+"/close",body(op())).statusCode()).isEqualTo(503);assertThat(state()).isEqualTo(before);
    }
    @Test void finalSessionRevalidationRollsBackAnAlreadyFinishedOperation() throws Exception {
        long a=begun();var before=state();
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(h.pupil).getPrincipal();
        var service=f.app.getBean(NewEvaluationExecutionService.class);JdbcTemplate spy=spy(sql());var finished=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{Object result=call.callRealMethod();finished.set(true);long remaining=count("select greatest(0,ceil(extract(epoch from (expires_at-clock_timestamp()))*1000))::bigint from account_sessions where id=?",principal.sessionId());assertThat(remaining).isPositive();Thread.sleep(remaining+50);return result;})
          .when(spy).queryForObject(contains(".finish_operation("),eq(Object.class),any(Object[].class));
        sql().update("update account_sessions set expires_at=date_trunc('second',clock_timestamp())+interval '18 seconds' where id=?",principal.sessionId());
        var session=f.app.getBean(com.morales.chemicallab.repository.AccountSessionRepository.class).findById(principal.sessionId()).orElseThrow();
        h.pupil=f.app.getBean(JwtService.class).generateToken(f.student,session);ReflectionTestUtils.setField(service,"jdbc",spy);
        try{assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/submit",body(op(),"answers",List.of(answer(q,17,null,"Must rollback")))).statusCode()).isEqualTo(401);}
        finally{ReflectionTestUtils.setField(service,"jdbc",sql());}
        assertThat(finished).isTrue();assertThat(state()).isEqualTo(before);
    }
    @Test void latestF08AndOutOfProtocolChangesCannotBeLegitimizedByReplays() throws Exception {
        UUID key=op();long a=begin(q,key).path("attemptId").asLong();
        for(String scope:List.of("UNRESOLVED","CONFLICT")){resolve(q,scope);denied(send("GET",h.pupil,q,"/own-attempts/"+a,null));denied(send("POST",h.pupil,q,"/own-attempts",body(key)));resolve(q,null);own(q,a);}
        var tx=new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(status->{sql().execute("set local session_replication_role=replica");sql().update("update "+ctl+".attempts set answers='{\"17\":{\"text\":\"Outside protocol\"}}'::jsonb where id=?",a);});
        denied(send("GET",h.pupil,q,"/own-attempts/"+a,null));denied(send("POST",h.pupil,q,"/own-attempts",body(key)));
        resolve(q,null);denied(send("GET",h.pupil,q,"/own-attempts/"+a,null));
    }
    @Test void defaultGatesTransportAndInvalidPublishedTypesRejectWithoutMutation() throws Exception {
        h.activation.remove("app.evaluations.institutional.new-execution-enabled");assertThat(send("POST",h.pupil,q,"/own-attempts",body(op())).statusCode()).isEqualTo(404);
        h.activation.put("app.evaluations.institutional.new-execution-enabled",true);
        assertThat(send("POST",h.pupil,q,"/own-attempts","{\"operationId\":\""+op()+"\",\"padding\":\""+"X".repeat(1048576)+"\"}").statusCode()).isEqualTo(413);
        long a=begun();var before=state();var many=new ArrayList<Map<String,Object>>();for(int i=0;i<201;i++)many.add(answer(q,17,null,"A"));
        assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/submit",body(op(),"answers",many)).statusCode()).isEqualTo(400);
        assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers",body(op(),"answer",answer(q,17,null,"X".repeat(3001)))).statusCode()).isEqualTo(400);
        assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers","{\"operationId\":\""+op()+"\",\"operationId\":\""+op()+"\"}").statusCode()).isEqualTo(400);
        assertThat(state()).isEqualTo(before);
        // B8 accepts castable scalars in its preparation contract. F's executable contract is typed.
        String invalid=payload(3,"ALL_AT_ONCE",false,0).replace("\"randomize_questions\":false","\"randomize_questions\":\"false\"");
        long res=revisions.createNew(AcademicRevisionMaintenanceService.Kind.EVALUATION,r.institution,f.teacher.getId(),r.authorMember,invalid,op());
        long rev=count("select id from t11b8_revisions where resource_id=?",res);revisions.publish(rev,1,op());
        Request bad=deliver(res,rev,r.institution,r.classId,r.episode,Instant.now().minusSeconds(10),Instant.now().plusSeconds(900));resolve(bad,null);
        String frozen=sql().queryForObject(r.signature("t11b8_revisions"),String.class);denied(send("POST",h.pupil,bad,"/own-attempts",body(op())));
        assertThat(sql().queryForObject(r.signature("t11b8_revisions"),String.class)).isEqualTo(frozen);
    }
}

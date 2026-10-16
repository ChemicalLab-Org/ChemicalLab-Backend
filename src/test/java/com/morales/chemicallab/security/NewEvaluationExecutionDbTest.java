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
        System.out.println("T12-F case: "+info.getTestMethod().orElseThrow().getName());
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
        return sendAt(f.origin,method,token,x,suffix,body);
    }
    HttpResponse<String> sendAt(String origin,String method,String token,Request x,String suffix,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(origin+base(x)+suffix)).timeout(Duration.ofSeconds(60)).header("Content-Type","application/json");
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
        e.resolve(e.request(false,Action.READ_OWN_ATTEMPT));
        long attempt=begun();
        assertThat(attempt).isEqualTo(e.aAttempt); // independent identity sequences deliberately collide
        assertThat(h.ok(h.pupil,e.request(false,Action.READ_OWN_ATTEMPT)).path("status").asText()).isEqualTo("FINAL");
        assertThat(own(q,attempt).path("status").asText()).isEqualTo("IN_PROGRESS");
        denied(send("GET",h.pupil,e.request(false,Action.READ_DELIVERY),"/own-attempts/"+e.aAttempt,null));
        denied(send("POST",h.pupil,e.request(false,Action.READ_DELIVERY),"/own-attempts",body(op())));
        assertThat(send("POST",h.pupil,q,"/own-attempts",body(op())).statusCode()).isEqualTo(403);
        h.activation.put("app.evaluations.institutional.attempt-lifecycle-enabled",true);
        assertThat(f.request("POST",h.base(q).replace("evaluation-reads","evaluation-writes")+"/own-attempts",h.pupil,body(op())).statusCode()).isEqualTo(403);
        denied(send("GET",h.pupil,e.legacy(Action.READ_DELIVERY),"/own-attempts/"+e.legacyAttempt,null));
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isZero();
        assertThat(count("select count(*) from evaluation_assignments where evaluation_id=?",q.evaluationId())).isZero();
        // A later simulator is never silently counted as an execution opportunity.
        long rec=count("select id from t11b8_recipients where delivery_id=?",q.deliveryId());
        assertThatThrownBy(()->revisions.reserveFixtureAttempt(rec,op())).isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be simulated");
        own(q,attempt);
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
        Instant due=Instant.now().plusSeconds(40).truncatedTo(java.time.temporal.ChronoUnit.MICROS);q=prepare(r.institution,r.classId,r.episode,3,"ALL_AT_ONCE",false,due);resolve(q,null);
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
        long second=begin(other,op()).path("attemptId").asLong();submit(other,second,op(),List.of(answer(other,117,null,"Pending grade")));
        revisions.closeDelivery(q.deliveryId(),1,op());revisions.closeDelivery(other.deliveryId(),1,op());resolve(q,null);
        denied(send("GET",h.pupil,q,"/own-attempts/"+a+"/correction",null));
        revisions.closeDelivery(future,1,op());resolve(q,null);
        denied(send("GET",h.pupil,q,"/own-attempts/"+a+"/correction",null));
        Request original=q;q=other;teacher(second,"/answers/117","score",2);teacher(second,"/close");q=original;
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
        e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,op());var before=state();
        assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/submit",body(op(),"answers",List.of(answer(q,17,null,"Quota")))).statusCode()).isEqualTo(503);
        assertThat(state()).isEqualTo(before);e.c.b.service.limit(q.institutionId(),"ORIGINAL",1000000,268435456,op());
        submit(q,a,op(),List.of(answer(q,17,null,"Quota")));teacher(a,"/answers/17","score",2);
        e.c.b.service.limit(q.institutionId(),"ORIGINAL",1,1,op());before=state();assertThat(send("POST",h.teacher,q,review(a)+"/close",body(op())).statusCode()).isEqualTo(503);assertThat(state()).isEqualTo(before);
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
        for(String invalidAnswer:List.of("{\"revisionId\":"+q.revisionId()+",\"questionId\":17.5,\"answerText\":\"A\"}",
          "{\"revisionId\":"+q.revisionId()+",\"questionId\":\"17\",\"answerText\":\"A\"}",
          "{\"revisionId\":"+q.revisionId()+",\"questionId\":17,\"answerText\":42}"))
            assertThat(send("POST",h.pupil,q,"/own-attempts/"+a+"/answers","{\"operationId\":\""+op()+"\",\"answer\":"+invalidAnswer+"}").statusCode()).isEqualTo(400);
        assertThat(state()).isEqualTo(before);
        // B8 accepts castable scalars in its preparation contract. F's executable contract is typed.
        String invalid=payload(3,"ALL_AT_ONCE",false,0).replace("\"randomize_questions\":false","\"randomize_questions\":\"false\"");
        long res=revisions.createNew(AcademicRevisionMaintenanceService.Kind.EVALUATION,r.institution,f.teacher.getId(),r.authorMember,invalid,op());
        long rev=count("select id from t11b8_revisions where resource_id=?",res);revisions.publish(rev,1,op());
        Request bad=deliver(res,rev,r.institution,r.classId,r.episode,Instant.now().minusSeconds(10),Instant.now().plusSeconds(900));resolve(bad,null);
        String frozen=sql().queryForObject(r.signature("t11b8_revisions"),String.class);denied(send("POST",h.pupil,bad,"/own-attempts",body(op())));
        assertThat(sql().queryForObject(r.signature("t11b8_revisions"),String.class)).isEqualTo(frozen);
    }
    @Test void lastOpportunitySerializesTwoIndependentHttpProcessesAcrossDeliveries() throws Exception {
        Request other=successor(q,r.otherClass,r.otherEpisode,100,"ALL_AT_ONCE",false);resolve(q,null);
        for(int i=0;i<2;i++){long a=begun();submit(q,a,op(),List.of(answer(q,17,null,"Historical consumption")));}
        var processes=new ArrayList<Process>();var stops=new ArrayList<Path>();var origins=new ArrayList<String>();
        var dir=Files.createDirectories(Path.of("target/t12f-processes",f.schema)).toAbsolutePath();var pool=Executors.newFixedThreadPool(2);
        try {
            for(int i=0;i<2;i++) {
                var ready=dir.resolve(i+".ready");var stop=dir.resolve(i+".stop");stops.add(stop);
                var command=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx384m","-Dspring.devtools.restart.enabled=false","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),NewExecutionServer.class.getName(),f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,Base64.getEncoder().encodeToString(f.key),ready.toString(),stop.toString());
                var child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start();processes.add(child);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);while(!Files.exists(ready)&&child.isAlive()&&System.nanoTime()<until)Thread.sleep(50);
                assertThat(Files.exists(ready)).as(dir.toString()).isTrue();origins.add(Files.readString(ready));
            }
            UUID one=op(),two=op();String b1=body(one),b2=body(two);
            try(Connection barrier=h.connection()) {
                barrier.setAutoCommit(false);barrier.createStatement().execute("lock table evaluations in exclusive mode");
                var first=pool.submit(()->sendAt(origins.get(0),"POST",h.pupil,q,"/own-attempts",b1));
                var second=pool.submit(()->sendAt(origins.get(1),"POST",h.pupil,other,"/own-attempts",b2));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);long blocked=0;
                while(System.nanoTime()<until){blocked=count("select count(*) from pg_stat_activity where wait_event_type='Lock' and query='lock table evaluations in exclusive mode'");if(blocked>=2)break;Thread.sleep(20);}
                assertThat(blocked).isGreaterThanOrEqualTo(2);barrier.commit();var a=first.get(60,TimeUnit.SECONDS);var b=second.get(60,TimeUnit.SECONDS);
                assertThat(List.of(a.statusCode(),b.statusCode())).filteredOn(x->x==200).hasSize(1);
                assertThat(a.statusCode()).isIn(200,403,503);assertThat(b.statusCode()).isIn(200,403,503);
                assertThat(count("select count(*) from "+ctl+".attempts where resource_id=?",resource(q))).isEqualTo(3);
                assertThat(count("select count(*) from "+ctl+".attempts where resource_id=? and status='IN_PROGRESS'",resource(q))).isEqualTo(1);
                Request winner=a.statusCode()==200?q:other;JsonNode result=ok(a.statusCode()==200?a:b);long target=result.path("attemptId").asLong();
                assertThat(ok(send("POST",h.pupil,winner,"/own-attempts",a.statusCode()==200?b1:b2))).isEqualTo(result);
                submit(winner,target,op(),List.of(answer(winner,winner==q?17:117,null,"Last")));
                denied(send("POST",h.pupil,q,"/own-attempts",body(op())));denied(send("POST",h.pupil,other,"/own-attempts",body(op())));
                Files.writeString(dir.resolve("evidence.json"),f.json.writeValueAsString(Map.of("osPids",processes.stream().map(Process::pid).toList(),"blocked",blocked,"statuses",List.of(a.statusCode(),b.statusCode()),"attempts",3,"lastNumber",result.path("attemptNumber").asInt())));
            }
        } finally {pool.shutdownNow();for(var stop:stops)Files.writeString(stop,"stop");for(var child:processes)if(!child.waitFor(15,TimeUnit.SECONDS))child.destroyForcibly();}
    }
    @Test void certificationRepeatAndPhysicalRecoveryPreserveExecutionAndPredecessors() throws Exception {
        e.resolve(e.request(false,Action.READ_OWN_ATTEMPT));
        UUID start=op();var first=begin(q,start);long a=first.path("attemptId").asLong();UUID save=op();var saved=save(q,a,save,17,null,"Durable student text");
        submit(q,a,op(),List.of(answer(q,18,31L,null)));teacher(a,"/answers/17","score",2);teacher(a,"/adjustments","amount",-1,"reason","Durable adjustment");
        UUID close=op();var closed=ok(send("POST",h.teacher,q,review(a)+"/close",body(close)));String expansion=r.script("t12f-v1");sql().execute(expansion);
        var tx=new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(status->{sql().execute("alter table "+ctl+".attempts disable trigger academic_guard");assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("divergence");status.setRollbackOnly();});
        Path dir=Files.createDirectories(Path.of("target/t12f-recovery",f.schema)).toAbsolutePath();String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));
        sql().update("update authentication_limits set expires_at=greatest(expires_at,floor(extract(epoch from clock_timestamp())*1000)::bigint+3600000)");
        Map<String,String> before=new TreeMap<>();
        for(String schema:List.of(f.schema,"t06_"+f.schema.substring(4),"t12c_"+f.schema.substring(4),"t12d_"+f.schema.substring(4),"t12e_"+f.schema.substring(4),ctl))
            for(String table:sql().queryForList("select tablename from pg_tables where schemaname=? order by tablename",String.class,schema))before.put(schema+"."+table,sql().queryForObject(r.signature(schema+"."+table),String.class));
        var backup=dir.resolve("copy");r.nativeTool("pg_basebackup",args,List.of("-D",backup.toString(),"--wal-method=stream","--checkpoint=fast"),dir);r.nativeTool("pg_verifybackup",List.of(),List.of(backup.toString()),dir);
        int copyPort=TransitionWalRecoveryFixture.port();Files.writeString(backup.resolve("postgresql.auto.conf"),"listen_addresses='127.0.0.1'\nport="+copyPort+"\nmax_connections=20\nmax_locks_per_transaction=512\nshared_buffers='16MB'\n");
        boolean started=false;String origin=f.origin;org.springframework.context.ConfigurableApplicationContext copyApp=null;
        try {
            r.nativeTool("pg_ctl",List.of(),List.of("-D",backup.toString(),"-l",dir.resolve("copy-postgres.log").toString(),"-w","start"),dir);started=true;
            String copyUrl="jdbc:postgresql://127.0.0.1:"+copyPort+"/chemicallab_t01_test";
            try(Connection copy=DriverManager.getConnection(copyUrl,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));var statement=copy.createStatement()) {
                statement.execute("set search_path to "+f.schema+",public");statement.execute(expansion);Map<String,String> restored=new TreeMap<>();
                for(String table:before.keySet())try(var rows=statement.executeQuery(r.signature(table))){rows.next();restored.put(table,rows.getString(1));}assertThat(restored).isEqualTo(before);
                Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("before",before,"restored",restored,"copyPort",copyPort,"method","physical backup with streaming WAL; not historical PITR")));
                copyApp=f.startApp("--spring.datasource.url="+copyUrl+"?currentSchema="+f.schema,"--spring.jpa.hibernate.ddl-auto=none","--app.evaluations.institutional.enabled=true","--app.evaluations.institutional.new-execution-enabled=true","--app.evaluations.institutional.fixture-schema="+f.schema);f.origin=f.origin(copyApp);
                assertThat(begin(q,start)).isEqualTo(first);assertThat(save(q,a,save,17,null,"Durable student text")).isEqualTo(saved);
                assertThat(ok(send("POST",h.teacher,q,review(a)+"/close",body(close)))).isEqualTo(closed);
                assertThat(own(q,a).path("answers").toString()).contains("Durable student text");assertThat(ok(send("GET",h.teacher,q,review(a),null)).path("gradeClosed").asBoolean()).isTrue();
                // The predecessor simulation remains visible ONLY through its original namespace.
                h.ok(h.pupil,e.request(false,Action.READ_OWN_ATTEMPT));
            }
        } finally {f.origin=origin;if(copyApp!=null)copyApp.close();if(started)r.nativeTool("pg_ctl",List.of(),List.of("-D",backup.toString(),"-m","fast","-w","stop"),dir);}
    }
    @Test void originalCReceiptAndNewExecutionShareInstitutionalQuotaWithoutRewritingHistory(TestInfo info) throws Exception {
        var s=new InstitutionalAnswerSaveDbTest();s.start(info);String suffix=s.f.schema.substring(4);
        try {
            UUID original=op();var receipt=s.save(original,s.open,null,"Original C");String frozen=s.sql().queryForObject(s.r.signature(s.ctl+".saves"),String.class);
            for(String version:List.of("t12d-v1","t12e-v1","t12f-v1"))s.sql().execute(s.r.script(version));
            s.h.activation.put("app.evaluations.institutional.new-execution-enabled",true);
            assertThat(s.save(original,s.open,null,"Original C")).isEqualTo(receipt);
            var n=new NewEvaluationExecutionDbTest();n.h=s.h;n.e=s.e;n.f=s.f;n.r=s.r;n.revisions=s.e.revisions;n.ctl="t12f_"+suffix;
            n.q=n.prepare(n.r.institution,n.r.classId,n.r.episode,3,"ALL_AT_ONCE",false,Instant.now().plusSeconds(900));n.resolve(n.q,null);long a=n.begun();n.save(n.q,a,op(),17,null,"NEW academic content");
            assertThat(s.sql().queryForObject(s.r.signature(s.ctl+".saves"),String.class)).isEqualTo(frozen);
            long bytes=n.count("select sum((x->>'bytes_used')::bigint) from jsonb_array_elements("+n.ctl+".measure()) x where x->>'scope'='INSTITUTION' and (x->>'owner_id')::bigint=? and x->>'category'='ORIGINAL'",n.q.institutionId());
            n.e.c.b.service.limit(n.q.institutionId(),"ORIGINAL",1000000,bytes,op());var before=n.state();
            assertThat(s.post(s.h.pupil,s.q,s.body(op(),s.open,null,"Original C plus additional bytes")).statusCode()).isEqualTo(503);
            assertThat(n.state()).isEqualTo(before);assertThat(s.sql().queryForObject(s.r.signature(s.ctl+".saves"),String.class)).isEqualTo(frozen);
            n.e.c.b.service.limit(n.q.institutionId(),"ORIGINAL",1000000,268435456,op());s.save(op(),s.open,null,"Allowed after explicit quota change");
            n.own(n.q,a);assertThat(s.save(original,s.open,null,"Original C")).isEqualTo(receipt);
        } finally {for(String prefix:List.of("t12f_","t12e_","t12d_"))s.sql().execute("drop schema if exists "+prefix+suffix+" cascade");s.stop();}
    }
    @Test void readmissionWithAnotherEpisodeCannotRefundConsumptionOrRetargetOldAttempt() throws Exception {
        q=prepare(r.institution,r.classId,r.episode,1,"ALL_AT_ONCE",false,Instant.now().plusSeconds(900));resolve(q,null);long a=begun();submit(q,a,op(),List.of(answer(q,17,null,"Historical")));
        long recipient=count("select id from t11b8_recipients where delivery_id=?",q.deliveryId());
        revisions.recipient(q.deliveryId(),f.student.getId(),r.episode,1,false,op());r.academic.transitionEnrollment(r.episode,Enrollment.Status.WITHDRAWN,Instant.now());
        Instant at=Instant.now();var episode=r.academic.createEnrollment(r.institution,r.classId,r.member,at,r.until,at);r.academic.transitionEnrollment(episode.id(),Enrollment.Status.ACTIVE,Instant.now());
        revisions.recipient(q.deliveryId(),f.student.getId(),episode.id(),2,true,op());resolve(q,null);
        denied(send("GET",h.pupil,q,"/own-attempts/"+a,null));denied(send("POST",h.pupil,q,"/own-attempts",body(op())));
        assertThat(count("select count(*) from "+ctl+".attempts where resource_id=?",resource(q))).isEqualTo(1);
        assertThat(count("select enrollment_id from "+ctl+".attempts where id=?",a)).isEqualTo(r.episode);
        assertThat(count("select id from t11b8_recipients where delivery_id=?",q.deliveryId())).isEqualTo(recipient);
    }
    @Test void futureStartAndWithdrawnAccessDenyAndChoiceOnlySubmissionClosesAutomatically() throws Exception {
        var draft=revisions.nextDraft(q.revisionId(),op());var payload=f.json.readTree(payload(3,"ALL_AT_ONCE",false,0));((com.fasterxml.jackson.databind.node.ArrayNode)payload.get("questions")).remove(0);
        draft=revisions.edit(draft.id(),1,payload.toString(),op());var published=revisions.publish(draft.id(),draft.version(),op());
        Request future=deliver(resource(q),published.id(),r.institution,r.classId,r.episode,Instant.now().plusSeconds(600),null);resolve(q,null);
        denied(send("POST",h.pupil,future,"/own-attempts",body(op())));denied(send("GET",h.pupil,future,"",null));
        assertThat(ok(send("GET",h.admin,future,"?recipientAccountId="+f.student.getId(),null)).path("projection").asText()).isEqualTo("SUPERVISION_METADATA");
        Request choice=deliver(resource(q),published.id(),r.institution,r.otherClass,r.otherEpisode,Instant.now().minusSeconds(60),Instant.now().plusSeconds(900));resolve(q,null);
        long a=begin(choice,op()).path("attemptId").asLong();var result=submit(choice,a,op(),List.of(answer(choice,18,31L,null)));
        assertThat(result.path("status").asText()).isEqualTo("GRADED");assertThat(result.path("gradeClosed").asBoolean()).isTrue();
        denied(send("GET",h.pupil,choice,"/own-attempts/"+a+"/correction",null));
        long active=begun();revisions.resourceState(resource(q),1,"WITHDRAWN",false,false,op());resolve(q,null);
        denied(send("POST",h.pupil,q,"/own-attempts/"+active+"/answers",body(op(),"answer",answer(q,17,null,"Withdrawn"))));
        denied(send("POST",h.pupil,q,"/own-attempts/"+active+"/submit",body(op(),"answers",List.of(answer(q,17,null,"Withdrawn")))));
    }
    @Test void startCrossingItsWindowRollsBackTheFinishedReceiptAndConsumption() throws Exception {
        Instant due=Instant.now().plusSeconds(22);q=prepare(r.institution,r.classId,r.episode,3,"ALL_AT_ONCE",false,due);resolve(q,null);var before=state();
        var service=f.app.getBean(NewEvaluationExecutionService.class);JdbcTemplate spy=spy(sql());var finished=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call->{Object result=call.callRealMethod();finished.set(true);long remaining=Duration.between(Instant.now(),due.plusMillis(50)).toMillis();if(remaining>0)Thread.sleep(remaining);return result;})
          .when(spy).queryForObject(contains(".finish_operation("),eq(Object.class),any(Object[].class));
        ReflectionTestUtils.setField(service,"jdbc",spy);
        try{denied(send("POST",h.pupil,q,"/own-attempts",body(op())));}finally{ReflectionTestUtils.setField(service,"jdbc",sql());}
        assertThat(finished).isTrue();assertThat(state()).isEqualTo(before);
    }
}

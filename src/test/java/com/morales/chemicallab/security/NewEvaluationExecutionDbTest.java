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
            if(uncertainty!=null && source.equals("evaluations")){row.remove(List.of("institutionId","classId","accountId","enrollmentId","sourceTimezone"));row.put("scope",uncertainty);}
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
}

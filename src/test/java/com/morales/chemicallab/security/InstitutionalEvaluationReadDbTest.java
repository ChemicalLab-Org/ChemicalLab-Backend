package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP + actual T02 filter chain, frozen B1-B11 and disposable PostgreSQL.
 * Spies only place barriers; authorization, transport, locks, data and writers remain real.
 */
class InstitutionalEvaluationReadDbTest {
    EvaluationDeliveryAccessDbTest e;
    WhiteboardSecurityTransportDbTest f;
    ResourceReconciliationDbTest r;
    Map<String,Object> activation;
    String pupil,teacher,admin,peer;
    JdbcTemplate sql(){return f.sql();}
    @BeforeEach void start(TestInfo info) throws Exception {
        e=new EvaluationDeliveryAccessDbTest();e.start(info);f=e.f;r=e.r;
        activation=new HashMap<>();
        ((ConfigurableEnvironment)f.app.getEnvironment()).getPropertySources().addFirst(new MapPropertySource("t12b-explicit-fixture",activation));
        enable();
        pupil=f.login(f.student);teacher=f.login(f.teacher);admin=f.login(e.c.school);peer=f.login(f.peerStudent);
    }
    void enable(){activation.put("app.evaluations.institutional.enabled",true);activation.put("app.evaluations.institutional.fixture-schema",f.schema);}
    @AfterEach void stop() throws Exception {if(e!=null)e.stop();}
    String base(Request q){return "/api/institutions/"+q.institutionId()+"/classes/"+q.classId()+"/evaluation-reads/"+q.contract()+"/evaluations/"+q.evaluationId()+"/deliveries/"+q.deliveryId()+"/revisions/"+q.revisionId();}
    String path(Request q){return base(q)+(q.action()==Action.READ_DELIVERY?"":"/own-attempts/"+q.attemptId()+(q.action()==Action.READ_CORRECTION?"/correction":""));}
    HttpResponse<String> get(String token,Request q) throws Exception {return f.request("GET",path(q),token,null);}
    JsonNode ok(String token,Request q) throws Exception {var response=get(token,q);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");return f.json.readTree(response.body());}
    void forbidden(String token,Request q) throws Exception {var response=get(token,q);assertThat(response.statusCode()).as(response.body()).isEqualTo(403);assertNoSecrets(response.body());}
    void assertNoSecrets(String body){assertThat(body).doesNotContain("PRIVATE-EXPECTED-T12B-1937","PRIVATE-CRITERIA-T12B-2fcd","PRIVATE-NESTED-T12B-991a","PRIVATE-CORRECTION-T12B-64c9","PRIVATE-FEEDBACK-T12B-8e32","expected_answer","expectedAnswer","criteria","payload_checksum","correctOptionText","pointsAwarded","teacherFeedback","finalScore","\"correct\":","\"score\":","\"overallFeedback\":");}

    @Test void disabledByDefaultAndFlagAloneCannotEscapeExplicitFixtureEnvironment() throws Exception {
        Request q=e.request(false,Action.READ_DELIVERY);e.resolve(q);
        ((ConfigurableEnvironment)f.app.getEnvironment()).getPropertySources().remove("t12b-explicit-fixture");
        assertThat(get(pupil,q).statusCode()).isEqualTo(404);
        ((ConfigurableEnvironment)f.app.getEnvironment()).getPropertySources().addFirst(new MapPropertySource("t12b-explicit-fixture",activation));
        activation.remove("app.evaluations.institutional.fixture-schema");assertThat(get(pupil,q).statusCode()).isEqualTo(404);
        activation.put("app.evaluations.institutional.fixture-schema","t03_"+"a".repeat(32));assertThat(get(pupil,q).statusCode()).isEqualTo(403);
        enable();((ConfigurableEnvironment)f.app.getEnvironment()).setActiveProfiles("prod");assertThat(get(pupil,q).statusCode()).isEqualTo(404);
        ((ConfigurableEnvironment)f.app.getEnvironment()).setActiveProfiles("test");ok(pupil,q);
    }
    @Test void bilateralScopesSharedAccountAndInstitutionalFacultiesOverrideNoGlobalRole() throws Exception {
        for(boolean b:List.of(false,true)) {
            Request q=e.request(b,Action.READ_DELIVERY);e.resolve(q);ok(pupil,q);
            var metadata=f.request("GET",base(q)+"?recipientAccountId="+f.student.getId(),teacher,null);
            assertThat(metadata.statusCode()).isEqualTo(200);
            var json=f.json.readTree(metadata.body());assertThat(names(json)).containsExactlyInAnyOrder("scope","projection","observedAt");assertNoSecrets(metadata.body());
            assertThat(json.path("projection").asText()).isEqualTo("SUPERVISION_METADATA");
            forbidden(peer,q);forbidden(teacher,e.request(b,Action.READ_OWN_ATTEMPT));
            ok(pupil,e.request(b,Action.READ_OWN_ATTEMPT));
            String platform=f.login(e.c.platform);forbidden(platform,q);
            Request other=e.request(!b,Action.READ_DELIVERY);
            for(Request manipulated:List.of(
                    e.changed(q,other.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),null),
                    e.changed(q,q.institutionId(),other.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),null),
                    e.changed(q,q.institutionId(),q.classId(),other.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),null),
                    e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),other.deliveryId(),q.revisionId(),q.recipientAccountId(),null),
                    e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),other.revisionId(),q.recipientAccountId(),null))) forbidden(pupil,manipulated);
        }
    }
    @Test void ownRoutesDeriveAccountAndSupervisionRecipientSelectorsNeverGrantPermission() throws Exception {
        Request q=e.request(false,Action.READ_OWN_ATTEMPT);e.resolve(q);
        assertThat(f.request("GET",path(q)+"?recipientAccountId="+f.student.getId()+"&projection=OWN_CORRECTION&action=READ_CORRECTION&role=ADMIN_INSTITUCION&allow=true",peer,null).statusCode()).isEqualTo(403);
        assertThat(f.request("GET",base(q)+"?recipientAccountId="+f.peerStudent.getId(),admin,null).statusCode()).isEqualTo(403);
        Request other=e.request(true,Action.READ_OWN_ATTEMPT);e.resolve(other);
        forbidden(pupil,e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),other.attemptId()));
        assertThat(f.request("GET",base(q)+"?recipientAccountId="+f.student.getId(),peer,null).statusCode()).isEqualTo(403);
        assertThat(f.request("POST",path(q),pupil,"{}").statusCode()).isNotEqualTo(200);
    }
    @Test void realAuthenticationRejectsMissingInvalidRevokedExpiredVersionAndTemporarySessions() throws Exception {
        Request q=e.request(false,Action.READ_OWN_ATTEMPT);e.resolve(q);
        assertThat(get(null,q).statusCode()).isEqualTo(401);assertThat(get("invalid",q).statusCode()).isEqualTo(401);
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(pupil).getPrincipal();
        sql().update("update account_sessions set revoked_at=statement_timestamp() where id=?",principal.sessionId());
        assertThat(get(pupil,q).statusCode()).isEqualTo(401);
        pupil=f.login(f.student);principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(pupil).getPrincipal();
        sql().update("update account_sessions set expires_at=statement_timestamp()-interval '1 second' where id=?",principal.sessionId());assertThat(get(pupil,q).statusCode()).isEqualTo(401);
        pupil=f.login(f.student);f.mutate(f.student,UserAccount::invalidateSessions);assertThat(get(pupil,q).statusCode()).isEqualTo(401);
        f.mutate(f.student,u->u.setTemporaryPassword(true));pupil=f.login(f.student);
        var temporary=get(pupil,q);assertThat(temporary.statusCode()).isEqualTo(403);assertThat(temporary.body()).contains("PASSWORD_CHANGE_REQUIRED");
        f.mutate(f.student,u->u.setActive(false));assertThat(get(pupil,q).statusCode()).isEqualTo(401);
    }
    @Test void membershipGrantEnrollmentAndAssignmentMustBeCurrentlyEffective() throws Exception {
        Request a=e.request(false,Action.READ_OWN_ATTEMPT),b=e.request(true,Action.READ_OWN_ATTEMPT);e.resolve(a);e.resolve(b);
        r.institutions.transitionMembership(r.member,InstitutionMembership.Status.SUSPENDED,Instant.now());forbidden(pupil,a);ok(pupil,b);
        long foreignMember=e.count("select membership_id from enrollments where id=?",e.c.foreignEpisode);
        long grant=e.count("select id from membership_role_grants where membership_id=? and role='ESTUDIANTE'",foreignMember);
        r.institutions.revokeRole(grant,Instant.now());forbidden(pupil,b);
        r.institutions.grantRole(foreignMember,MembershipRoleGrant.InstitutionalRole.ESTUDIANTE,Instant.now().plusSeconds(300),r.until);forbidden(pupil,b);
        r.institutions.grantRole(foreignMember,MembershipRoleGrant.InstitutionalRole.ESTUDIANTE,r.from.plusSeconds(50),r.from.plusSeconds(100));forbidden(pupil,b);
        Request delivery=e.request(false,Action.READ_DELIVERY);
        r.academic.transitionAssignment(e.responsible,TeachingAssignment.Status.ENDED,Instant.now());e.resolve(delivery);
        assertThat(f.request("GET",base(delivery)+"?recipientAccountId="+f.student.getId(),teacher,null).statusCode()).isEqualTo(403);
    }
    @Test void missingUncertainStaleParentAndWrongNamespaceFailClosed() throws Exception {
        Request q=e.request(false,Action.READ_OWN_ATTEMPT);forbidden(pupil,q);
        for(String scope:List.of("UNRESOLVED","CONFLICT")){e.resolve(q,scope);forbidden(pupil,q);}
        e.resolve(q);ok(pupil,q);
        sql().update("update evaluations set title='STALE-T12B' where id=?",q.evaluationId());forbidden(pupil,q);
        e.resolve(q);ok(pupil,q);
        forbidden(pupil,new Request(Contract.LEGACY,q.action(),q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()));
        e.resolve(q,"UNRESOLVED","t11b8_recipients");forbidden(pupil,q);
        e.resolve(q,"UNRESOLVED","t11b8_fixture_attempts");forbidden(pupil,q);
    }
    @Test void suspendedEnrollmentAndClosedPeriodDenyThroughHttpInTheirOwnInstitution() throws Exception {
        Request a=e.request(false,Action.READ_OWN_ATTEMPT),b=e.request(true,Action.READ_OWN_ATTEMPT);e.resolve(a);e.resolve(b);
        ok(pupil,a);ok(pupil,b);
        r.academic.transitionEnrollment(r.episode,Enrollment.Status.SUSPENDED,Instant.now());e.resolve(a);forbidden(pupil,a);ok(pupil,b);
        long period=e.count("select period_id from academic_classes where id=?",r.foreignClass);
        r.academic.transitionPeriod(period,AcademicPeriod.Status.CLOSED,Instant.now());e.resolve(b);forbidden(pupil,b);
    }
    @Test void enabledRouteStillRejectsAnActualDifferentDisposableDatabase() throws Exception {
        Request q=e.request(false,Action.READ_DELIVERY);e.resolve(q);
        String url=f.setting("spring.datasource.url").replace("/chemicallab_t01_test","/postgres");
        try(Connection wrong=DriverManager.getConnection(url,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            assertThat(f.schema).matches("t03_[a-f0-9]{32}");
            try(var statement=wrong.createStatement()){statement.execute("create schema "+f.schema);statement.execute("set search_path to "+f.schema);}
            try {
                var dataSource=new org.springframework.jdbc.datasource.SingleConnectionDataSource(wrong,true);
                ReflectionTestUtils.setField(e.access,"jdbc",new JdbcTemplate(dataSource));
                forbidden(pupil,q);
            } finally {
                ReflectionTestUtils.setField(e.access,"jdbc",f.sql());
                try(var statement=wrong.createStatement()){statement.execute("drop schema "+f.schema);}
            }
        }
    }
    @Test void realPersistenceFailureReturnsNoProjectionOrBusinessSuccess() throws Exception {
        Request q=e.request(false,Action.READ_DELIVERY);e.resolve(q);var before=e.c.state();
        var jdbc=spy(sql());doAnswer(call->{sql().execute("select 1/0");return null;}).when(jdbc).execute("select t11b5_fixture()");
        ReflectionTestUtils.setField(e.access,"jdbc",jdbc);
        try {
            var response=get(pupil,q);assertThat(response.statusCode()).isEqualTo(503);assertNoSecrets(response.body());
            var after=e.c.state();before.remove("system_logs");after.remove("system_logs");assertThat(after).isEqualTo(before);
        } finally {ReflectionTestUtils.setField(e.access,"jdbc",f.sql());}
    }

    @Test void resolvedRootCannotExposeUncertainLegacyQuestionOptionOrOwnAnswerRows() throws Exception {
        legacyContent();
        long question=sql().queryForObject("insert into evaluation_questions(evaluation_id,question_text,question_type,points,order_index,required,active) values(?,'Public choice','MULTIPLE_CHOICE',1,1,true,true) returning id",Long.class,r.evaluation);
        sql().update("insert into evaluation_options(question_id,option_text,order_index,active,correct) values(?,'Public option',0,true,true)",question);
        Request delivery=e.legacy(Action.READ_DELIVERY),attempt=e.legacy(Action.READ_OWN_ATTEMPT);
        for(String source:List.of("evaluation_questions","evaluation_options","evaluation_answers")) {
            Request q=source.equals("evaluation_answers")?attempt:delivery;
            e.resolve(q,"UNRESOLVED",source);forbidden(pupil,q);
            assertThat(f.request("GET",base(delivery)+"?recipientAccountId="+f.student.getId(),admin,null).statusCode()).isEqualTo(200);
        }
        e.resolve(attempt);assertThat(ok(pupil,attempt).path("answers").toString()).contains("OWN-LEGACY-T12B");
    }

    String payload(String title){return """
        {"resource":{"title":"%s","max_attempts":3,"time_limit_minutes":30,"question_display_mode":"ALL_AT_ONCE",
          "randomize_questions":false,"private":{"criteria":"PRIVATE-CRITERIA-T12B-2fcd"}},
         "questions":[{"id":17,"question_text":"Public open","question_type":"OPEN_TEXT","points":1,"order_index":1,
            "expected_answer":"PRIVATE-EXPECTED-T12B-1937","explanation":"PRIVATE-CORRECTION-T12B-64c9","nested":{"key":"PRIVATE-NESTED-T12B-991a"}},
           {"id":23,"question_text":"Public choice","question_type":"MULTIPLE_CHOICE","points":2,"order_index":0}],
         "options":[{"id":201,"question_id":23,"option_text":"Choice A","order_index":1,"correct":true,"nested":{"criteria":"PRIVATE-NESTED-T12B-991a"}},
           {"id":202,"question_id":23,"option_text":"Choice B","order_index":0,"correct":false}],"private":{"key":"PRIVATE-NESTED-T12B-991a"}}
        """.formatted(title);}
    void academicContent() {
        e.aResource=e.revisions.createNew(AcademicRevisionMaintenanceService.Kind.EVALUATION,r.institution,f.teacher.getId(),r.authorMember,payload("Published exact T12B"),e.op());
        long draft=e.count("select id from t11b8_revisions where resource_id=?",e.aResource);
        e.aRevision=e.revisions.publish(draft,1,e.op()).id();
        e.aDelivery=e.delivery(e.aRevision,r.institution,r.classId,Instant.now().minusSeconds(60),Instant.now().plusSeconds(600));
        long recipient=e.revisions.recipient(e.aDelivery,f.student.getId(),r.episode,0,true,e.op());
        e.aAttempt=e.revisions.reserveFixtureAttempt(recipient,e.op());
        e.revisions.finishFixtureAttempt(e.aAttempt,1,"{\"17\":{\"answer_text\":\"MY-ANSWER-T12B\",\"expected_answer\":\"PRIVATE-EXPECTED-T12B-1937\",\"nested\":{\"key\":\"PRIVATE-NESTED-T12B-991a\"}},\"23\":{\"selected_option_id\":202,\"teacherFeedback\":\"PRIVATE-FEEDBACK-T12B-8e32\"}}",18,e.op());
    }
    @Test void completeNewJsonUsesExactPublishedRevisionAndNestedAllowlists() throws Exception {
        academicContent();Request q=e.request(false,Action.READ_DELIVERY);e.resolve(q);
        JsonNode content=ok(pupil,q);String body=content.toString();assertNoSecrets(body);
        assertThat(names(content)).containsExactlyInAnyOrder("scope","title","description","instructions","topic","timeLimitMinutes","allowChemicalCalculator","allowPeriodicTable","trackTabExit","questionDisplayMode","randomizeQuestions","questions","observedAt");
        assertThat(content.path("questions").get(0).path("id").asLong()).isEqualTo(23);
        assertThat(content.path("questions").get(0).path("options").get(0).path("id").asLong()).isEqualTo(202);
        for(JsonNode question:content.get("questions")){assertThat(names(question)).containsExactlyInAnyOrder("id","questionText","questionType","points","orderIndex","required","options");for(JsonNode option:question.get("options"))assertThat(names(option)).containsExactlyInAnyOrder("id","optionText","orderIndex");}
        JsonNode attempt=ok(pupil,e.request(false,Action.READ_OWN_ATTEMPT));assertNoSecrets(attempt.toString());
        assertThat(attempt.path("questionOrder").toString()).isEqualTo("[17,23]");assertThat(attempt.path("answers").toString()).contains("MY-ANSWER-T12B");
        assertThat(names(attempt)).containsExactlyInAnyOrder("scope","attemptId","status","attemptNumber","questionOrder","currentQuestionIndex","answers","observedAt");
        for(JsonNode answer:attempt.get("answers"))assertThat(names(answer)).containsExactlyInAnyOrder("questionId","selectedOptionId","answerText");
        var next=e.revisions.nextDraft(e.aRevision,e.op());next=e.revisions.edit(next.id(),1,payload("Successor must never replace exact T12B"),e.op());
        next=e.revisions.publish(next.id(),next.version(),e.op());e.resolve(q);
        assertThat(ok(pupil,q).path("title").asText()).isEqualTo("Published exact T12B");
        forbidden(pupil,e.changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),next.id(),q.recipientAccountId(),null));
        assertThat(sql().queryForObject("select status from evaluations where id=?",String.class,q.evaluationId())).isEqualTo("DRAFT");
        var reads=f.app.getBean(InstitutionalEvaluationReadService.class);var jdbc=spy(sql());ReflectionTestUtils.setField(reads,"jdbc",jdbc);
        try {
            var metadata=f.request("GET",base(q)+"?recipientAccountId="+f.student.getId(),admin,null);assertThat(metadata.statusCode()).isEqualTo(200);assertNoSecrets(metadata.body());assertThat(metadata.body()).doesNotContain("questions","Published exact T12B","MY-ANSWER");
            verifyNoInteractions(jdbc);
        } finally {ReflectionTestUtils.setField(reads,"jdbc",f.sql());}
    }
    void legacyContent() {
        long question=sql().queryForObject("insert into evaluation_questions(evaluation_id,question_text,question_type,points,order_index,required,active,expected_answer,explanation) values(?,'Legacy public open','OPEN_TEXT',2,0,true,true,'PRIVATE-EXPECTED-T12B-1937','PRIVATE-CORRECTION-T12B-64c9') returning id",Long.class,r.evaluation);
        sql().update("insert into evaluation_answers(attempt_id,question_id,answer_text,points_awarded,reviewed,teacher_feedback) values(?,?,'OWN-LEGACY-T12B',2,true,'PRIVATE-FEEDBACK-T12B-8e32')",e.legacyAttempt,question);
        sql().update("update evaluation_attempts set question_order=?,current_question_index=1,score=2,max_score=2,final_score=20,overall_feedback='PRIVATE-FEEDBACK-T12B-8e32' where id=?",Long.toString(question),e.legacyAttempt);
    }
    @Test void legacyUnitReadsHideCorrectionAndNeverReconcileOrAdvanceExpiredAttempts() throws Exception {
        legacyContent();Request q=e.legacy(Action.READ_DELIVERY);e.resolve(q);
        assertNoSecrets(ok(pupil,q).toString());JsonNode attempt=ok(pupil,e.legacy(Action.READ_OWN_ATTEMPT));assertNoSecrets(attempt.toString());assertThat(attempt.path("answers").toString()).contains("OWN-LEGACY-T12B");
        sql().update("insert into evaluation_questions(evaluation_id,question_text,question_type,points,order_index,required,active) values(?,'INACTIVE-QUESTION-T12B','OPEN_TEXT',1,1,true,false)",r.evaluation);
        sql().update("update evaluations set question_display_mode='ONE_BY_ONE',randomize_questions=true where id=?",r.evaluation);e.resolve(q);
        assertThat(ok(pupil,q).path("questions").toString()).doesNotContain("INACTIVE-QUESTION-T12B");
        JsonNode sequential=ok(pupil,e.legacy(Action.READ_OWN_ATTEMPT));assertThat(sequential.path("questionOrder")).isEqualTo(attempt.path("questionOrder"));assertThat(sequential.path("currentQuestionIndex").asInt()).isEqualTo(1);
        sql().update("update evaluation_attempts set status='IN_PROGRESS' where id=?",e.legacyAttempt);
        sql().update("update evaluation_assignments set due_at='2026-02-02 00:00:00' where id=?",r.assignment);e.resolve(e.legacy(Action.READ_OWN_ATTEMPT));
        var before=e.c.state();var sequences=sequences();ok(pupil,e.legacy(Action.READ_OWN_ATTEMPT));
        assertThat(e.c.state()).isEqualTo(before);assertThat(sequences()).isEqualTo(sequences);
        assertThat(sql().queryForObject("select status from evaluation_attempts where id=?",String.class,e.legacyAttempt)).isEqualTo("IN_PROGRESS");
    }
    @Test void legacyT05FutureEmptyUndatedAssignmentsAndIndividualGradeGuardCorrection() throws Exception {
        legacyContent();sql().update("update evaluation_attempts set status='GRADED',grade_closed=true where id=?",e.legacyAttempt);
        sql().update("update evaluation_assignments set due_at='2026-02-02 00:00:00' where id=?",r.assignment);
        Request q=e.legacy(Action.READ_CORRECTION);e.resolve(q);forbidden(pupil,q);
        sql().update("update evaluation_assignments set active=false where id=?",r.secondAssignment);e.resolve(q);forbidden(pupil,q);
        sql().update("update evaluation_assignments set active=false where id=?",r.thirdAssignment);e.resolve(q);
        JsonNode correction=ok(pupil,q);assertThat(correction.path("finalScore").decimalValue()).isEqualByComparingTo("20");
        assertThat(correction.toString()).contains("PRIVATE-CORRECTION-T12B-64c9","PRIVATE-FEEDBACK-T12B-8e32").doesNotContain("PRIVATE-EXPECTED-T12B-1937","expectedAnswer","expected_answer");
        sql().update("update evaluation_attempts set grade_closed=false where id=?",e.legacyAttempt);e.resolve(q);forbidden(pupil,q);
    }
    @Test void newT05AllRevisionsAndClassesBlockUntilGlobalClosureWithoutPublishingPrivateCriteria() throws Exception {
        academicContent();var next=e.revisions.nextDraft(e.aRevision,e.op());next=e.revisions.publish(next.id(),1,e.op());
        long future=e.delivery(next.id(),r.institution,r.otherClass,Instant.now().plusSeconds(600),null);
        e.revisions.closeDelivery(e.aDelivery,1,e.op());Request q=e.request(false,Action.READ_CORRECTION);e.resolve(q);forbidden(pupil,q);
        e.revisions.closeDelivery(future,1,e.op());e.resolve(q);JsonNode correction=ok(pupil,q);
        assertThat(correction.path("finalScore").decimalValue()).isEqualByComparingTo("18");
        assertThat(correction.toString()).contains("PRIVATE-CORRECTION-T12B-64c9","Choice A").doesNotContain("PRIVATE-EXPECTED-T12B-1937","PRIVATE-CRITERIA-T12B-2fcd","PRIVATE-NESTED-T12B-991a","PRIVATE-FEEDBACK-T12B-8e32","expected_answer","expectedAnswer");
        assertNoSecrets(ok(pupil,e.request(false,Action.READ_OWN_ATTEMPT)).toString());
    }
    @Test void readsPreserveAllBusinessRowsSequencesQuotasHistoryAndSuccess() throws Exception {
        academicContent();Request a=e.request(false,Action.READ_DELIVERY),b=e.request(true,Action.READ_DELIVERY),legacy=e.legacy(Action.READ_DELIVERY);e.resolve(a);e.resolve(b);e.resolve(legacy);
        var before=e.c.state();var sequences=sequences();
        for(Request q:List.of(a,b,legacy)){ok(pupil,q);assertThat(f.request("GET",base(q)+"?recipientAccountId="+f.student.getId(),admin,null).statusCode()).isEqualTo(200);}
        ok(pupil,e.request(false,Action.READ_OWN_ATTEMPT));ok(pupil,e.legacy(Action.READ_OWN_ATTEMPT));
        assertThat(e.c.state()).isEqualTo(before);assertThat(sequences()).isEqualTo(sequences);
    }
    @Test void affectedLegacyRoutesRetainTheirExistingContract() throws Exception {
        legacyContent();e.resolve(e.legacy(Action.READ_DELIVERY));
        var detail=f.request("GET","/api/evaluations/student/"+r.evaluation,pupil,null);assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode json=f.json.readTree(detail.body());assertThat(json.has("questionsVisible")).isTrue();assertThat(json.has("assignmentId")).isTrue();assertNoSecrets(detail.body());
        var attempt=f.request("GET","/api/evaluations/student/attempts/"+e.legacyAttempt,pupil,null);assertThat(attempt.statusCode()).isEqualTo(200);assertThat(f.json.readTree(attempt.body()).has("canAnswer")).isTrue();
        var teacherDetail=f.request("GET","/api/evaluations/teacher/"+r.evaluation,teacher,null);assertThat(teacherDetail.statusCode()).isEqualTo(200);assertThat(teacherDetail.body()).contains("PRIVATE-EXPECTED-T12B-1937");
    }

    List<String> names(JsonNode node){var names=new ArrayList<String>();node.fieldNames().forEachRemaining(names::add);return names;}
    List<Map<String,Object>> sequences(){return sql().queryForList("select sequencename,last_value from pg_sequences where schemaname=current_schema() order by sequencename");}
    Connection connection() throws Exception {return DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));}
    void waitForBlocked(int pid) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end){if(Boolean.TRUE.equals(sql().queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid)))return;Thread.sleep(20);}
        fail("Independent PostgreSQL writer did not wait on the read barrier");
    }
    void race(Request q,String token,String mutation,int nextStatus) throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        boolean afterAuthorization=q.contract()==Contract.NEW;
        JdbcTemplate spy=spy(sql());
        doAnswer(call->{String statement=call.getArgument(0);call.callRealMethod();if(!afterAuthorization&&statement.startsWith("lock table ")){locked.countDown();assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();}return null;}).when(spy).execute(anyString());
        ReflectionTestUtils.setField(e.access,"jdbc",spy);
        var reads=f.app.getBean(InstitutionalEvaluationReadService.class);
        if(afterAuthorization) {
            JdbcTemplate projection=spy(sql());
            doAnswer(call->{Object value=call.callRealMethod();locked.countDown();assertThat(release.await(8,TimeUnit.SECONDS)).isTrue();return value;})
                    .when(projection).queryForObject(startsWith("select payload::text"),eq(String.class),eq(q.revisionId()));
            ReflectionTestUtils.setField(reads,"jdbc",projection);
        }
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try(Connection writer=connection()) {
            int pid;try(var s=writer.createStatement();var rows=s.executeQuery("select pg_backend_pid()")){rows.next();pid=rows.getInt(1);}
            Future<HttpResponse<String>> reading=threads.submit(()->get(token,q));assertThat(locked.await(8,TimeUnit.SECONDS)).isTrue();
            Future<?> changing=threads.submit(()->{try(var s=writer.createStatement()){s.execute(mutation);}return null;});
            waitForBlocked(pid);release.countDown();
            HttpResponse<String> result=reading.get(12,TimeUnit.SECONDS);assertThat(result.statusCode()).as(result.body()).isEqualTo(afterAuthorization?200:403);
            if(q.action()!=Action.READ_CORRECTION)assertNoSecrets(result.body());
            if(afterAuthorization)assertThat(f.json.readTree(result.body()).path("scope").path("institutionId").asLong()).isEqualTo(q.institutionId());
            changing.get(12,TimeUnit.SECONDS);
            // A new HTTP read must resolve again, never consume the old ALLOW.
            assertThat(get(token,q).statusCode()).isEqualTo(nextStatus);
        } finally {release.countDown();threads.shutdownNow();ReflectionTestUtils.setField(e.access,"jdbc",f.sql());ReflectionTestUtils.setField(reads,"jdbc",f.sql());}
    }
    @Test void contextWriterCannotChangeTheScopeBetweenAuthorizationAndProjection() throws Exception {
        Request q=e.request(false,Action.READ_DELIVERY);e.resolve(q);
        race(q,pupil,"update evaluations set title='CHANGED-PARENT-T12B' where id="+q.evaluationId(),403);
    }
    @Test void approvedB10ContextCorrectionDuringReadCannotMixInstitutions() throws Exception {
        Request q=e.request(false,Action.READ_DELIVERY);e.resolve(q);
        Request corrected=e.changed(q,r.foreign,r.foreignClass,q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),null);
        var mapping=f.json.readTree(e.c.mapping("evaluations",q.evaluationId(),r.foreign,r.foreignClass));
        for(var item:mapping.get("resolutions")) {
            var row=(com.fasterxml.jackson.databind.node.ObjectNode)item;
            if(row.has("enrollmentId"))row.put("enrollmentId",e.c.foreignEpisode);
            if(row.path("source").asText().equals("t11b8_fixture_attempts")){row.put("enrollmentId",e.c.foreignEpisode);row.put("sourceTimezone","America/Lima");}
        }
        long proposal=e.c.service.propose(e.c.root("evaluations",q.evaluationId()),mapping.toString(),"FIXTURE-CONTEXT-RACE",e.op());
        e.c.approve(proposal,"PLATFORM",e.c.platform,e.c.platformGrant);
        e.c.approve(proposal,"INSTITUTION",e.c.school,e.c.schoolGrant);
        race(q,pupil,"insert into t11b10_commands(operation_id,action,request) values('"+e.op()+"','APPLY','{\"proposal\":"+proposal+"}'::jsonb)",403);
        assertThat(ok(pupil,corrected).path("scope").path("institutionId").asLong()).isEqualTo(r.foreign);
    }
    @Test void sessionRevocationFromIndependentConnectionSerializesWithRead() throws Exception {
        Request q=e.request(false,Action.READ_OWN_ATTEMPT);e.resolve(q);
        var principal=(SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(pupil).getPrincipal();
        race(q,pupil,"update account_sessions set revoked_at=clock_timestamp() where id='"+principal.sessionId()+"'",401);
    }
    @Test void membershipPermissionWriterSerializesWithRead() throws Exception {
        Request q=e.request(false,Action.READ_OWN_ATTEMPT);e.resolve(q);
        long grant=e.count("select id from membership_role_grants where membership_id=? and role='ESTUDIANTE'",r.member);
        race(q,pupil,"update membership_role_grants set status='REVOKED',revoked_at=clock_timestamp() where id="+grant,403);
    }
    @Test void academicClosureDuringReadReturnsOldDenialThenLegitimatelyReleasedCorrection() throws Exception {
        legacyContent();sql().update("update evaluation_attempts set status='GRADED',grade_closed=true where id=?",e.legacyAttempt);
        sql().update("update evaluation_assignments set active=false where evaluation_id=? and id<>?",r.evaluation,r.assignment);
        Request q=e.legacy(Action.READ_CORRECTION);e.resolve(q);
        // Source mutation makes B10 stale until explicit reconciliation; it cannot expose data.
        race(q,pupil,"update evaluation_assignments set active=false where id="+r.assignment,403);
        e.resolve(q);assertThat(ok(pupil,q).toString()).contains("PRIVATE-CORRECTION-T12B-64c9");
    }
    @Test void writerCommittingBeforeReadLockCannotLeaveAStaleAuthorizationSnapshot() throws Exception {
        Request q=e.request(false,Action.READ_DELIVERY);e.resolve(q);
        ExecutorService thread=Executors.newSingleThreadExecutor();
        try(Connection writer=connection()) {
            writer.setAutoCommit(false);
            try(var statement=writer.createStatement()){statement.executeUpdate("update evaluations set title='WRITER-FIRST-T12B' where id="+q.evaluationId());}
            Future<HttpResponse<String>> reading=thread.submit(()->get(pupil,q));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);boolean blocked=false;
            while(System.nanoTime()<until){blocked=Boolean.TRUE.equals(sql().queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like 'lock table %')",Boolean.class));if(blocked)break;Thread.sleep(20);}
            assertThat(blocked).isTrue();writer.commit();
            assertThat(reading.get(12,TimeUnit.SECONDS).statusCode()).isEqualTo(403);
        } finally {thread.shutdownNow();}
    }
}

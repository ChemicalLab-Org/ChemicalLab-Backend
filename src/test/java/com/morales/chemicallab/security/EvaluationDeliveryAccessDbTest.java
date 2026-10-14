package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;
import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual frozen B1-B11, PostgreSQL relationships and T02 persisted sessions. No runtime adoption. */
class EvaluationDeliveryAccessDbTest {
    ContextReconciliationDbTest c;
    ResourceReconciliationDbTest r;
    WhiteboardSecurityTransportDbTest f;
    EvaluationDeliveryAccessService access;
    AcademicRevisionMaintenanceService revisions;
    long aResource,bResource,aDelivery,bDelivery,aRevision,bRevision,aAttempt,bAttempt;
    long legacyDelivery,legacyRevision,legacyAttempt,responsible;
    SessionPrincipal pupil,teacher,admin;
    JdbcTemplate sql(){return f.sql();}
    long count(String query,Object...args){return sql().queryForObject(query,Long.class,args);}
    UUID op(){return UUID.randomUUID();}

    @BeforeEach void start(TestInfo info) throws Exception {
        c=new ContextReconciliationDbTest();c.start(info);r=c.r;f=c.f;
        sql().execute(r.script("t11b11-v1"));
        access=f.app.getBean(EvaluationDeliveryAccessService.class);revisions=c.b.b8.service;
        aResource=c.b.resource;bResource=c.b.foreignResource;aRevision=c.b.published.id();bRevision=c.b.b8.draft(bResource);
        aDelivery=delivery(aRevision,r.institution,r.classId,Instant.now().minusSeconds(60),Instant.now().plusSeconds(600));
        bDelivery=delivery(bRevision,r.foreign,r.foreignClass,Instant.now().minusSeconds(60),Instant.now().plusSeconds(600));
        long ar=revisions.recipient(aDelivery,f.student.getId(),r.episode,0,true,op());
        long br=revisions.recipient(bDelivery,f.student.getId(),c.foreignEpisode,0,true,op());
        aAttempt=revisions.reserveFixtureAttempt(ar,op());bAttempt=revisions.reserveFixtureAttempt(br,op());
        revisions.finishFixtureAttempt(aAttempt,1,"{}",18,op());revisions.finishFixtureAttempt(bAttempt,1,"{}",19,op());
        var assignment=r.academic.planAssignment(r.institution,r.classId,r.authorMember,r.from.plusSeconds(10),r.until,r.from.plusSeconds(3));
        responsible=assignment.id();r.academic.transitionAssignment(responsible,TeachingAssignment.Status.ACTIVE,r.from.plusSeconds(10));
        long member=count("select id from institution_memberships where institution_id=? and account_id=?",r.foreign,f.teacher.getId());
        r.institutions.grantRole(member,ADMIN_INSTITUCION,r.from.plusSeconds(20),null);
        legacyAttempt=r.attempt(1,AttemptStatus.PENDING_MANUAL_REVIEW,r.assignment,true);
        legacyDelivery=count("select id from t11b3_evaluation_deliveries where legacy_assignment_id=?",r.assignment);
        legacyRevision=count("select revision_id from t11b3_evaluation_deliveries where id=?",legacyDelivery);
        pupil=session(f.student);teacher=session(f.teacher);admin=session(c.school);
    }
    @AfterEach void stop() throws Exception {SecurityContextHolder.clearContext();if(c!=null)c.stop();}
    SessionPrincipal session(UserAccount account) throws Exception {
        return (SessionPrincipal)f.app.getBean(AccountSessionService.class).authenticate(f.login(account)).getPrincipal();
    }
    long delivery(long revision,long institution,long clazz,Instant start,Instant due){return revisions.assign(revision,institution,clazz,start,due,op());}
    Request request(boolean b,Action action) {
        return new Request(Contract.NEW,action,b?r.foreign:r.institution,b?r.foreignClass:r.classId,
            count("select evaluation_id from t11b8_resources where id=?",b?bResource:aResource),b?bDelivery:aDelivery,b?bRevision:aRevision,
            f.student.getId(),action==Action.READ_DELIVERY?null:b?bAttempt:aAttempt);
    }
    Request legacy(Action action){return new Request(Contract.LEGACY,action,r.institution,r.classId,r.evaluation,legacyDelivery,legacyRevision,f.student.getId(),action==Action.READ_DELIVERY?null:legacyAttempt);}
    void resolve(Request q) throws Exception {resolve(q,null);}
    void resolve(Request q,String uncertain) throws Exception { resolve(q, uncertain, null); }
    void resolve(Request q,String uncertain,String uncertainSource) throws Exception {
        String map=c.mapping("evaluations",q.evaluationId(),q.institutionId(),q.classId());
        var parsed=f.json.readTree(map);
        long episode=count("select e.id from enrollments e join institution_memberships m on m.id=e.membership_id where e.institution_id=? and e.class_id=? and m.account_id=?",q.institutionId(),q.classId(),f.student.getId());
        for(var item:parsed.get("resolutions")) {
            var row=(com.fasterxml.jackson.databind.node.ObjectNode)item;
            if(row.has("enrollmentId"))row.put("enrollmentId",episode);
            if(row.path("source").asText().equals("t11b8_fixture_attempts")) {
                row.put("enrollmentId",episode);row.put("sourceTimezone","America/Lima");
            }
            if(uncertain!=null&&(uncertainSource==null||row.path("source").asText().equals(uncertainSource))){row.remove(List.of("institutionId","classId","accountId","enrollmentId","sourceTimezone"));row.put("scope",uncertain);}
        }
        long proposal=c.service.propose(c.root("evaluations",q.evaluationId()),parsed.toString(),"FIXTURE-ACCESS",op());
        c.approve(proposal,"PLATFORM",c.platform,c.platformGrant);
        c.approve(proposal,"INSTITUTION",c.school,q.institutionId()==r.foreign?c.schoolGrant:c.foreignSchoolGrant);
        assertThat(c.service.apply(proposal,op())).isEqualTo("APPLIED");
    }
    Decision allow(SessionPrincipal p,Request q) {
        var decision=access.decide(p,q);assertThat(decision.outcome()).as(q.toString()).isEqualTo(Outcome.ALLOW);
        assertThat(decision.context()).isNotNull();return decision;
    }
    void deny(SessionPrincipal p,Request q){var d=access.decide(p,q);assertThat(d.allowed()).as(String.valueOf(q)).isFalse();assertThat(d.context()).isNull();}
    Request changed(Request q,long institution,long clazz,long evaluation,long delivery,long revision,long account,Long attempt) {
        return new Request(q.contract(),q.action(),institution,clazz,evaluation,delivery,revision,account,attempt);
    }

    @Test void sharedAccountAndDistinctGrantsAreBilateralWithoutGlobalRoleAuthority() throws Exception {
        for(boolean b:List.of(false,true)) {
            Request q=request(b,Action.READ_OWN_ATTEMPT);resolve(q);
            allow(pupil,q);deny(teacher,q);deny(session(f.peerStudent),q);
            allow(teacher,request(b,Action.SUPERVISE));
            if(b)deny(teacher,request(b,Action.REVIEW_ATTEMPT));else allow(teacher,request(b,Action.REVIEW_ATTEMPT));
            deny(session(c.platform),request(b,Action.SUPERVISE));
        }
    }
    @Test void manipulatedSelectorsAndTypedIdsDenyInBothDirections() throws Exception {
        resolve(request(false,Action.READ_OWN_ATTEMPT));resolve(request(true,Action.READ_OWN_ATTEMPT));
        for(boolean b:List.of(false,true)) {
            Request q=request(b,Action.READ_OWN_ATTEMPT),other=request(!b,Action.READ_OWN_ATTEMPT);allow(pupil,q);
            for(Request bad:List.of(
                changed(q,other.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),
                changed(q,q.institutionId(),other.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),
                changed(q,q.institutionId(),q.classId(),other.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),
                changed(q,q.institutionId(),q.classId(),q.evaluationId(),other.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()),
                changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),other.revisionId(),q.recipientAccountId(),q.attemptId()),
                changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),f.peerStudent.getId(),q.attemptId()),
                changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),other.attemptId())))deny(pupil,bad);
        }
    }
    @Test void adminNeedsDocenteAndAssignedClassWhileCurrentCoTeacherHasFullReviewFaculty() throws Exception {
        resolve(legacy(Action.SUPERVISE));allow(admin,legacy(Action.SUPERVISE));deny(admin,legacy(Action.GRADE_ATTEMPT));
        long am=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,c.school.getId());
        r.institutions.grantRole(am,DOCENTE,r.from.plusSeconds(20),null);resolve(legacy(Action.SUPERVISE));
        allow(admin,legacy(Action.SUPERVISE));deny(admin,legacy(Action.GRADE_ATTEMPT));
        var delegations=f.app.getBean(DelegationMaintenanceService.class);
        var co=delegations.planCoTeacher(r.institution,r.classId,am,r.from.plusSeconds(30),r.until,r.from.plusSeconds(21));
        delegations.transitionCoTeacher(co.id(),TeachingAssignment.Status.ACTIVE,r.from.plusSeconds(30));
        resolve(legacy(Action.SUPERVISE));allow(admin,legacy(Action.REVIEW_ATTEMPT));allow(admin,legacy(Action.GRADE_ATTEMPT));
        allow(teacher,legacy(Action.GRADE_ATTEMPT));
        delegations.transitionCoTeacher(co.id(),TeachingAssignment.Status.ENDED,Instant.now());
        deny(admin,legacy(Action.GRADE_ATTEMPT));
    }
    @Test void selfGradingAndClosedGradeNeverFollowCombinedRoles() throws Exception {
        r.institutions.grantRole(r.member,DOCENTE,r.from.plusSeconds(20),null);
        r.institutions.grantRole(r.member,ADMIN_INSTITUCION,r.from.plusSeconds(21),null);
        var delegation=f.app.getBean(DelegationMaintenanceService.class);
        var co=delegation.planCoTeacher(r.institution,r.classId,r.member,r.from.plusSeconds(30),r.until,r.from.plusSeconds(22));
        delegation.transitionCoTeacher(co.id(),TeachingAssignment.Status.ACTIVE,r.from.plusSeconds(30));
        resolve(legacy(Action.SUPERVISE));allow(pupil,legacy(Action.SUPERVISE));deny(pupil,legacy(Action.GRADE_ATTEMPT));deny(pupil,legacy(Action.REVIEW_ATTEMPT));
        allow(teacher,legacy(Action.GRADE_ATTEMPT));
        sql().update("update evaluation_attempts set status='GRADED',grade_closed=true where id=?",legacyAttempt);resolve(legacy(Action.SUPERVISE));
        deny(teacher,legacy(Action.GRADE_ATTEMPT));allow(teacher,legacy(Action.REVIEW_ATTEMPT));
    }
    @Test void libraryGrantCannotReplaceTeachingAssignment() throws Exception {
        var grant=f.app.getBean(DelegationMaintenanceService.class);
        long m=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,f.otherTeacher.getId());
        long adminMember=count("select id from institution_memberships where institution_id=? and account_id=?",r.institution,c.school.getId());
        Instant granted=Instant.now().minusSeconds(2);
        c.authenticate(c.school);
        try {grant.grantResource(r.institution,ResourceManagerGrant.Kind.EVALUATION,r.evaluation,m,ResourceManagerGrant.Faculty.PUBLISH,adminMember,
            granted.plusSeconds(1),r.until,granted);}finally{SecurityContextHolder.clearContext();}
        resolve(legacy(Action.SUPERVISE));deny(session(f.otherTeacher),legacy(Action.SUPERVISE));deny(session(f.otherTeacher),legacy(Action.GRADE_ATTEMPT));
    }
    @Test void localSuspensionThenTerminationLeavesIndependentInstitutionAuthorized() throws Exception {
        Request a=request(false,Action.READ_OWN_ATTEMPT),b=request(true,Action.READ_OWN_ATTEMPT);resolve(a);resolve(b);allow(pupil,a);allow(pupil,b);
        r.institutions.transitionMembership(r.member,InstitutionMembership.Status.SUSPENDED,Instant.now());deny(pupil,a);allow(pupil,b);
        r.institutions.transitionMembership(r.member,InstitutionMembership.Status.ENDED,Instant.now().plusMillis(10));deny(pupil,a);allow(pupil,b);
    }
    @Test void localRoleRevocationInEitherInstitutionPreservesTheOther() throws Exception {
        Request a=request(false,Action.READ_OWN_ATTEMPT),b=request(true,Action.READ_OWN_ATTEMPT);resolve(a);resolve(b);
        long g=count("select g.id from membership_role_grants g join institution_memberships m on m.id=g.membership_id where m.institution_id=? and m.account_id=? and g.role='ESTUDIANTE'",r.foreign,f.student.getId());
        r.institutions.revokeRole(g,Instant.now());deny(pupil,b);allow(pupil,a);
    }
    @Test void revokedExpiredFutureTeacherGrantsAndEndedAssignmentsDeny() throws Exception {
        resolve(legacy(Action.GRADE_ATTEMPT));allow(teacher,legacy(Action.GRADE_ATTEMPT));
        long g=count("select id from membership_role_grants where membership_id=? and role='DOCENTE'",r.authorMember);
        r.institutions.revokeRole(g,r.from.plusSeconds(20));resolve(legacy(Action.GRADE_ATTEMPT));deny(teacher,legacy(Action.GRADE_ATTEMPT));
        r.institutions.grantRole(r.authorMember,DOCENTE,r.from.plusSeconds(20),r.from.plusSeconds(40));resolve(legacy(Action.GRADE_ATTEMPT));deny(teacher,legacy(Action.GRADE_ATTEMPT));
        r.institutions.grantRole(r.authorMember,DOCENTE,Instant.now().plusSeconds(600),r.until);resolve(legacy(Action.GRADE_ATTEMPT));deny(teacher,legacy(Action.GRADE_ATTEMPT));
    }
    @Test void sessionRevocationVersionsExpiryTemporaryAndDisabledAccountsDenyBothInstitutions() throws Exception {
        Request a=request(false,Action.READ_OWN_ATTEMPT),b=request(true,Action.READ_OWN_ATTEMPT);resolve(a);resolve(b);
        deny(null,a);deny(pupil,null);allow(pupil,a);allow(pupil,b);
        deny(new SessionPrincipal(pupil.userId(),pupil.username(),pupil.sessionId(),pupil.credentialsVersion(),pupil.role(),true),a);
        deny(new SessionPrincipal(pupil.userId(),pupil.username(),pupil.sessionId(),pupil.credentialsVersion(),Role.ADMINISTRADOR,false),a);
        sql().update("update account_sessions set revoked_at=statement_timestamp() where id=?",pupil.sessionId());deny(pupil,a);deny(pupil,b);
        pupil=session(f.student);sql().update("update account_sessions set expires_at=statement_timestamp()-interval '1 second' where id=?",pupil.sessionId());deny(pupil,a);deny(pupil,b);
        pupil=session(f.student);f.mutate(f.student,UserAccount::invalidateSessions);deny(pupil,a);deny(pupil,b);
        pupil=session(f.student);f.mutate(f.student,u->u.setTemporaryPassword(true));deny(pupil,a);deny(pupil,b);
        f.mutate(f.student,u->u.setTemporaryPassword(false));pupil=session(f.student);f.mutate(f.student,u->u.setActive(false));deny(pupil,a);deny(pupil,b);
    }
    @Test void absentUnresolvedConflictAndStaleContextsNeverFallBackToB3() throws Exception {
        Request q=legacy(Action.READ_OWN_ATTEMPT);deny(pupil,q);
        resolve(q,"UNRESOLVED");deny(pupil,q);resolve(q,"CONFLICT");deny(pupil,q);resolve(q);allow(pupil,q);
        sql().update("update evaluations set title='Changed parent' where id=?",q.evaluationId());deny(pupil,q);
        resolve(q);allow(pupil,q);
    }
    @Test void publishedExactRevisionAndContractAreRequiredDespiteTheDraftTechnicalAnchor() throws Exception {
        Request q=request(false,Action.READ_OWN_ATTEMPT);resolve(q);allow(pupil,q);
        assertThat(sql().queryForObject("select status from evaluations where id=?",String.class,q.evaluationId())).isEqualTo("DRAFT");
        var draft=revisions.nextDraft(aRevision,op());resolve(q);
        deny(pupil,changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),draft.id(),q.recipientAccountId(),q.attemptId()));
        var published=revisions.publish(draft.id(),1,op());resolve(q);
        deny(pupil,changed(q,q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),published.id(),q.recipientAccountId(),q.attemptId()));
        deny(pupil,new Request(Contract.LEGACY,q.action(),q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId(),q.recipientAccountId(),q.attemptId()));
        assertThat(allow(pupil,q).context().revisionKind()).isEqualTo(RevisionKind.PUBLISHED_ACADEMIC);
    }
    @Test void legacyT05UsesAllCurrentAssignmentsIncludingFutureEmptyUndated() throws Exception {
        sql().update("update evaluation_attempts set status='GRADED',grade_closed=true where id=?",legacyAttempt);
        sql().update("update evaluation_assignments set due_at='2026-02-02 10:00:00' where id=?",r.assignment);
        Request q=legacy(Action.READ_CORRECTION);resolve(q);deny(pupil,q);
        sql().update("update evaluation_assignments set active=false where id=?",r.secondAssignment);resolve(q);deny(pupil,q);
        sql().update("update evaluation_assignments set active=false where id=?",r.thirdAssignment);resolve(q);allow(pupil,q);
        assertThat(allow(pupil,legacy(Action.READ_OWN_ATTEMPT)).context().revisionKind()).isEqualTo(RevisionKind.TECHNICAL_SNAPSHOT);
        sql().update("update evaluation_assignments set start_at='2026-12-01 00:00:00',due_at=null where id=?",r.assignment);resolve(q);deny(pupil,q);
    }
    @Test void newT05IgnoresIndividualClosureAndExhaustionWhileFutureOrUndatedEmptyDeliveryRemains() throws Exception {
        long rec=count("select id from t11b8_recipients where delivery_id=? and account_id=?",aDelivery,f.student.getId());
        for(int n=0;n<2;n++){long attempt=revisions.reserveFixtureAttempt(rec,op());revisions.finishFixtureAttempt(attempt,1,"{}",18,op());}
        assertThat(count("select count(*) from t11b8_fixture_attempts where resource_id=? and account_id=?",aResource,f.student.getId())).isEqualTo(3);
        var rev=c.b.b8.successor(c.b.published,"Future exact revision");
        long future=delivery(rev.id(),r.institution,r.otherClass,Instant.now().plusSeconds(100),Instant.now().plusSeconds(1000));
        long empty=r.clazz(r.institution,r.period,"EMPTY-ACCESS");
        long undated=delivery(aRevision,r.institution,empty,Instant.now().plusSeconds(100),null);
        revisions.closeDelivery(aDelivery,1,op());Request q=request(false,Action.READ_CORRECTION);resolve(q);deny(pupil,q);
        revisions.closeDelivery(future,1,op());resolve(q);deny(pupil,q);
        revisions.closeDelivery(undated,1,op());resolve(q);allow(pupil,q);
    }
    @Test void frozenBusinessTablesHistoryQuotaAuditAndSequencesRemainIdenticalAcrossDecisions() throws Exception {
        resolve(request(false,Action.READ_OWN_ATTEMPT));resolve(request(true,Action.READ_OWN_ATTEMPT));resolve(legacy(Action.READ_OWN_ATTEMPT));
        var before=c.state();
        var sequences=sql().queryForList("select sequencename,last_value from pg_sequences where schemaname=current_schema() order by sequencename");
        for(boolean b:List.of(false,true)) {
            var d=allow(pupil,request(b,Action.READ_OWN_ATTEMPT));deny(teacher,request(b,Action.READ_OWN_ATTEMPT));deny(pupil,request(b,Action.READ_CORRECTION));
            String result=d.toString();
            assertThat(result).doesNotContain("Synthetic criterion","score","payload","answers","budget","feedback");
        }
        allow(teacher,legacy(Action.GRADE_ATTEMPT));assertThat(c.state()).isEqualTo(before);
        assertThat(sql().queryForList("select sequencename,last_value from pg_sequences where schemaname=current_schema() order by sequencename")).isEqualTo(sequences);
    }
    @Test void persistenceFailureCannotProduceAContextOrBusinessSuccess() throws Exception {
        Request q=request(false,Action.READ_OWN_ATTEMPT);resolve(q);allow(pupil,q);var before=c.state();
        var broken=spy(sql());doAnswer(invocation->{sql().execute("select 1/0");return null;}).when(broken).execute("select t11b5_fixture()");
        var service=new EvaluationDeliveryAccessService(broken,f.app.getBean(AccountSessionService.class),f.app.getBean(PlatformTransactionManager.class));
        var decision=service.decide(pupil,q);assertThat(decision.outcome()).isEqualTo(Outcome.UNAVAILABLE);assertThat(decision.context()).isNull();
        assertThat(c.state()).isEqualTo(before);
    }
    @Test void latestB10CorrectionReplacesOldSchoolWithoutFallbackOrAnotherMembershipChoice() throws Exception {
        Request a=request(false,Action.READ_OWN_ATTEMPT);resolve(a);allow(pupil,a);
        Request corrected=changed(a,r.foreign,r.foreignClass,a.evaluationId(),a.deliveryId(),a.revisionId(),a.recipientAccountId(),a.attemptId());
        resolve(corrected);deny(pupil,a);allow(pupil,corrected);
        Request back=changed(corrected,r.institution,r.classId,a.evaluationId(),a.deliveryId(),a.revisionId(),a.recipientAccountId(),a.attemptId());
        resolve(back);deny(pupil,corrected);allow(pupil,back);
    }
    @Test void validRootNeverAuthorizesAnUnresolvedRecipientOrAttempt() throws Exception {
        Request q=request(false,Action.READ_OWN_ATTEMPT);
        resolve(q,"UNRESOLVED","t11b8_recipients");deny(pupil,q);
        resolve(q,"UNRESOLVED","t11b8_fixture_attempts");deny(pupil,q);
        resolve(q);allow(pupil,q);
    }
    @Test void explicitRootClosureReusesB8WithoutOpeningDeliveryContentOrIgnoringUnclosedGrades() throws Exception {
        Request content=request(false,Action.READ_DELIVERY);resolve(content);
        assertThat(allow(pupil,content).context().projection()).isEqualTo(Projection.DELIVERY_WITHOUT_CORRECTION);
        assertThat(allow(admin,content).context().projection()).isEqualTo(Projection.SUPERVISION_METADATA);
        long rec=count("select id from t11b8_recipients where delivery_id=? and account_id=?",aDelivery,f.student.getId());
        long active=revisions.reserveFixtureAttempt(rec,op());
        revisions.resourceState(aResource,count("select version from t11b8_resources where id=?",aResource),"AVAILABLE",true,false,op());
        resolve(content);deny(pupil,content);deny(pupil,request(false,Action.READ_CORRECTION));
        revisions.finishFixtureAttempt(active,1,"{}",18,op());resolve(content);allow(pupil,request(false,Action.READ_CORRECTION));
    }
    @Test void closedPeriodCutsOnlyItsInstitutionEvenWhenRowsStillSayActive() throws Exception {
        Request a=request(false,Action.READ_OWN_ATTEMPT),b=request(true,Action.READ_OWN_ATTEMPT);resolve(a);resolve(b);allow(pupil,a);allow(pupil,b);
        r.academic.transitionPeriod(r.period,AcademicPeriod.Status.CLOSED,Instant.now());deny(pupil,a);allow(pupil,b);
    }
    @Test void suspendedEnrollmentIsNotRestoredByAPreviousRecipient() throws Exception {
        Request a=request(false,Action.READ_OWN_ATTEMPT),b=request(true,Action.READ_OWN_ATTEMPT);resolve(a);resolve(b);allow(pupil,a);allow(pupil,b);
        r.academic.transitionEnrollment(r.episode,Enrollment.Status.SUSPENDED,Instant.now());deny(pupil,a);allow(pupil,b);
    }
    @Test void freshNewContextCannotAdoptAnOriginalWriterOrPreparedBudget() throws Exception {
        Request q=request(false,Action.READ_OWN_ATTEMPT);resolve(q);allow(pupil,q);
        var profile=f.app.getBean(com.morales.chemicallab.repository.TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        r.assignment(q.evaluationId(),profile,LocalDateTime.of(2026,12,1,0,0));resolve(q);deny(pupil,q);
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",q.evaluationId())).isZero();
    }
    @Test void scheduledDeliveryOnlyAllowsSupervisionWithoutQuestionsOrCorrections() throws Exception {
        var rev=c.b.b8.successor(c.b.published,"Scheduled academic revision");
        long delivery=delivery(rev.id(),r.institution,r.otherClass,Instant.now().plusSeconds(600),null);
        revisions.recipient(delivery,f.student.getId(),r.otherEpisode,0,true,op());
        Request q=new Request(Contract.NEW,Action.READ_DELIVERY,r.institution,r.otherClass,request(false,Action.READ_DELIVERY).evaluationId(),delivery,rev.id(),f.student.getId(),null);
        resolve(q);deny(pupil,q);assertThat(allow(admin,q).context().projection()).isEqualTo(Projection.SUPERVISION_METADATA);
    }
}

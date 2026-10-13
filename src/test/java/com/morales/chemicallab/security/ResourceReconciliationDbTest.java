package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.service.ResourceReconciliationService.SourceKind.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourceReconciliationDbTest {
    final WhiteboardSecurityTransportDbTest f=new WhiteboardSecurityTransportDbTest();
    ResourceReconciliationService service;
    AcademicMaintenanceService academic;
    InstitutionMaintenanceService institutions;
    String expansion,predecessorExpansion,b1,b2,b2v2,oldB2,oldAudit,oldB3;
    final Instant from=Instant.parse("2026-01-01T00:00:00Z"),until=Instant.parse("2027-01-01T00:00:00Z");
    long institution,foreign,period,classId,otherClass,foreignClass,member,authorMember,episode,otherEpisode,evaluation,assignment,secondAssignment,thirdAssignment;
    int sequence;
    @BeforeAll void start() throws Exception {
        f.start();service=f.app.getBean(ResourceReconciliationService.class);academic=f.app.getBean(AcademicMaintenanceService.class);institutions=f.app.getBean(InstitutionMaintenanceService.class);
        b1=script("t11b1-v1");b2=script("t11b2-v1");b2v2=script("t11b2-v2");predecessorExpansion=script("t11b3-v1");expansion=script("t11b3-v2");
        // Genuinely closed CHECK from before B1. Hibernate never runs after this preparation.
        for(String n:f.sql().queryForList("select conname from pg_constraint where conrelid='system_logs'::regclass and contype='c' and pg_get_constraintdef(oid) like '%event_type%'",String.class))f.sql().execute("alter table system_logs drop constraint "+n);
        String legacy=Arrays.stream(LogEventType.values()).map(Enum::name).filter(n->!n.startsWith("RESOURCE_")&&!n.startsWith("INSTITUTION_")&&!n.startsWith("MEMBERSHIP_")&&!n.startsWith("ACADEMIC_")&&!n.startsWith("TEACHING_ASSIGNMENT_")&&!n.startsWith("ENROLLMENT_")).map(n->"'"+n+"'").collect(java.util.stream.Collectors.joining(","));
        f.sql().execute("alter table system_logs add constraint pre_b1_event_check check(event_type in ("+legacy+"))");
        f.sql().execute(b1);f.sql().execute(b2);f.sql().execute(b2v2);
        oldB2=f.sql().queryForObject("select fingerprint from t11b2_schema_version",String.class);
        oldAudit=f.sql().queryForObject("select fingerprint from t11b2_audit_version",String.class);
        var tx=new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s->{
            f.sql().execute("alter table academic_classes disable trigger t11b2_guard");
            assertThatThrownBy(()->f.sql().execute(predecessorExpansion)).isInstanceOf(DataAccessException.class);
            s.setRollbackOnly();
        });
        assertThat(f.sql().queryForObject("select to_regclass(current_schema()||'.t11b3_schema_version') is null",Boolean.class)).isTrue();
        assertThatThrownBy(()->f.sql().execute(expansion)).isInstanceOf(DataAccessException.class);
        f.sql().execute(predecessorExpansion);f.sql().execute(predecessorExpansion);
        oldB3=f.sql().queryForObject("select fingerprint from t11b3_schema_version",String.class);
        String rows= f.sql().queryForObject(signature("t11b3_schema_version"),String.class);
        tx.executeWithoutResult(s->{
            f.sql().execute("alter table t11b3_attempt_consumptions disable trigger t11b3_immutable");
            assertThatThrownBy(()->f.sql().execute(expansion)).isInstanceOf(DataAccessException.class);
            s.setRollbackOnly();
        });
        assertThat(f.sql().queryForObject("select to_regclass(current_schema()||'.t11b3_apply_version') is null",Boolean.class)).isTrue();
        // A failed/unknown commit can safely retry v2; its predecessor row is never rewritten.
        tx.executeWithoutResult(s->{f.sql().execute(expansion);s.setRollbackOnly();});
        f.sql().execute(predecessorExpansion);
        f.sql().execute(expansion);f.sql().execute(expansion);
        assertThat(f.sql().queryForObject(signature("t11b3_schema_version"),String.class)).isEqualTo(rows);
        assertThat(f.sql().queryForObject("select predecessor_fingerprint from t11b3_apply_version",String.class)).isEqualTo(oldB3);
    }
    String script(String name) throws Exception {return new ClassPathResource("db/expansion/"+name+".sql").getContentAsString(StandardCharsets.UTF_8);}
    @BeforeEach void fixture() {
        // Fixture creates no audit types outside the pre-B1 allowlist.
        f.fixture();sequence++;
        institution=institutions.createInstitution("B3-A-"+sequence,"Fictitious A","America/Lima").id();
        foreign=institutions.createInstitution("B3-B-"+sequence,"Fictitious B","America/Lima").id();
        institutions.changeInstitutionStatus(institution,Institution.Status.ACTIVE);institutions.changeInstitutionStatus(foreign,Institution.Status.ACTIVE);
        period=period(institution);classId=clazz(institution,period,"CHEM");otherClass=clazz(institution,period,"PHYS");foreignClass=clazz(foreign,period(foreign),"CHEM");
        member=membership(institution,f.student.getId(),MembershipRoleGrant.InstitutionalRole.ESTUDIANTE);
        authorMember=membership(institution,f.teacher.getId(),MembershipRoleGrant.InstitutionalRole.DOCENTE);
        episode=enroll(classId);otherEpisode=enroll(otherClass);
        var teacher=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        evaluation=f.app.getBean(EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Fictitious E").status(EvaluationStatus.PUBLISHED).maxAttempts(3).timeLimitMinutes(30).createdByTeacher(teacher).build()).getId();
        assignment=assignment(evaluation,teacher,LocalDateTime.of(2026,12,1,0,0));
        secondAssignment=assignment(evaluation,teacher,null);thirdAssignment=assignment(evaluation,teacher,LocalDateTime.of(2026,12,2,0,0));
        f.sql().update("update evaluation_assignments set grade='4',section='B' where id=?",secondAssignment);
        f.sql().update("update evaluation_assignments set grade='5',section='C' where id=?",thirdAssignment);
    }
    @AfterEach void close(){f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    long period(long i){var p=academic.createPeriod(i,"2026","Fictitious period",from,until,from);academic.transitionPeriod(p.id(),AcademicPeriod.Status.OPEN,from.plusSeconds(1));return p.id();}
    long clazz(long i,long p,String code){var c=academic.createClass(i,p,code,"Fictitious","Chemistry","3","A",from);academic.transitionClass(c.id(),AcademicClass.Status.ACTIVE,from.plusSeconds(2));return c.id();}
    long membership(long i,long account,MembershipRoleGrant.InstitutionalRole role){var m=institutions.createMembership(i,account,from);institutions.transitionMembership(m.id(),InstitutionMembership.Status.ACTIVE,from.plusSeconds(1));institutions.grantRole(m.id(),role,from.plusSeconds(2),null);return m.id();}
    long enroll(long c){var e=academic.createEnrollment(institution,c,member,from.plusSeconds(10),until,from.plusSeconds(3));academic.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,from.plusSeconds(10));return e.id();}
    long assignment(long e,TeacherProfile teacher,LocalDateTime due){return f.app.getBean(EvaluationAssignmentRepository.class).saveAndFlush(EvaluationAssignment.builder().evaluation(f.app.getBean(EvaluationRepository.class).findById(e).orElseThrow()).teacher(teacher).grade("3").section("A").startAt(LocalDateTime.of(2026,1,1,0,0)).dueAt(due).build()).getId();}
    long attempt(int number,AttemptStatus status,Long a,boolean active) {
        return f.app.getBean(EvaluationAttemptRepository.class).saveAndFlush(EvaluationAttempt.builder().evaluation(f.app.getBean(EvaluationRepository.class).findById(evaluation).orElseThrow())
            .student(f.app.getBean(StudentProfileRepository.class).findByUser_Id(f.student.getId()).orElseThrow()).assignment(a==null?null:f.app.getBean(EvaluationAssignmentRepository.class).findById(a).orElseThrow())
            .attemptNumber(number).status(status).active(active).startedAt(LocalDateTime.of(2026,2,1,10,0)).questionOrder("17,23").build()).getId();
    }
    String map(List<Map<String,Object>> deliveries,List<Map<String,Object>> contexts) throws Exception {return f.json.writeValueAsString(Map.of("institutionId",institution,"authorAccountId",f.teacher.getId(),"authorMembershipId",authorMember,"deliveries",deliveries,"contexts",contexts));}
    Map<String,Object> delivery(long a,long c,long e){return Map.of("assignmentId",a,"classId",c,"recipients",List.of(Map.of("accountId",f.student.getId(),"enrollmentId",e,"evidence","FIXTURE-ROSTER")));}
    String standardMap() throws Exception {return map(List.of(delivery(assignment,classId,episode),delivery(secondAssignment,otherClass,otherEpisode)),List.of());}
    long approved(ResourceReconciliationService.SourceKind k,long id,String map){long entry=service.propose(k,id,map);approve(entry);return entry;}
    void approve(long id){service.approve(id,f.sql().queryForObject("select md5(source_checksum||proposal_checksum) from t11b3_reconciliations where id=?",String.class,id),"FIXTURE-REVIEW");}
    long count(String sql,Object... args){return f.sql().queryForObject(sql,Long.class,args);}
    Map<String,Object> budget(){return f.sql().queryForMap("select b.* from t11b3_budget_observations b where evaluation_id=? and account_id=?",evaluation,f.student.getId());}
    @Test void exhaustedInactiveAndUnassignedAttemptsReserveOneBudgetA13() throws Exception {
        f.sql().update("update evaluations set max_attempts=2 where id=?",evaluation);
        long one=attempt(1,AttemptStatus.GRADED,assignment,true),two=attempt(2,AttemptStatus.GRADED,null,false);
        String source=f.sql().queryForObject("select t11b3_snapshot('EVALUATION',?)::text",String.class,evaluation);
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        assertThat(budget()).containsEntry("used_count",2L).containsEntry("next_number",3L).containsEntry("reserved_remaining",0L).containsEntry("admission_enabled",false);
        assertThat(count("select count(*) from t11b3_attempt_consumptions where attempt_id in (?,?)",one,two)).isEqualTo(2);
        assertThat(count("select count(*) from t11b3_attempt_contexts where attempt_id=?",two)).isZero();
        assertThat(service.propose(EVALUATION,evaluation,standardMap())).isEqualTo(entry);assertThat(service.apply(entry)).isEqualTo("APPLIED");
        assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",evaluation)).isEqualTo(1);
        assertThat(f.sql().queryForObject("select t11b3_snapshot('EVALUATION',?)::text",String.class,evaluation)).isEqualTo(source);
        assertThatThrownBy(()->f.app.getBean(EvaluationService.class).startAttempt(f.student.getUsername(),evaluation,null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("máximo");
    }
    @Test void multipleAssignmentsAndTechnicalRevisionsShareRootA14() throws Exception {
        attempt(1,AttemptStatus.GRADED,assignment,true);attempt(2,AttemptStatus.GRADED,secondAssignment,true);
        long emptyClass=clazz(institution,period,"EMPTY");
        var third=Map.<String,Object>of("assignmentId",thirdAssignment,"classId",emptyClass,"recipients",List.of());
        long entry=approved(EVALUATION,evaluation,map(List.of(delivery(assignment,classId,episode),delivery(secondAssignment,otherClass,otherEpisode),third),List.of()));
        assertThat(service.apply(entry)).isEqualTo("APPLIED");
        long revision=f.sql().queryForObject("select id from t11b3_evaluation_revisions where evaluation_id=?",Long.class,evaluation);
        service.technicalRevision(EVALUATION,revision);
        assertThat(count("select count(*) from t11b3_evaluation_revisions where evaluation_id=?",evaluation)).isEqualTo(2);
        assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",evaluation)).isEqualTo(1);
        assertThat(budget()).containsEntry("used_count",2L).containsEntry("next_number",3L);
        // Legacy remains authority; one legitimate remaining start cannot refresh prepared snapshots silently.
        var started=f.app.getBean(EvaluationService.class).startAttempt(f.student.getUsername(),evaluation,null);
        assertThat(started.attemptNumber()).isEqualTo(3);
        assertThatThrownBy(()->f.app.getBean(EvaluationService.class).startAttempt(f.student.getUsername(),evaluation,null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(budget()).containsEntry("source_current",false).containsEntry("admission_enabled",false);
        assertThat(service.apply(entry)).isEqualTo("STALE");
    }
    @Test void previousActiveContextIsFixedAtStartAndUnassignedHasNoInventedEpisodeA15() throws Exception {
        long id=attempt(1,AttemptStatus.IN_PROGRESS,assignment,true);
        var ctx=Map.<String,Object>of("attemptId",id,"accountId",f.student.getId(),"enrollmentId",episode,"sourceTimezone","America/Lima","evidence","FIXTURE-START");
        long entry=approved(EVALUATION,evaluation,map(List.of(delivery(assignment,classId,episode)),List.of(ctx)));
        assertThat(service.apply(entry)).isEqualTo("APPLIED");assertThat(budget()).containsEntry("active_attempt_id",id).containsEntry("used_count",1L);
        assertThat(count("select count(*) from t11b3_attempt_contexts where attempt_id=? and enrollment_id=?",id,episode)).isEqualTo(1);
        assertThatThrownBy(()->f.sql().update("update t11b3_attempt_contexts set enrollment_id=? where attempt_id=?",otherEpisode,id)).isInstanceOf(DataAccessException.class);
        f.sql().update("update evaluation_attempts set status='GRADED' where id=?",id);
        assertThat(budget()).containsEntry("source_current",false).containsEntry("used_count",1L);
    }
    @Test void readmissionDoesNotDuplicateRecipientsOrRefundA16() throws Exception {
        attempt(1,AttemptStatus.GRADED,assignment,true);attempt(2,AttemptStatus.GRADED,secondAssignment,true);
        academic.transitionEnrollment(episode,Enrollment.Status.WITHDRAWN,Instant.parse("2026-03-01T00:00:00Z"));
        var next=academic.createEnrollment(institution,classId,member,Instant.parse("2026-04-01T00:00:00Z"),until,Instant.parse("2026-03-02T00:00:00Z"));
        academic.transitionEnrollment(next.id(),Enrollment.Status.ACTIVE,Instant.parse("2026-04-01T00:00:00Z"));
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        long d=f.sql().queryForObject("select id from t11b3_evaluation_deliveries where legacy_assignment_id=?",Long.class,assignment);
        assertThatThrownBy(()->f.sql().update("insert into t11b3_evaluation_recipients(delivery_id,institution_id,class_id,account_id,enrollment_id,evidence) values(?,?,?,?,?,'FIXTURE-READMISSION')",d,institution,classId,f.student.getId(),next.id())).isInstanceOf(DataAccessException.class);
        assertThat(budget()).containsEntry("used_count",2L).containsEntry("next_number",3L);
    }
    @Test void numberingConflictsAndTwoActivesRemainReservedA17() throws Exception {
        attempt(1,AttemptStatus.IN_PROGRESS,assignment,true);attempt(1,AttemptStatus.IN_PROGRESS,null,false);
        String before=f.sql().queryForObject("select t11b3_snapshot('EVALUATION',?)::text",String.class,evaluation);
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("CONFLICT");
        assertThat(budget()).containsEntry("used_count",2L).containsEntry("status","CONFLICT").containsEntry("next_number",null).containsEntry("active_attempt_id",null);
        assertThat(count("select count(*) from t11b3_attempt_consumptions where reconciliation_id=?",entry)).isEqualTo(2);
        assertThat(count("select count(*) from t11b3_evaluation_resources where evaluation_id=?",evaluation)).isZero();
        assertThat(f.sql().queryForObject("select t11b3_snapshot('EVALUATION',?)::text",String.class,evaluation)).isEqualTo(before);
    }
    @Test void crossInstitutionAndWrongAuthorMapsBecomeConflictAndSqlRejectsCrossReferences() throws Exception {
        long entry=approved(EVALUATION,evaluation,map(List.of(delivery(assignment,foreignClass,episode)),List.of()));
        assertThat(service.apply(entry)).isEqualTo("CONFLICT");
        long concept=f.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder().title("Fictitious C").category("Test").createdByTeacher(f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow()).build()).getId();
        long cEntry=approved(CONCEPT,concept,map(List.of(),List.of()));assertThat(service.apply(cEntry)).isEqualTo("APPLIED");
        long rev=f.sql().queryForObject("select id from t11b3_concept_revisions where concept_id=?",Long.class,concept);
        assertThatThrownBy(()->f.sql().update("insert into t11b3_concept_revisions(concept_id,institution_id,revision_number,payload,payload_checksum,reconciliation_id) select concept_id,?,2,payload,payload_checksum,reconciliation_id from t11b3_concept_revisions where id=?",foreign,rev)).isInstanceOf(DataAccessException.class);
    }
    @Test void frozenEvaluationContentSurvivesMutableSourceAndDriftIsDetected() throws Exception {
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        String payload=f.sql().queryForObject("select payload::text from t11b3_evaluation_revisions where evaluation_id=?",String.class,evaluation);
        f.sql().update("update evaluations set title='New title',time_limit_minutes=10 where id=?",evaluation);
        assertThat(f.sql().queryForObject("select payload::text from t11b3_evaluation_revisions where evaluation_id=?",String.class,evaluation)).isEqualTo(payload);
        assertThat(service.apply(entry)).isEqualTo("STALE");
        long newEntry=approved(EVALUATION,evaluation,standardMap());assertThat(newEntry).isNotEqualTo(entry);assertThat(service.apply(newEntry)).isEqualTo("CONFLICT");
    }
    @Test void sourceChangeBetweenApprovalAndApplicationPreservesStaleEvidence() throws Exception {
        long entry=approved(EVALUATION,evaluation,standardMap());
        attempt(1,AttemptStatus.GRADED,null,false);
        assertThat(service.apply(entry)).isEqualTo("STALE");assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",evaluation)).isZero();
        assertThat(persisted(entry)).isEqualTo("STALE");
        assertThat(service.apply(entry)).isEqualTo(persisted(entry));
        assertThat(count("select count(*) from t11b3_reconciliation_events where reconciliation_id=? and status='STALE'",entry)).isEqualTo(1);
        assertThat(count("select count(*) from system_logs where target_id=? and event_type='RESOURCE_RECONCILIATION_STALE' and outcome='SUCCESS'",entry)).isEqualTo(1);
    }
    String persisted(long entry){return f.sql().queryForObject("select status from t11b3_reconciliations where id=?",String.class,entry);}
    Map<String,String> reconciliationData() {
        Map<String,String> result=new LinkedHashMap<>();
        for(String table:f.sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' and (relname like 't11b3_%' or relname in ('system_logs','storage_usage')) order by relname",String.class))
            result.put(table,f.sql().queryForObject(signature(table),String.class));
        return result;
    }
    void rejectPending(boolean changed) throws Exception {
        long entry=service.propose(EVALUATION,evaluation,standardMap());
        if(changed)f.sql().update("update evaluations set title='Changed fictitious source' where id=?",evaluation);
        var before=reconciliationData();
        for(int retry=0;retry<2;retry++) {
            assertThatThrownBy(()->service.apply(entry)).isInstanceOf(DataAccessException.class).hasMessageContaining("Explicit fixture approval required");
            assertThat(persisted(entry)).isEqualTo("PROPOSED");assertThat(reconciliationData()).isEqualTo(before);
            // The SQL contract also rejects callers that bypass the Java service.
            assertThatThrownBy(()->f.sql().queryForObject("select t11b3_apply(?,null)",String.class,entry)).isInstanceOf(DataAccessException.class).hasMessageContaining("Explicit fixture approval required");
            assertThat(reconciliationData()).isEqualTo(before);
        }
        assertThat(count("select count(*) from system_logs where target_id=? and event_type in ('RESOURCE_RECONCILIATION_STALE','RESOURCE_RECONCILIATION_APPLIED','RESOURCE_RECONCILIATION_CONFLICT')",entry)).isZero();
    }
    @Test void proposedIntactSourceRejectsApplyAndRetriesWithoutMutation() throws Exception {rejectPending(false);}
    @Test void proposedChangedSourceRejectsApplyAndRetriesWithoutMutation() throws Exception {rejectPending(true);}
    @Test void staleAuditFailureRollsBackStatusHistoryAndQuotaThenRetryCommitsOnce() throws Exception {
        long entry=approved(EVALUATION,evaluation,standardMap());
        f.sql().update("update evaluations set title='Changed fictitious source' where id=?",evaluation);
        var before=reconciliationData();
        f.sql().execute("alter table system_logs rename to temporarily_unavailable_logs");
        try {assertThatThrownBy(()->service.apply(entry)).isInstanceOf(com.morales.chemicallab.security.audit.AuditUnavailableException.class);}
        finally {f.sql().execute("alter table temporarily_unavailable_logs rename to system_logs");}
        assertThat(persisted(entry)).isEqualTo("APPROVED");assertThat(reconciliationData()).isEqualTo(before);
        assertThat(service.apply(entry)).isEqualTo(persisted(entry));assertThat(persisted(entry)).isEqualTo("STALE");
        var committed=reconciliationData();assertThat(service.apply(entry)).isEqualTo("STALE");assertThat(reconciliationData()).isEqualTo(committed);
        assertThat(count("select count(*) from t11b3_reconciliation_events where reconciliation_id=? and status='STALE'",entry)).isEqualTo(1);
        assertThat(count("select count(*) from system_logs where target_id=? and event_type='RESOURCE_RECONCILIATION_STALE' and outcome='SUCCESS'",entry)).isEqualTo(1);
        assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",evaluation)).isZero();
    }
    @Test void inconsistentSqlReturnIsRejectedWithoutFictitiousAudit() throws Exception {
        long entry=approved(EVALUATION,evaluation,standardMap());var before=reconciliationData();
        var tx=new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s->{
            f.sql().execute("create or replace function t11b3_apply(entry bigint,actor bigint default null) returns text language sql as $$ select 'STALE'::text $$");
            assertThatThrownBy(()->service.apply(entry)).isInstanceOf(IllegalStateException.class).hasMessageContaining("persisted status");
            s.setRollbackOnly();
        });
        assertThat(persisted(entry)).isEqualTo("APPROVED");assertThat(reconciliationData()).isEqualTo(before);
        f.sql().execute(expansion);
    }
    @Test void failedQuotaAndRequiredAuditRollBackBudgetLedgerHistoryTogether() throws Exception {
        attempt(1,AttemptStatus.GRADED,assignment,true);long entry=approved(EVALUATION,evaluation,standardMap());
        long events=count("select count(*) from t11b3_reconciliation_events where reconciliation_id=?",entry),usage=count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='REVISION_BYTES'",f.teacher.getId());
        f.sql().update("update storage_limits set maximum=1 where resource='REVISION_BYTES'");
        try {assertThatThrownBy(()->service.apply(entry)).isInstanceOf(DataAccessException.class);} finally {f.sql().update("update storage_limits set maximum=268435456 where resource='REVISION_BYTES'");}
        assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",evaluation)).isZero();
        f.sql().execute("alter table system_logs rename to temporarily_unavailable_logs");
        try {assertThatThrownBy(()->service.apply(entry)).isInstanceOf(com.morales.chemicallab.security.audit.AuditUnavailableException.class);} finally {f.sql().execute("alter table temporarily_unavailable_logs rename to system_logs");}
        assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",evaluation)).isZero();
        assertThat(count("select count(*) from t11b3_reconciliation_events where reconciliation_id=?",entry)).isEqualTo(events);
        assertThat(count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='REVISION_BYTES'",f.teacher.getId())).isEqualTo(usage);
        assertThat(service.apply(entry)).isEqualTo("APPLIED");
    }
    @Test void interruptionRollbackAndResumeIsIdempotent() throws Exception {
        attempt(1,AttemptStatus.GRADED,null,false);long entry=approved(EVALUATION,evaluation,standardMap());
        var tx=new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s->{assertThat(service.apply(entry)).isEqualTo("APPLIED");s.setRollbackOnly();});
        assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",evaluation)).isZero();
        assertThat(service.apply(entry)).isEqualTo("APPLIED");assertThat(service.apply(entry)).isEqualTo("APPLIED");
        assertThat(count("select count(*) from system_logs where event_type='RESOURCE_RECONCILIATION_APPLIED' and target_id=?",entry)).isEqualTo(1);
    }
    @Test void originalT05ClosureCountsFutureEmptyAndNoDeadlineAssignments() {
        String query="select t11b3_review_closed(to_jsonb(e),(select jsonb_agg(to_jsonb(a)) from evaluation_assignments a where evaluation_id=e.id),?::timestamp) from evaluations e where id=?";
        assertThat(f.sql().queryForObject(query,Boolean.class,"2026-12-03",evaluation)).isFalse(); // no dueAt remains blocking
        f.sql().update("update evaluation_assignments set active=false where id=?",secondAssignment);
        assertThat(f.sql().queryForObject(query,Boolean.class,"2026-12-01",evaluation)).isFalse(); // future empty original still counts
        assertThat(f.sql().queryForObject(query,Boolean.class,"2026-12-03",evaluation)).isTrue();
        f.sql().update("update evaluations set status='ARCHIVED' where id=?",evaluation);
        assertThat(f.sql().queryForObject(query,Boolean.class,"2026-02-01",evaluation)).isTrue();
    }
    @Test void terminalOriginalClosureSurvivesDeliveriesAndTechnicalCopy() throws Exception {
        attempt(1,AttemptStatus.GRADED,assignment,true);f.sql().update("update evaluations set status='ARCHIVED' where id=?",evaluation);
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        long rev=f.sql().queryForObject("select id from t11b3_evaluation_revisions where evaluation_id=?",Long.class,evaluation);service.technicalRevision(EVALUATION,rev);
        assertThat(budget()).containsEntry("opportunities_closed",true).containsEntry("correction_exposure_enabled",false).containsEntry("admission_enabled",false);
    }
    @Test void allB1B2B3AuditTypesWorkFromPreB1CheckWithoutHibernate() {
        var audit=f.app.getBean(AuditLogService.class);var tx=new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        var events=Arrays.stream(LogEventType.values()).filter(e->e.name().startsWith("RESOURCE_")||e.name().startsWith("INSTITUTION_")||e.name().startsWith("MEMBERSHIP_")||e.name().startsWith("ACADEMIC_")||e.name().startsWith("TEACHING_ASSIGNMENT_")||e.name().startsWith("ENROLLMENT_")).toList();
        tx.executeWithoutResult(s->{for(var e:events)audit.recordInfo(e,"ResourceReconciliation",999999L,null,null,null,null);});
        assertThat(count("select count(*) from system_logs where target_id=999999 and outcome='SUCCESS'")).isEqualTo(20);
        assertThatThrownBy(()->f.sql().update("insert into system_logs(event_type,category,severity,created_at,outcome,correlation_id) values('UNKNOWN','ADMIN','INFO',localtimestamp,'SUCCESS',gen_random_uuid())")).isInstanceOf(DataAccessException.class);
    }
    @Test void explicitExpansionRepeatsPreservesPredecessorsAndRejectsDrift() {
        f.sql().execute(b1);f.sql().execute(expansion);
        assertThat(f.sql().queryForObject("select fingerprint from t11b2_schema_version",String.class)).isEqualTo(oldB2);
        assertThat(f.sql().queryForObject("select fingerprint from t11b2_audit_version",String.class)).isEqualTo(oldAudit);
        assertThat(f.sql().queryForObject("select fingerprint from t11b3_schema_version",String.class)).isEqualTo(oldB3);
        assertThatThrownBy(()->f.sql().execute(predecessorExpansion)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->f.sql().execute(b2v2)).isInstanceOf(DataAccessException.class);
        f.sql().execute("alter table t11b3_attempt_consumptions disable trigger t11b3_immutable");
        try {assertThatThrownBy(()->f.sql().execute(expansion)).isInstanceOf(DataAccessException.class);} finally {f.sql().execute("alter table t11b3_attempt_consumptions enable trigger t11b3_immutable");}
        f.sql().execute(expansion);
        var tx=new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s->{
            String definition=f.sql().queryForObject("select pg_get_functiondef('t11b3_apply(bigint,bigint)'::regprocedure)",String.class);
            f.sql().execute(definition.replace("Explicit fixture approval required","Altered fixture approval required"));
            assertThatThrownBy(()->f.sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");
            s.setRollbackOnly();
        });
        f.sql().execute(expansion);
    }
    @Test void approvalChecksumIsExactAndPendingCannotApply() throws Exception {
        long entry=service.propose(EVALUATION,evaluation,standardMap());
        assertThatThrownBy(()->service.apply(entry)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.approve(entry,"wrong","FIXTURE-REVIEW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->approveWithInvalidEvidence(entry)).isInstanceOf(DataAccessException.class);
        approve(entry);assertThat(service.apply(entry)).isEqualTo("APPLIED");
    }
    @Test void sqlCannotApproveNullEvidenceOrDigest() throws Exception {
        long entry=service.propose(EVALUATION,evaluation,standardMap());
        assertThatThrownBy(()->f.sql().update("update t11b3_reconciliations set status='APPROVED' where id=?",entry)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->f.sql().update("update t11b3_reconciliations set status='APPROVED',approval_evidence='FIXTURE-REVIEW' where id=?",entry)).isInstanceOf(DataAccessException.class);
        assertThat(f.sql().queryForObject("select status from t11b3_reconciliations where id=?",String.class,entry)).isEqualTo("PROPOSED");
        approve(entry);assertThat(service.apply(entry)).isEqualTo("APPLIED");
    }
    void approveWithInvalidEvidence(long entry){service.approve(entry,f.sql().queryForObject("select md5(source_checksum||proposal_checksum) from t11b3_reconciliations where id=?",String.class,entry),"real approval not authorized");}

    @Test void unassignedActiveAndGappedNumberArePreserved() throws Exception {
        long id=attempt(2,AttemptStatus.IN_PROGRESS,null,false);long entry=approved(EVALUATION,evaluation,standardMap());
        assertThat(service.apply(entry)).isEqualTo("CONFLICT");assertThat(budget()).containsEntry("used_count",1L).containsEntry("next_number",null);
        assertThat(count("select count(*) from t11b3_attempt_consumptions where attempt_id=? and attempt_number=2",id)).isEqualTo(1);
        assertThat(count("select count(*) from t11b3_attempt_contexts where attempt_id=?",id)).isZero();
    }
    @Test void consistentUnassignedActiveIsReservedWithoutInventingContext() throws Exception {
        long id=attempt(1,AttemptStatus.IN_PROGRESS,null,false);long entry=approved(EVALUATION,evaluation,standardMap());
        assertThat(service.apply(entry)).isEqualTo("APPLIED");assertThat(budget()).containsEntry("used_count",1L).containsEntry("active_attempt_id",id);
        assertThat(count("select count(*) from t11b3_attempt_contexts where attempt_id=?",id)).isZero();
    }
    @Test void contextForWrongClassOrEpisodeRollsBackAssociationIntoConflict() throws Exception {
        long id=attempt(1,AttemptStatus.GRADED,assignment,true);
        var ctx=Map.<String,Object>of("attemptId",id,"accountId",f.student.getId(),"enrollmentId",otherEpisode,"sourceTimezone","America/Lima","evidence","FIXTURE-WRONG");
        long entry=approved(EVALUATION,evaluation,map(List.of(delivery(assignment,classId,episode)),List.of(ctx)));
        assertThat(service.apply(entry)).isEqualTo("CONFLICT");assertThat(count("select count(*) from t11b3_attempt_contexts where attempt_id=?",id)).isZero();
        assertThat(budget()).containsEntry("used_count",1L).containsEntry("reconciliation_status","CONFLICT");
    }
    @Test void changedHistoricalEnrollmentInvalidatesApprovedMap() throws Exception {
        long entry=approved(EVALUATION,evaluation,standardMap());
        academic.transitionEnrollment(episode,Enrollment.Status.WITHDRAWN,Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(service.apply(entry)).isEqualTo("STALE");
    }
    @Test void zeroConsumptionRecipientHasOneReservedBudgetAndIndependentOriginStaysSeparate() throws Exception {
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        assertThat(budget()).containsEntry("used_count",0L).containsEntry("next_number",1L);
        var t=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        long independent=f.app.getBean(EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Fictitious E").status(EvaluationStatus.PUBLISHED).maxAttempts(3).createdByTeacher(t).build()).getId();
        long separate=approved(EVALUATION,independent,map(List.of(),List.of()));assertThat(service.apply(separate)).isEqualTo("APPLIED");
        assertThat(f.app.getBean(ResourceReconciliationRepository.class).legacyGroup(evaluation).id()).isNotEqualTo(f.app.getBean(ResourceReconciliationRepository.class).legacyGroup(independent).id());
    }
    @Test void duplicatedProfilesArePreservedInConflictWithoutMerging() throws Exception {
        // Reproduce contradictory historical schema only in this owned disposable fixture.
        String constraint=f.sql().queryForObject("select c.conname from pg_constraint c join pg_attribute a on a.attrelid=c.conrelid and a.attname='user_id' and a.attnum=any(c.conkey) where c.conrelid='student_profiles'::regclass and c.contype='u'",String.class);
        f.sql().execute("alter table student_profiles drop constraint "+constraint);
        long original=attempt(1,AttemptStatus.GRADED,assignment,true);
        long duplicate=f.sql().queryForObject("insert into student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section) select user_id,teacher_id,'DUPLICATE-FIXTURE','Synthetic','Profile',grade,section from student_profiles where user_id=? returning id",Long.class,f.student.getId());
        f.sql().update("insert into evaluation_attempts(evaluation_id,student_id,attempt_number,status,started_at,current_question_index,grade_closed,active) values(?,?,2,'GRADED','2026-02-01',0,false,false)",evaluation,duplicate);
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("CONFLICT");
        assertThat(budget()).containsEntry("used_count",2L).containsEntry("status","CONFLICT");
        assertThat(count("select count(*) from student_profiles where user_id=?",f.student.getId())).isEqualTo(2);
        assertThat(count("select count(*) from evaluation_attempts where id=?",original)).isEqualTo(1);
        // Contradictory source remains until the entire disposable schema is removed by the harness.
    }
    @Test void frozenConceptIncludesOrderedTextLinksAndActualFileBytesWithExplicitQuota() throws Exception {
        var teacher=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        long concept=f.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder().title("Fictitious C").category("Test").explanation("Original").formationSteps(List.of("first","second")).createdByTeacher(teacher).build()).getId();
        long material=f.sql().queryForObject("insert into concept_materials(concept_content_id,type,title,file_data,active) values(?,'FILE','Synthetic bytes',decode('01020304','hex'),true) returning id",Long.class,concept);
        long a=f.app.getBean(ConceptAssignmentRepository.class).saveAndFlush(ConceptAssignment.builder().conceptContent(f.app.getBean(ConceptContentRepository.class).findById(concept).orElseThrow()).teacher(teacher).grade("3").section("A").build()).getId();
        long entry=approved(CONCEPT,concept,map(List.of(delivery(a,classId,episode)),List.of()));assertThat(service.apply(entry)).isEqualTo("APPLIED");
        String payload=f.sql().queryForObject("select payload::text from t11b3_concept_revisions where concept_id=?",String.class,concept);
        assertThat(payload).contains("01020304","first","second","Original");
        long bytes=count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='REVISION_BYTES'",f.teacher.getId());
        assertThat(bytes).isGreaterThan(payload.length());
        f.sql().update("update concept_materials set file_data=decode('0909','hex') where id=?",material);f.sql().update("update concept_contents set explanation='Later' where id=?",concept);
        assertThat(f.sql().queryForObject("select payload::text from t11b3_concept_revisions where concept_id=?",String.class,concept)).isEqualTo(payload);
        assertThat(service.apply(entry)).isEqualTo("STALE");
    }
    @Test void answerAdjustmentOrderAndGradeFingerprintsStayIntact() throws Exception {
        long id=attempt(1,AttemptStatus.GRADED,assignment,true);
        var e=f.app.getBean(EvaluationRepository.class).findById(evaluation).orElseThrow();
        long question=f.app.getBean(EvaluationQuestionRepository.class).saveAndFlush(EvaluationQuestion.builder().evaluation(e).questionText("Synthetic open").questionType(QuestionType.OPEN_TEXT).expectedAnswer("Frozen rubric").build()).getId();
        long profile=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow().getId();
        f.sql().update("insert into evaluation_answers(attempt_id,question_id,answer_text,points_awarded,reviewed,teacher_feedback,reviewed_by) values(?,?,'Original answer',1,true,'Original feedback',?)",id,question,profile);
        f.sql().update("insert into evaluation_attempt_adjustments(attempt_id,amount,type,reason,created_by,active) values(?,0.50,'BONUS','Synthetic criterion',?,true)",id,profile);
        String before=f.sql().queryForObject("select t11b3_snapshot('EVALUATION',?)::text",String.class,evaluation);
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        assertThat(f.sql().queryForObject("select t11b3_snapshot('EVALUATION',?)::text",String.class,evaluation)).isEqualTo(before);
        assertThat(f.sql().queryForObject("select payload::text from t11b3_evaluation_revisions where evaluation_id=?",String.class,evaluation)).contains("Frozen rubric");
        f.sql().update("update evaluation_answers set teacher_feedback='Later' where attempt_id=?",id);
        assertThat(budget()).containsEntry("source_current",false);
    }
    @Test void twoIndependentJvmsApplyOnlyOneConsumptionAndSuccess() throws Exception {
        attempt(1,AttemptStatus.GRADED,assignment,true);long entry=approved(EVALUATION,evaluation,standardMap());
        assertThat(race("apply",entry)).containsExactly("APPLIED","APPLIED");
        assertThat(count("select count(*) from t11b3_attempt_consumptions where reconciliation_id=?",entry)).isEqualTo(1);
        assertThat(count("select count(*) from system_logs where event_type='RESOURCE_RECONCILIATION_APPLIED' and target_id=?",entry)).isEqualTo(1);
    }
    @Test void legacyLastOpportunityRaceWithPreparedDeliveriesStillHasOneWinner() throws Exception {
        attempt(1,AttemptStatus.GRADED,assignment,true);attempt(2,AttemptStatus.GRADED,secondAssignment,true);
        long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        assertThat(race("start",evaluation)).containsExactlyInAnyOrder("STARTED:3","REJECTED");
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?",evaluation)).isEqualTo(3);
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=? and status='IN_PROGRESS'",evaluation)).isEqualTo(1);
        assertThat(budget()).containsEntry("source_current",false).containsEntry("used_count",2L).containsEntry("admission_enabled",false);
    }
    List<String> race(String mode,long id) throws Exception {
        Path dir=Files.createDirectories(Path.of("target","t11b3-processes",f.schema,mode));
        Path go=dir.resolve("go");List<Process> children=new ArrayList<>();
        try {
            for(int i=1;i<=2;i++) {
                var cmd=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx384m","-Dspring.devtools.restart.enabled=false","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ResourceReconciliationProcess.class.getName(),f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,""+id,dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString(),mode,f.student.getUsername());
                children.add(new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready")))&&children.stream().allMatch(Process::isAlive)&&System.nanoTime()<deadline)Thread.sleep(30);
            assertThat(Files.exists(dir.resolve("1.ready"))).as("child 1: %s",dir).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();
            Files.writeString(go,"go");
            for(Process p:children){assertThat(p.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
            System.out.println("T11-B3 independent JVMs "+mode+": "+children.stream().map(Process::pid).toList());
            return List.of(Files.readString(dir.resolve("1.result")),Files.readString(dir.resolve("2.result")));
        } finally {for(Process p:children)if(p.isAlive())p.destroyForcibly();}
    }

    @Test void nativeDumpRestorePreservesAllIdsChecksumsAndAuditGuards() throws Exception {
        attempt(1,AttemptStatus.GRADED,null,false);long entry=approved(EVALUATION,evaluation,standardMap());assertThat(service.apply(entry)).isEqualTo("APPLIED");
        Path directory=Files.createDirectories(Path.of("target","t11b3-native",f.schema));
        String db="chemicallab_t11b3_restore_"+UUID.randomUUID().toString().replace("-","");
        List<String> tables=f.sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' order by relname",String.class);
        Map<String,String> original=new LinkedHashMap<>();for(String table:tables)original.put(table,f.sql().queryForObject(signature(table),String.class));
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        List<String> base=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));
        boolean created=false;
        try {
            nativeTool("pg_dump",base,List.of("-Fc","-d","chemicallab_t01_test","-f",directory.resolve("fixture.dump").toAbsolutePath().toString()),directory);
            nativeTool("createdb",base,List.of(db),directory);created=true;
            nativeTool("pg_restore",base,List.of("--exit-on-error","-d",db,directory.resolve("fixture.dump").toAbsolutePath().toString()),directory);
            try(Connection restored=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement sql=restored.createStatement()) {
                sql.execute("set search_path to "+f.schema+", public");sql.execute(b1);sql.execute(expansion);
                Map<String,String> recovered=new LinkedHashMap<>();for(String table:tables)try(ResultSet rows=sql.executeQuery(signature(table))){rows.next();recovered.put(table,rows.getString(1));}
                assertThat(recovered).isEqualTo(original);
                for(String mutation:List.of("update system_logs set event_type='ADMIN_ACTION' where id=(select min(id) from system_logs)","delete from system_logs where id=(select min(id) from system_logs)","truncate system_logs"))assertThatThrownBy(()->sql.execute(mutation)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
                sql.execute("alter table t11b3_attempt_consumptions disable trigger t11b3_immutable");
                assertThatThrownBy(()->sql.execute(expansion)).isInstanceOf(SQLException.class).hasMessageContaining("divergence");
                sql.execute("alter table t11b3_attempt_consumptions enable trigger t11b3_immutable");sql.execute(expansion);
                Files.writeString(directory.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source",original,"restored",recovered,"schema",f.schema,"guards","UPDATE/DELETE/TRUNCATE denied","certifier","B1 v1 + B3 v2")));
            }
        } finally {if(created)nativeTool("dropdb",base,List.of(db),directory);}
    }
    String signature(String table){return "select md5(coalesce(string_agg(to_jsonb(t)::text,E'\\n' order by to_jsonb(t)::text),'')) from "+table+" t";}
    void nativeTool(String name,List<String> base,List<String> args,Path directory) throws Exception {
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        String executable=windows?Path.of(System.getProperty("t11b3.pgBin","C:\\Program Files\\PostgreSQL\\18\\bin"),name+".exe").toString():name;
        var cmd=new ArrayList<String>();cmd.add(executable);cmd.addAll(base);cmd.addAll(args);
        ProcessBuilder pb=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(directory.resolve(name+".log").toFile());pb.environment().put("PGPASSWORD",f.setting("spring.datasource.password"));
        Process p=pb.start();try {assertThat(p.waitFor(60,TimeUnit.SECONDS)).as("native %s",name).isTrue();assertThat(p.exitValue()).as("native %s, log: %s",name,directory).isZero();} finally {if(p.isAlive())p.destroyForcibly();}
    }
}

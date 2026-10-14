package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.AuditUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.*;

/** Independent direct PK census, real PostgreSQL guard/transaction/process/restore tests. */
class ContextReconciliationDbTest {
    InstitutionStorageDbTest b;
    ResourceReconciliationDbTest r;
    WhiteboardSecurityTransportDbTest f;
    ContextReconciliationMaintenanceService service;
    UserAccount platform,school;
    long platformGrant,schoolGrant,foreignSchoolGrant,foreignEpisode;
    String expansion;
    JdbcTemplate sql(){return f.sql();}
    long count(String q,Object... args){return sql().queryForObject(q,Long.class,args);}
    UUID op(){return UUID.randomUUID();}
    @BeforeEach void start(TestInfo info) throws Exception {
        b=new InstitutionStorageDbTest();b.start(info);r=b.r;f=b.f;
        service=f.app.getBean(ContextReconciliationMaintenanceService.class);
        platform=f.account(Role.ADMINISTRADOR,null,"3","A");school=f.account(Role.DOCENTE,null,"3","A");
        long proof=sql().queryForObject("insert into t11b5_admin_proofs(account_id,evidence,source_checksum) select id,'FIXTURE-PLATFORM-'||id,md5(to_jsonb(u)::text) from user_accounts u where id=? returning id",Long.class,platform.getId());
        platformGrant=sql().queryForObject("insert into t11b5_platform_role_grants(account_id,proof_id,valid_from) values(?,?,statement_timestamp()) returning id",Long.class,platform.getId(),proof);
        long member=r.membership(r.foreign,school.getId(),ADMIN_INSTITUCION);
        schoolGrant=count("select id from membership_role_grants where membership_id=? and role='ADMIN_INSTITUCION'",member);
        long other=r.membership(r.institution,school.getId(),ADMIN_INSTITUCION);
        foreignSchoolGrant=count("select id from membership_role_grants where membership_id=? and role='ADMIN_INSTITUCION'",other);
        long shared=count("select id from institution_memberships where institution_id=? and account_id=?",r.foreign,f.student.getId());
        var episode=r.academic.createEnrollment(r.foreign,r.foreignClass,shared,r.from.plusSeconds(10),r.until,r.from.plusSeconds(3));
        r.academic.transitionEnrollment(episode.id(),Enrollment.Status.ACTIVE,r.from.plusSeconds(10));foreignEpisode=episode.id();
        expansion=r.script("t11b10-v1");
        if(info.getTestMethod().orElseThrow().getName().startsWith("installationRollback")) {
            b.b8.previous.previous.previous.base.tx().executeWithoutResult(s->{sql().execute(expansion);s.setRollbackOnly();});
            assertThat(sql().queryForObject("select to_regclass(current_schema()||'.t11b10_schema_version') is null",Boolean.class)).isTrue();
        }
        sql().execute(expansion);b.baseline();
    }
    @AfterEach void stop() throws Exception {SecurityContextHolder.clearContext();if(b!=null)b.stop();}
    Map<String,String> state(){return b.state();}
    String root(String source,long pk) throws Exception {return f.json.writeValueAsString(Map.of("source",source,"pk",Map.of("id",pk)));}
    String mapping(String source,long pk,long institution,long clazz) throws Exception {
        var rows=f.json.readTree(sql().queryForObject("select t11p05_manifest(?::jsonb)::text",String.class,root(source,pk)));
        var resolutions=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            var x=new HashMap<String,Object>();x.put("source",row.get("source_table").asText());x.put("pk",f.json.convertValue(row.get("source_pk"),Map.class));
            x.put("scope","INSTITUTION");x.put("institutionId",institution);x.put("classId",clazz);x.put("reason","FIXTURE-CORRECT");x.put("evidence","FIXTURE-PK-MAP");
            for(String field:List.of("account_id","author_account_id","owner_user_id","user_id","actor_user_id"))if(row.get("snapshot").hasNonNull(field))x.put("accountId",row.get("snapshot").get(field).asLong());
            for(String field:List.of("teacher_id","created_by_teacher_id"))if(row.get("snapshot").hasNonNull(field))x.put("accountId",count("select user_id from teacher_profiles where id=?",row.get("snapshot").get(field).asLong()));
            if(row.get("snapshot").hasNonNull("student_id"))x.put("accountId",count("select user_id from student_profiles where id=?",row.get("snapshot").get("student_id").asLong()));
            if(Set.of("evaluation_answers","evaluation_attempt_events","evaluation_attempt_adjustments").contains(x.get("source")))x.put("accountId",count("select student.user_id from evaluation_attempts attempt join student_profiles student on student.id=attempt.student_id where attempt.id=?",row.get("snapshot").get("attempt_id").asLong()));
            if(x.get("source").equals("t11b6_resource_grants"))x.put("accountId",count("select account_id from institution_memberships where id=?",row.get("snapshot").get("membership_id").asLong()));
            if(row.get("snapshot").hasNonNull("enrollment_id")) {x.put("enrollmentId",institution==r.foreign?foreignEpisode:r.episode);x.put("sourceTimezone","America/Lima");}
            if(x.get("source").equals("evaluation_attempts")&&row.get("snapshot").hasNonNull("assignment_id")) {x.put("enrollmentId",institution==r.foreign?foreignEpisode:r.episode);x.put("sourceTimezone","America/Lima");}
            if(x.get("source").equals("evaluation_attempts")&&row.get("snapshot").get("assignment_id").isNull()) {
                x.keySet().removeAll(Set.of("institutionId","classId","accountId","enrollmentId","sourceTimezone"));x.put("scope","UNRESOLVED");
            }
            resolutions.add(x);
        }
        return f.json.writeValueAsString(Map.of("institutionId",institution,"resolutions",resolutions));
    }
    long proposal(String source,long pk,long institution,long clazz) throws Exception {return service.propose(root(source,pk),mapping(source,pk,institution,clazz),"FIXTURE-CORRECTION",op());}
    long proposal() throws Exception {return proposal("evaluations",r.evaluation,r.foreign,r.foreignClass);}
    void authenticate(UserAccount account) {
        var principal=new SessionPrincipal(account.getId(),account.getUsername(),UUID.randomUUID(),0,account.getRole(),false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }
    void approve(long p,String role,UserAccount account,long grant) {
        authenticate(account);try{service.approve(p,role,grant,service.approvalChecksum(p),"FIXTURE-APPROVAL",op());}finally{SecurityContextHolder.clearContext();}
    }
    void approvals(long p){approve(p,"PLATFORM",platform,platformGrant);approve(p,"INSTITUTION",school,schoolGrant);}

    @Test void completeIndependentPkCensusIncludesAccountsProfilesGroupsAndUnassignedAttempts() throws Exception {
        long unassigned=r.attempt(1,AttemptStatus.GRADED,null,false);
        long question=sql().queryForObject("insert into evaluation_questions(evaluation_id,question_text,question_type,points,order_index,required,active) values(?,'Census question','OPEN_TEXT',1,0,true,true) returning id",Long.class,r.evaluation);
        sql().update("insert into evaluation_answers(attempt_id,question_id,answer_text,points_awarded,reviewed) values(?,?,'Census original answer',1,true)",unassigned,question);
        long otherQuestion=sql().queryForObject("insert into evaluation_questions(evaluation_id,question_text,question_type,points,order_index,required,active) values(?,'Other census question','MULTIPLE_CHOICE',1,1,true,true) returning id",Long.class,r.evaluation);
        long wrongOption=sql().queryForObject("insert into evaluation_options(question_id,option_text,correct,order_index,active) values(?,'Different question option',true,0,true) returning id",Long.class,otherQuestion);
        sql().update("update evaluation_answers set selected_option_id=? where attempt_id=?",wrongOption,unassigned);
        sql().update("insert into evaluation_attempt_events(attempt_id,event_type,occurred_at) values(?,'ATTEMPT_SUBMITTED','2026-02-01 10:06:00')",unassigned);
        b.b8.previous.previous.previous.base.tx().executeWithoutResult(s->{sql().execute("set local session_replication_role=replica");sql().update("insert into concept_content_examples(concept_content_id,example_order,example) values(9223372036854775000,0,'Census orphan')");sql().execute("set local session_replication_role=origin");});
        long id=service.inventory(op());var coverage=new ArrayList<Map<String,Object>>();
        var descriptor=f.json.readTree(Files.readString(Path.of("docs/arquitectura/t11b10/catalog.json")));
        // Independent SQL enumeration: no scan(), inventory manifest or B9 catalog computes expected PKs.
        for(var c:descriptor.get("sources")) {
            String source=c.get("source").asText();
            var fields=new ArrayList<String>();for(var key:c.get("keys")){fields.add("'"+key.asText()+"'");fields.add(key.asText());}
            var expected=source.equals("legacy_groups")?sql().queryForList("select jsonb_build_object('grade',grade,'section',section)::text from (select grade,section from student_profiles union select grade,section from concept_assignments union select grade,section from evaluation_assignments union select grade,section from whiteboard_sessions) labels",String.class):sql().queryForList("select jsonb_build_object("+String.join(",",fields)+")::text from "+source,String.class);
            var actual=sql().queryForList("select source_pk::text from t11b10_inventory where run_id=? and source_table=?",String.class,id,source);
            // Inventory SUCCESS follows the photograph and belongs to the next census.
            if(source.equals("system_logs"))expected=sql().queryForList("select jsonb_build_object('id',id)::text from system_logs where not(target_type='ContextResolution' and target_id=? and event_type='ADMIN_ACTION')",String.class,id);
            assertThat(actual).as(source+" complete PKs").containsExactlyInAnyOrderElementsOf(expected);
            coverage.add(Map.of("source",source,"expected",expected.size(),"inventoried",actual.size()));
        }
        assertThat(count("select count(*) from t11b10_inventory where run_id=? and source_table='student_profiles' and classification='UNRESOLVED'",id)).isGreaterThan(0);
        assertThat(count("select count(*) from t11b10_inventory where run_id=? and source_table='legacy_groups' and reason='AMBIGUOUS_LABEL_WITHOUT_PERIOD_OR_INSTITUTION'",id)).isGreaterThan(0);
        assertThat(count("select count(*) from t11b10_inventory where run_id=? and reason='UNASSIGNED_ATTEMPT_NO_EPISODE_EVIDENCE'",id)).isEqualTo(1);
        assertThat(count("select count(*) from t11b10_inventory where run_id=? and reason like 'MISSING_PARENT:%'",id)).isGreaterThan(0);
        assertThat(count("select count(*) from t11b10_inventory where run_id=? and reason='ANSWER_OPTION_QUESTION_MISMATCH' and classification='CONFLICT'",id)).isEqualTo(1);
        Path out=Files.createDirectories(Path.of("target/t11b10-evidence"));Files.writeString(out.resolve("coverage.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(coverage));
    }
    @Test void correctionVersionsEntireAggregatePreservesRootsOriginalsAndB9() throws Exception {
        var preserved=b.b8.previous.previous.signatures("evaluations","evaluation_assignments","evaluation_attempts","evaluation_answers","t11b3_opportunity_groups","t11b3_account_attempt_budgets","t11b3_evaluation_resources","t11b3_evaluation_deliveries","t11b9_items","t11b9_counters","t11b9_commands");
        long p=proposal();approvals(p);UUID operation=op();assertThat(service.apply(p,operation)).isEqualTo("APPLIED");
        assertThat(count("select count(*) from t11b10_current where proposal_id=? and context_valid and (resolution->>'institutionId')::bigint=?",p,r.foreign))
            .isEqualTo(count("select jsonb_array_length(manifest) from t11b10_proposals where id=?",p));
        assertThat(b.b8.previous.previous.signatures(preserved.keySet().toArray(String[]::new))).isEqualTo(preserved);
        assertThat(count("select count(*) from t11b10_measurements m,lateral jsonb_array_elements(m.deltas) d where d->>'reason'='ATTRIBUTION_CORRECTION'")).isGreaterThan(0);
        var before=state();assertThat(service.apply(p,operation)).isEqualTo("APPLIED");assertThat(state()).isEqualTo(before);
        long next=proposal("evaluations",r.evaluation,r.institution,r.classId);approve(next,"PLATFORM",platform,platformGrant);approve(next,"INSTITUTION",school,foreignSchoolGrant);
        assertThat(service.apply(next,op())).isEqualTo("APPLIED");
        assertThat(count("select count(*) from t11b10_versions where predecessor_id is not null")).isEqualTo(count("select count(*) from t11b10_versions where proposal_id=?",p));
        assertThat(count("select count(*) from t11b10_current where proposal_id=?",p)).isZero();assertThat(count("select count(*) from t11b10_versions where proposal_id=?",p)).isGreaterThan(0);
    }
    @Test void requiresBothApprovalsFromDistinctAccountsAndCorrectSchoolWithExactEvidence() throws Exception {
        long p=proposal();var before=state();
        assertThatThrownBy(()->service.apply(p,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("two distinct");assertThat(state()).isEqualTo(before);
        approve(p,"PLATFORM",platform,platformGrant);before=state();
        assertThatThrownBy(()->service.apply(p,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("two distinct");assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->approve(p,"INSTITUTION",school,foreignSchoolGrant)).isInstanceOf(DataAccessException.class).hasMessageContaining("provenance");
        authenticate(school);
        try {
            assertThatThrownBy(()->service.approve(p,"INSTITUTION",schoolGrant,"0".repeat(32),"FIXTURE-APPROVAL",op())).isInstanceOf(DataAccessException.class).hasMessageContaining("mismatch");
            assertThatThrownBy(()->service.approve(p,"INSTITUTION",schoolGrant,service.approvalChecksum(p),"",op())).isInstanceOf(DataAccessException.class);
        }finally{SecurityContextHolder.clearContext();}
        assertThat(state()).isEqualTo(before);approve(p,"INSTITUTION",school,schoolGrant);assertThat(service.apply(p,op())).isEqualTo("APPLIED");
    }
    @Test void oneAccountWithBothPrivilegesStillCannotSupplyBothApprovals() throws Exception {
        long member=r.membership(r.foreign,platform.getId(),ADMIN_INSTITUCION);
        long grant=count("select id from membership_role_grants where membership_id=? and role='ADMIN_INSTITUCION'",member);
        long p=proposal();approve(p,"PLATFORM",platform,platformGrant);var before=state();
        assertThatThrownBy(()->approve(p,"INSTITUTION",platform,grant)).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);assertThat(count("select count(*) from t11b10_approvals where proposal_id=?",p)).isEqualTo(1);
    }
    @Test void sourceDriftIsTerminalAndRetryPreservesOldApprovalsAndNoContext() throws Exception {
        long p=proposal();approvals(p);sql().update("update evaluations set title='changed fixture' where id=?",r.evaluation);
        UUID operation=op();assertThat(service.apply(p,operation)).isEqualTo("STALE");assertThat(count("select count(*) from t11b10_versions")).isZero();
        var before=state();assertThat(service.apply(p,operation)).isEqualTo("STALE");assertThat(state()).isEqualTo(before);
        assertThat(count("select count(*) from t11b10_approvals where proposal_id=?",p)).isEqualTo(2);
        assertThatThrownBy(()->approve(p,"PLATFORM",platform,platformGrant)).isInstanceOf(DataAccessException.class);
    }
    @Test void permissionRevocationAndReferenceChangesInvalidateApprovedVersion() throws Exception {
        long p=proposal();approvals(p);r.institutions.revokeRole(schoolGrant,java.time.Instant.now());
        assertThat(service.apply(p,op())).isEqualTo("STALE");assertThat(count("select count(*) from t11b10_current")).isZero();
        long q=proposal("evaluations",r.evaluation,r.institution,r.classId);approve(q,"PLATFORM",platform,platformGrant);approve(q,"INSTITUTION",school,foreignSchoolGrant);
        sql().update("update user_accounts set credentials_version=credentials_version+1 where id=?",platform.getId());
        assertThat(service.apply(q,op())).isEqualTo("STALE");
    }
    @Test void omittedPkWrongClassAndMissingEvidenceCannotBeRepairedByApproval() throws Exception {
        var mapping=f.json.readTree(mapping("evaluations",r.evaluation,r.foreign,r.foreignClass));
        ((com.fasterxml.jackson.databind.node.ArrayNode)mapping.get("resolutions")).remove(0);
        long p=service.propose(root("evaluations",r.evaluation),mapping.toString(),"FIXTURE-OMISSION",op());approvals(p);var before=state();
        assertThatThrownBy(()->service.apply(p,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("exhaustive");assertThat(state()).isEqualTo(before);
        service.reject(p,"FIXTURE-REJECTED-MAP",op());assertThat(count("select count(*) from t11b10_approvals where proposal_id=?",p)).isEqualTo(2);
        mapping=f.json.readTree(mapping("evaluations",r.evaluation,r.foreign,r.classId));
        long q=service.propose(root("evaluations",r.evaluation),mapping.toString(),"FIXTURE-WRONG-CLASS",op());approvals(q);
        assertThatThrownBy(()->service.apply(q,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("class institution");
        ((com.fasterxml.jackson.databind.node.ObjectNode)mapping.get("resolutions").get(0)).remove("evidence");
        long z=service.propose(root("evaluations",r.evaluation),mapping.toString(),"FIXTURE-MISSING-EVIDENCE",op());approvals(z);
        assertThatThrownBy(()->service.apply(z,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("evidence");
    }
    @Test void uncertainSuccessorAndStaleLatestNeverFallbackToEarlierValidVersion() throws Exception {
        long p=proposal();approvals(p);assertThat(service.apply(p,op())).isEqualTo("APPLIED");
        var map=f.json.readTree(mapping("evaluations",r.evaluation,r.foreign,r.foreignClass));
        for(var resolution:map.get("resolutions")){
            var x=(com.fasterxml.jackson.databind.node.ObjectNode)resolution;x.put("scope","UNRESOLVED");x.remove(List.of("institutionId","classId","accountId","enrollmentId"));
        }
        long q=service.propose(root("evaluations",r.evaluation),map.toString(),"FIXTURE-UNCERTAIN",op());approvals(q);assertThat(service.apply(q,op())).isEqualTo("APPLIED");
        assertThat(count("select count(*) from t11b10_current where context_valid")).isZero();assertThat(count("select count(*) from t11b10_versions where proposal_id=?",p)).isGreaterThan(0);
        sql().update("update evaluations set title='source changed again' where id=?",r.evaluation);
        assertThat(count("select count(*) from t11b10_current where status='STALE'")).isEqualTo(count("select count(*) from t11b10_current"));
    }
    @Test void b3ExistingRootConflictIsSupersededWithoutReplacingBudgetOrOriginals() throws Exception {
        sql().update("update evaluations set title='new photograph' where id=?",r.evaluation);
        long conflict=r.approved(ResourceReconciliationService.SourceKind.EVALUATION,r.evaluation,r.standardMap());assertThat(r.service.apply(conflict)).isEqualTo("CONFLICT");
        var before=b.b8.previous.previous.signatures("t11b3_opportunity_groups","t11b3_account_attempt_budgets","t11b3_attempt_consumptions","evaluations","evaluation_assignments");
        long p=proposal();approvals(p);assertThat(service.apply(p,op())).isEqualTo("APPLIED");
        assertThat(b.b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        assertThat(count("select count(*) from t11b3_opportunity_groups where evaluation_id=?",r.evaluation)).isEqualTo(1);
        assertThat(count("select count(*) from t11b10_current where source_table='evaluations' and context_valid")).isEqualTo(1);
    }
    @Test void exhaustedAndActiveLegacyBudgetClosesNoWindowAndRefundsNoOpportunity() throws Exception {
        var teacher=f.app.getBean(com.morales.chemicallab.repository.TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        r.evaluation=f.app.getBean(com.morales.chemicallab.repository.EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Budget fixture").status(EvaluationStatus.PUBLISHED).maxAttempts(2).timeLimitMinutes(30).createdByTeacher(teacher).build()).getId();
        r.assignment=r.assignment(r.evaluation,teacher,java.time.LocalDateTime.of(2026,12,1,0,0));
        long graded=r.attempt(1,AttemptStatus.GRADED,r.assignment,true);long active=r.attempt(2,AttemptStatus.IN_PROGRESS,null,false);
        long question=sql().queryForObject("insert into evaluation_questions(evaluation_id,question_text,question_type,points,order_index,required,active) values(?,'Synthetic question','OPEN_TEXT',1,0,true,true) returning id",Long.class,r.evaluation);
        sql().update("insert into evaluation_answers(attempt_id,question_id,answer_text,points_awarded,reviewed,teacher_feedback,reviewed_by,answered_at) values(?,?,'Original synthetic answer',1,true,'Original synthetic feedback',?,'2026-02-01 10:05:00')",graded,question,teacher.getId());
        sql().update("insert into evaluation_attempt_events(attempt_id,event_type,description,occurred_at) values(?,'ATTEMPT_SUBMITTED','Original lifecycle event','2026-02-01 10:06:00')",graded);
        sql().update("insert into evaluation_attempt_adjustments(attempt_id,amount,type,reason,created_by,created_at,active) values(?,1,'BONUS','Original synthetic adjustment',?,'2026-02-01 10:07:00',true)",graded,teacher.getId());
        sql().update("update evaluation_attempts set score=1,max_score=1,final_score=20,grade_closed=true,grade_closed_by=?,grade_closed_at='2026-02-01 10:08:00' where id=?",teacher.getId(),graded);
        long source=r.approved(ResourceReconciliationService.SourceKind.EVALUATION,r.evaluation,r.map(List.of(r.delivery(r.assignment,r.classId,r.episode)),List.of()));
        assertThat(r.service.apply(source)).isEqualTo("APPLIED");var budget=r.budget();
        assertThat(budget).containsEntry("used_count",2L).containsEntry("active_attempt_id",active).containsEntry("reserved_remaining",0L).containsEntry("admission_enabled",false);
        var before=b.b8.previous.previous.signatures("t11b3_opportunity_groups","t11b3_account_attempt_budgets","t11b3_attempt_consumptions","evaluation_attempts","evaluation_answers","evaluation_attempt_events","evaluation_attempt_adjustments","evaluations","evaluation_assignments","t11b8_budgets","t11b8_fixture_attempts");
        long p=proposal();approvals(p);assertThat(service.apply(p,op())).isEqualTo("APPLIED");
        assertThat(b.b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);assertThat(r.budget()).isEqualTo(budget);
        assertThat(count("select count(*) from t11b10_current where source_table='evaluation_attempts' and source_pk=jsonb_build_object('id',?::bigint) and status='UNRESOLVED'",active)).isEqualTo(1);
        assertThat(count("select count(*) from t11b10_current where source_table in('evaluation_answers','evaluation_attempt_events','evaluation_attempt_adjustments') and context_valid and (resolution->>'accountId')::bigint=?",f.student.getId())).isEqualTo(3);
    }
    @Test void b4WrongHistoricalLinkIsPreservedWhileBoardAndParticipantsGainNewVersion() throws Exception {
        var boards=f.app.getBean(BoardActivityReconciliationService.class);
        String map=f.json.writeValueAsString(Map.of("institutionId",r.institution,"classId",r.classId,"authorAccountId",f.teacher.getId(),"participants",List.of()));
        long original=boards.propose(BoardActivityReconciliationService.SourceKind.BOARD,f.board,map);
        boards.approve(original,sql().queryForObject("select md5(source_checksum||proposal_checksum) from t11b4_reconciliations where id=?",String.class,original),"FIXTURE-ORIGINAL");assertThat(boards.apply(original)).isEqualTo("APPLIED");
        String changed=f.json.writeValueAsString(Map.of("institutionId",r.foreign,"classId",r.foreignClass,"authorAccountId",f.teacher.getId(),"participants",List.of()));
        long conflict=boards.propose(BoardActivityReconciliationService.SourceKind.BOARD,f.board,changed);
        boards.approve(conflict,sql().queryForObject("select md5(source_checksum||proposal_checksum) from t11b4_reconciliations where id=?",String.class,conflict),"FIXTURE-CORRECTION");assertThat(boards.apply(conflict)).isEqualTo("CONFLICT");
        var before=b.b8.previous.previous.signatures("whiteboard_sessions","whiteboard_participants","whiteboard_object_ownership","t11b4_board_contexts");
        long p=proposal("whiteboard_sessions",f.board,r.foreign,r.foreignClass);approvals(p);assertThat(service.apply(p,op())).isEqualTo("APPLIED");
        assertThat(b.b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        assertThat(count("select institution_id from t11b4_board_contexts where board_id=?",f.board)).isEqualTo(r.institution);
        assertThat(count("select count(*) from t11b10_current where source_table='whiteboard_participants' and context_valid")).isEqualTo(count("select count(*) from whiteboard_participants where session_id=?",f.board));
    }
    @Test void auditAndQuotaFailuresRollbackVersionsMeasurementsEvidenceAndSuccess() throws Exception {
        long p=proposal();approvals(p);var before=state();
        sql().execute("create function fixture_b10_fail() returns trigger language plpgsql as $$ begin if NEW.target_type='ContextResolution' then raise exception 'fixture audit unavailable';end if;return NEW;end $$");
        sql().execute("create trigger fixture_b10_fail before insert on system_logs for each row execute function fixture_b10_fail()");
        try{assertThatThrownBy(()->service.apply(p,op())).isInstanceOf(AuditUnavailableException.class);}finally{sql().execute("drop trigger fixture_b10_fail on system_logs");sql().execute("drop function fixture_b10_fail()");}
        assertThat(state()).isEqualTo(before);
        b.service.limit(r.foreign,"ORIGINAL",1,1,op());before=state();
        assertThatThrownBy(()->service.apply(p,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("quota exceeded");assertThat(state()).isEqualTo(before);
        b.service.limit(r.foreign,"ORIGINAL",1000000,268435456,op());assertThat(service.apply(p,op())).isEqualTo("APPLIED");
    }
    @Test void omissionsAmbiguousKeysAndMissingParentsRemainVerifiableConflicts() throws Exception {
        long concept=count("select min(id) from concept_contents");
        // Deliberately damaged fixture: normal PostgreSQL PK integrity already prevents this.
        sql().execute("alter table concept_content_key_points drop constraint concept_content_key_points_pkey");
        sql().update("insert into concept_content_key_points(concept_content_id,key_point_order,key_point) values(?,4242,'one'),(?,4242,'two')",concept,concept);
        b.b8.previous.previous.previous.base.tx().executeWithoutResult(s->{
            sql().execute("set local session_replication_role=replica");
            sql().update("insert into concept_content_examples(concept_content_id,example_order,example) values(9223372036854775000,0,'orphan fixture')");
            sql().execute("set local session_replication_role=origin");
        });
        long run=service.inventory(op());assertThat(count("select count(*) from t11b10_inventory where run_id=? and reason='AMBIGUOUS_KEY' and (snapshot->>'physicalRows')::bigint=2",run)).isEqualTo(1);
        assertThat(count("select count(*) from t11b10_inventory where run_id=? and reason like 'MISSING_PARENT:%'",run)).isGreaterThan(0);
        sql().execute("create table fixture_unknown_source(id bigint primary key)");
        try{assertThatThrownBy(()->service.inventory(op())).isInstanceOf(DataAccessException.class).hasMessageContaining("catalog omission");}finally{sql().execute("drop table fixture_unknown_source");}
        sql().update("insert into concept_content_examples(concept_content_id,example_order,example) values(?,99,'new source PK')",concept);
        String differences=sql().queryForObject("select t11p05_inventory_differences(?)::text",String.class,run);assertThat(differences).contains("OMITTED_OR_NEW_SOURCE_PK");
    }
    @Test void installationRollbackRepeatDivergenceAndDirectTamperingAreRejected() throws Exception {
        var before=state();sql().execute(expansion);sql().execute(b.expansion);assertThat(state()).isEqualTo(before);
        b.b8.previous.previous.previous.base.tx().executeWithoutResult(s->{
            sql().execute("alter table user_accounts drop constraint user_accounts_role_check");
            sql().execute("alter table user_accounts add constraint user_accounts_role_check check(role in('ESTUDIANTE','DOCENTE','ADMINISTRADOR','UNKNOWN'))");
            assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();
        });
        for(String table:List.of("t11b9_items","t11b10_versions")) {
            b.b8.previous.previous.previous.base.tx().executeWithoutResult(s->{
                sql().execute("alter table "+table+" disable trigger "+(table.startsWith("t11b9")?"t11b9_guard":"t11p05_generated"));
                assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();
            });
        }
        long p=proposal();approvals(p);assertThat(service.apply(p,op())).isEqualTo("APPLIED");before=state();
        for(String query:List.of("update t11b10_proposals set reason='FIXTURE-REWRITE'","delete from t11b10_versions","truncate t11b10_approvals cascade","update t11b10_usage set bytes_used=0","insert into t11b10_events(proposal_id,status,command_id,reason) select id,'APPLIED',command_id,'FAKE' from t11b10_proposals"))
            assertThatThrownBy(()->sql().execute(query)).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void typedUsageAndAuditRequireSameCurrentParentAndPreserveOriginalFacts() throws Exception {
        long board=proposal("whiteboard_sessions",f.board,r.foreign,r.foreignClass);approvals(board);assertThat(service.apply(board,op())).isEqualTo("APPLIED");
        b.b8.previous.previous.previous.base.tx().executeWithoutResult(s->f.app.getBean(AuditLogService.class).recordInfo(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board,null,null,null,null));
        long log=count("select max(id) from system_logs where target_type='WhiteboardSession' and target_id=?",f.board);
        long usage=f.app.getBean(com.morales.chemicallab.repository.UsageEventRepository.class).saveAndFlush(UsageEvent.builder().userId(f.student.getId()).username(f.student.getUsername()).userRole(Role.ESTUDIANTE).module(UsageModule.WHITEBOARD).eventType(UsageEventType.WHITEBOARD_SESSION_JOINED).resourceType("WHITEBOARD_SESSION").resourceId(""+f.board).build()).getId();
        long wrong=proposal("system_logs",log,r.institution,r.classId);approve(wrong,"PLATFORM",platform,platformGrant);approve(wrong,"INSTITUTION",school,foreignSchoolGrant);
        assertThatThrownBy(()->service.apply(wrong,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("typed parent");
        String original=sql().queryForObject("select to_jsonb(l)::text from system_logs l where id=?",String.class,log);
        for(var fact:Map.of("system_logs",log,"usage_events",usage).entrySet()) {
            long p=proposal(fact.getKey(),fact.getValue(),r.foreign,r.foreignClass);approvals(p);assertThat(service.apply(p,op())).isEqualTo("APPLIED");
        }
        assertThat(sql().queryForObject("select to_jsonb(l)::text from system_logs l where id=?",String.class,log)).isEqualTo(original);
        long changed=proposal("whiteboard_sessions",f.board,r.institution,r.classId);approve(changed,"PLATFORM",platform,platformGrant);approve(changed,"INSTITUTION",school,foreignSchoolGrant);assertThat(service.apply(changed,op())).isEqualTo("APPLIED");
        assertThat(count("select count(*) from t11b10_current where source_table in('system_logs','usage_events') and status='STALE'")).isEqualTo(2);
    }
    @Test void resourceCorrectionCannotTransferOldSchoolLibraryAuthority() throws Exception {
        var delegations=f.app.getBean(DelegationMaintenanceService.class);long admin=count("select membership_id from membership_role_grants where id=?",foreignSchoolGrant);
        var now=java.time.Instant.now();authenticate(school);long grant;
        try{grant=delegations.grantResource(r.institution,ResourceManagerGrant.Kind.EVALUATION,r.evaluation,r.authorMember,ResourceManagerGrant.Faculty.ASSIGN,admin,now.plusSeconds(1),now.plusSeconds(600),now).id();}finally{SecurityContextHolder.clearContext();}
        long p=proposal();approvals(p);var before=state();
        assertThatThrownBy(()->service.apply(p,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("authority grant cannot change");assertThat(state()).isEqualTo(before);
        var map=f.json.readTree(mapping("evaluations",r.evaluation,r.foreign,r.foreignClass));
        for(var item:map.get("resolutions"))if(item.get("source").asText().equals("t11b6_resource_grants")) {
            var x=(com.fasterxml.jackson.databind.node.ObjectNode)item;x.put("scope","CONFLICT");x.remove(List.of("institutionId","classId","accountId","enrollmentId","sourceTimezone"));
        }
        long q=service.propose(root("evaluations",r.evaluation),map.toString(),"FIXTURE-KEEP-OLD-GRANT",op());approvals(q);assertThat(service.apply(q,op())).isEqualTo("APPLIED");
        assertThat(count("select institution_id from t11b6_resource_grants where id=?",grant)).isEqualTo(r.institution);
        assertThat(count("select count(*) from t11b10_current where source_table='t11b6_resource_grants' and source_pk=jsonb_build_object('id',?::bigint) and status='CONFLICT' and not context_valid",grant)).isEqualTo(1);
    }
    @Test void originalAccountAndHistoricalEpisodeMustBothMatch() throws Exception {
        long attempt=r.attempt(1,AttemptStatus.GRADED,r.assignment,true);
        var map=f.json.readTree(mapping("evaluations",r.evaluation,r.foreign,r.foreignClass));
        for(var item:map.get("resolutions"))if(item.get("source").asText().equals("evaluation_attempts"))((com.fasterxml.jackson.databind.node.ObjectNode)item).put("accountId",f.peerStudent.getId());
        long wrongAccount=service.propose(root("evaluations",r.evaluation),map.toString(),"FIXTURE-WRONG-ACCOUNT",op());approvals(wrongAccount);
        assertThatThrownBy(()->service.apply(wrongAccount,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("account/author/owner");
        for(var item:map.get("resolutions"))if(item.get("source").asText().equals("evaluation_attempts")){
            var x=(com.fasterxml.jackson.databind.node.ObjectNode)item;x.put("accountId",f.student.getId());x.put("enrollmentId",r.episode);
        }
        long wrongEpisode=service.propose(root("evaluations",r.evaluation),map.toString(),"FIXTURE-WRONG-EPISODE",op());approvals(wrongEpisode);
        assertThatThrownBy(()->service.apply(wrongEpisode,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("episode/class/institution/account");
        assertThat(count("select count(*) from t11b10_versions where source_table='evaluation_attempts' and source_pk=jsonb_build_object('id',?::bigint)",attempt)).isZero();
    }
    @Test void explicitNewF09RootAndAnsweredFixtureAttemptKeepOneBudgetAfterCorrection() throws Exception {
        long delivery=b.b8.delivered(b.published,r.classId),recipient=b.b8.admitted(delivery,r.episode);
        long attempt=b.b8.service.reserveFixtureAttempt(recipient,op());b.b8.service.finishFixtureAttempt(attempt,1,"{\"17\":\"Original answer\"}",19,op());
        long evaluation=count("select evaluation_id from t11b8_resources where id=?",b.resource);
        var before=b.b8.previous.previous.signatures("t11b8_resources","t11b8_revisions","t11b8_deliveries","t11b8_recipients","t11b8_budgets","t11b8_fixture_attempts","t11b8_events","evaluations","evaluation_attempts","t11b3_opportunity_groups");
        long p=proposal("evaluations",evaluation,r.foreign,r.foreignClass);approvals(p);assertThat(service.apply(p,op())).isEqualTo("APPLIED");
        assertThat(b.b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        assertThat(count("select count(*) from t11b8_budgets where resource_id=? and account_id=?",b.resource,f.student.getId())).isEqualTo(1);
        assertThat(count("select count(*) from t11b10_current where source_table='t11b8_fixture_attempts' and source_pk=jsonb_build_object('id',?::bigint) and context_valid",attempt)).isEqualTo(1);
    }
    @Test void twoProcessesCompeteOnSamePredecessorAndRestoreCertifiesFullSuccessor() throws Exception {
        long one=proposal(),two=proposal();approvals(one);approvals(two);
        Path dir=Files.createDirectories(Path.of("target/t11b10-processes",f.schema));Path go=dir.resolve("go");var children=new ArrayList<Process>();
        try(Connection barrier=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            barrier.setAutoCommit(false);barrier.createStatement().execute("select t11p05_lock()");
            try {
                for(int i=1;i<=2;i++) {
                    var args=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ContextReconciliationProcess.class.getName(),
                        f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,""+(i==1?one:two),dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString());
                    children.add(new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready")))&&System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(Files.exists(dir.resolve("1.ready"))).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();Files.writeString(go,"go");
                List<Integer> pids=List.of();deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline){pids=sql().queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query='select t11p05_lock()'",Integer.class);if(pids.size()==2)break;Thread.sleep(20);}
                assertThat(pids).hasSize(2);barrier.commit();
                for(Process p:children){assertThat(p.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
                var results=List.of(Files.readString(dir.resolve("1.result")),Files.readString(dir.resolve("2.result")));
                assertThat(results).containsExactlyInAnyOrder("APPLIED","STALE");assertThat(count("select count(*) from t11b10_measurements")).isEqualTo(1);
                assertThat(count("select count(*) from t11b10_versions")).isEqualTo(count("select jsonb_array_length(manifest) from t11b10_proposals where id=?",one));
                Files.writeString(dir.resolve("evidence.json"),f.json.writeValueAsString(Map.of("osPids",children.stream().map(Process::pid).toList(),"blockedPostgresPids",pids,"results",results,"scope","SQL integrity; mandatory service audit tested separately")));
            }finally{barrier.rollback();for(Process p:children)if(p.isAlive())p.destroyForcibly();}
        }
        nativeRestore();
    }
    void nativeRestore() throws Exception {
        Path dir=Files.createDirectories(Path.of("target/t11b10-native",f.schema));String db="chemicallab_t11b10_restore_"+UUID.randomUUID().toString().replace("-","");
        var before=state();String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));boolean created=false;
        try {
            r.nativeTool("pg_dump",args,List.of("-Fc","-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            r.nativeTool("createdb",args,List.of(db),dir);created=true;r.nativeTool("pg_restore",args,List.of("--exit-on-error","--single-transaction","-d",db,dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            try(Connection c=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement q=c.createStatement()) {
                q.execute("set search_path to "+f.schema+",public");q.execute(expansion);Map<String,String> restored=new TreeMap<>();
                for(String table:before.keySet())try(var rows=q.executeQuery(r.signature(table))){rows.next();restored.put(table,rows.getString(1));}assertThat(restored).isEqualTo(before);
                assertThatThrownBy(()->q.execute("select t11p05_lock()")).isInstanceOf(SQLException.class).hasMessageContaining("fixtures");
                assertThatThrownBy(()->q.execute("truncate t11b10_versions cascade")).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
                Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source",before,"restored",restored,"certifier","B10 v1 frozen B9 chain")));
            }
        }finally{if(created)r.nativeTool("dropdb",args,List.of(db),dir);}
    }
}

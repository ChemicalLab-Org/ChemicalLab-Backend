package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.AuditUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.entity.institution.ResourceManagerGrant.*;
import static com.morales.chemicallab.entity.institution.TeachingAssignment.Status.*;
import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DelegationPersistenceDbTest {
    final IdentityPreparationDbTest previous=new IdentityPreparationDbTest();
    final ResourceReconciliationDbTest r=previous.base.base;
    final WhiteboardSecurityTransportDbTest f=r.f;
    DelegationMaintenanceService service;
    String expansion;
    long a,b,c,teacher,admin,foreignAdmin,concept,foreignConcept,foreignTeacher;
    final Instant t=Instant.parse("2026-01-01T00:00:00Z");
    Instant at(int seconds){return t.plusSeconds(seconds);}
    JdbcTemplate sql(){return f.sql();}
    long count(String q,Object... args){return sql().queryForObject(q,Long.class,args);}
    Map<String,String> signatures(String... tables) {
        Map<String,String> result=new TreeMap<>();for(String table:tables)result.put(table,sql().queryForObject(r.signature(table),String.class));return result;
    }
    Map<String,String> versions() {return signatures("t11b1_schema_version","t11b2_schema_version","t11b2_audit_version","t11b3_schema_version","t11b3_apply_version","t11b4_schema_version","t11b4_apply_version","t11b5_schema_version","audit_schema_version","storage_schema_version");}
    @BeforeAll void start() throws Exception {
        previous.start();service=f.app.getBean(DelegationMaintenanceService.class);expansion=r.script("t11b6-v1");
        r.fixture();var old=r.academic.planAssignment(r.institution,r.classId,r.authorMember,at(10),r.until,at(3));
        r.academic.transitionAssignment(old.id(),ACTIVE,at(10));
        var original=signatures("teaching_assignments","teaching_assignment_events","enrollments","user_accounts");var certs=versions();
        previous.base.tx().executeWithoutResult(s->{sql().execute(expansion);s.setRollbackOnly();});
        assertThat(sql().queryForObject("select to_regclass(current_schema()||'.t11b6_schema_version') is null",Boolean.class)).isTrue();
        sql().execute(expansion);sql().execute(expansion);
        assertThat(signatures(original.keySet().toArray(String[]::new))).isEqualTo(original);assertThat(versions()).isEqualTo(certs);
        assertThat(count("select count(*) from t11b6_teaching_slots where responsible_id=?",old.id())).isEqualTo(1);
        assertThatThrownBy(()->sql().execute(previous.expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");
    }
    @BeforeEach void fixture() throws Exception {
        previous.fixture();a=r.institution;b=r.foreign;c=r.classId;
        teacher=r.membership(a,f.otherTeacher.getId(),DOCENTE);
        admin=previous.other;r.institutions.transitionMembership(admin,InstitutionMembership.Status.ACTIVE,at(1));r.institutions.grantRole(admin,ADMIN_INSTITUCION,at(2),null);
        foreignAdmin=r.membership(b,f.peerStudent.getId(),ADMIN_INSTITUCION);foreignTeacher=r.membership(b,f.otherTeacher.getId(),DOCENTE);
        assertThat(r.service.apply(r.approved(ResourceReconciliationService.SourceKind.EVALUATION,r.evaluation,r.standardMap()))).isEqualTo("APPLIED");
        concept=concept(a,r.authorMember);foreignConcept=concept(b,null);
        authenticate(f.peerStudent);
    }
    long concept(long inst,Long author) throws Exception {
        var profile=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        long id=f.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder().title("Same fictitious resource").category("Chemistry").explanation("Synthetic").createdByTeacher(profile).build()).getId();
        var map=new HashMap<String,Object>(Map.of("institutionId",inst,"authorAccountId",f.teacher.getId(),"deliveries",List.of(),"contexts",List.of()));
        if(author!=null)map.put("authorMembershipId",author);
        assertThat(r.service.apply(r.approved(ResourceReconciliationService.SourceKind.CONCEPT,id,f.json.writeValueAsString(map)))).isEqualTo("APPLIED");return id;
    }
    void authenticate(UserAccount account) {
        var p=new SessionPrincipal(account.getId(),account.getUsername(),UUID.randomUUID(),0,account.getRole(),false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,List.of()));
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    CoTeachingAssignment planned(long member){return service.planCoTeacher(a,c,member,at(10),r.until,at(3));}
    CoTeachingAssignment active(long member){return service.transitionCoTeacher(planned(member).id(),ACTIVE,at(10));}
    TeachingAssignment responsible(long member){var x=r.academic.planAssignment(a,c,member,at(10),r.until,at(3));return r.academic.transitionAssignment(x.id(),ACTIVE,at(10));}
    ResourceManagerGrant grant(Kind kind,long resource,Faculty faculty){return service.grantResource(a,kind,resource,teacher,faculty,admin,at(10),r.until,at(5));}
    Map<String,String> state(){return signatures("t11b6_co_assignments","t11b6_co_events","t11b6_teaching_slots","t11b6_resource_grants","t11b6_grant_events","t11b6_usage","teaching_assignments","teaching_assignment_events","system_logs","storage_usage");}

    @Test void manyCoTeachersAndOneResponsibleWithDistinctHistories() {
        long third=r.membership(a,f.outsider.getId(),DOCENTE);responsible(r.authorMember);active(teacher);active(third);
        assertThat(count("select count(*) from t11b6_teaching_slots where class_id=?",c)).isEqualTo(3);
        assertThatThrownBy(()->responsible(teacher)).isInstanceOf(DataAccessException.class);
        assertThat(count("select count(*) from teaching_assignments where class_id=? and activated_at is not null",c)).isEqualTo(1);
    }
    @Test void samePersonCannotHoldOverlappingFunctionsInEitherDirection() {
        responsible(r.authorMember);assertThatThrownBy(()->active(r.authorMember)).isInstanceOf(DataAccessException.class);
        active(teacher);assertThatThrownBy(()->responsible(teacher)).isInstanceOf(DataAccessException.class);
    }
    @Test void coTeacherDuplicatesAlsoRejectedDirectlyInPostgres() {
        active(teacher);var second=planned(teacher);
        assertThatThrownBy(()->sql().update("update t11b6_co_assignments set status='ACTIVE',state_since=?,activated_at=?,change_reason='ACTIVE' where id=?",Timestamp.from(at(10)),Timestamp.from(at(10)),second.id())).isInstanceOf(DataAccessException.class);
    }
    @Test void crossSchoolClassAndMembershipRejected() {
        assertThatThrownBy(()->service.planCoTeacher(b,c,foreignTeacher,at(10),r.until,at(3))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.planCoTeacher(a,c,foreignTeacher,at(10),r.until,at(3))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.planCoTeacher(a,r.foreignClass,teacher,at(10),r.until,at(3))).isInstanceOf(DataAccessException.class);
    }
    @Test void adminAloneStudentPendingAndSuspendedCannotActivateCoTeaching() {
        for(long member:List.of(admin,r.member))assertThatThrownBy(()->active(member)).isInstanceOf(DataAccessException.class);
        long pending=r.institutions.createMembership(a,f.outsider.getId(),t).id();assertThatThrownBy(()->active(pending)).isInstanceOf(DataAccessException.class);
        r.institutions.transitionMembership(teacher,InstitutionMembership.Status.SUSPENDED,at(6));assertThatThrownBy(()->active(teacher)).isInstanceOf(DataAccessException.class);
    }
    @Test void revokedFutureAndExpiredTeacherRolesCannotActivate() {
        long member=r.membership(a,f.outsider.getId(),DOCENTE);
        long role=count("select id from membership_role_grants where membership_id=? and role='DOCENTE'",member);
        r.institutions.revokeRole(role,at(6));assertThatThrownBy(()->active(member)).isInstanceOf(DataAccessException.class);
        r.institutions.grantRole(member,DOCENTE,at(20),at(30));assertThatThrownBy(()->active(member)).isInstanceOf(DataAccessException.class);
        var x=service.planCoTeacher(a,c,member,at(30),r.until,at(3));assertThatThrownBy(()->service.transitionCoTeacher(x.id(),ACTIVE,at(30))).isInstanceOf(DataAccessException.class);
    }
    @Test void cancellationAndEndPreserveOriginalWindowAndAllowAdjacentEpisodes() {
        var cancelled=service.transitionCoTeacher(planned(teacher).id(),CANCELLED,at(6));assertThat(cancelled.activatedAt()).isNull();
        var x=active(teacher);service.transitionCoTeacher(x.id(),ENDED,at(20));
        var next=service.planCoTeacher(a,c,teacher,at(20),r.until,at(20));service.transitionCoTeacher(next.id(),ACTIVE,at(20));
        assertThat(count("select count(*) from t11b6_co_events where entity_id=?",x.id())).isEqualTo(3);
        assertThat(f.app.getBean(DelegationRepository.class).coTeacher(x.id()).validUntil()).isEqualTo(r.until);
        assertThatThrownBy(()->service.transitionCoTeacher(x.id(),ACTIVE,at(30))).isInstanceOf(DataAccessException.class);
    }
    @Test void replacementPreservesIndependentCoTeachersAuthorsEnrollmentAndAccounts() {
        var old=responsible(r.authorMember);var co=active(teacher);long replacement=r.membership(a,f.outsider.getId(),DOCENTE);
        var before=signatures("t11b6_co_assignments","t11b6_co_events","enrollments","enrollment_events","academic_classes","user_accounts","evaluation_attempts","concept_contents","evaluations","t11b3_concept_resources","t11b3_evaluation_resources");
        r.academic.replaceResponsible(old.id(),replacement,at(20),r.until);
        assertThat(signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        assertThat(f.app.getBean(DelegationRepository.class).coTeacher(co.id())).isEqualTo(co);
    }
    @Test void replacementByExistingCoTeacherFailsAndRollsBackPredecessorHistory() {
        var old=responsible(r.authorMember);active(teacher);var before=state();
        assertThatThrownBy(()->r.academic.replaceResponsible(old.id(),teacher,at(20),r.until)).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void eachFacultyIndependentForBothTypedResources() {
        for(Kind kind:Kind.values())for(Faculty faculty:Faculty.values())grant(kind,kind==Kind.CONCEPT?concept:r.evaluation,faculty);
        assertThat(count("select count(*) from t11b6_resource_grants where institution_id=?",a)).isEqualTo(6);
        assertThat(count("select count(*) from t11b6_resource_grants where institution_id=? and faculty='EDIT_DRAFT'",a)).isEqualTo(2);
    }
    @Test void revocationDoesNotAlterOtherFacultiesOrAuthorHistory() {
        var edit=grant(Kind.CONCEPT,concept,Faculty.EDIT_DRAFT);var publish=grant(Kind.CONCEPT,concept,Faculty.PUBLISH);
        var before=signatures("concept_contents","t11b3_concept_resources","t11b3_concept_revisions");
        var revoked=service.revokeResource(edit.id(),admin,at(20));
        assertThat(revoked.status()).isEqualTo(Status.REVOKED);assertThat(revoked.revokedBy()).isEqualTo(f.peerStudent.getId());
        assertThat(revoked.validFrom()).isEqualTo(edit.validFrom());assertThat(revoked.validUntil()).isEqualTo(edit.validUntil());
        assertThat(f.app.getBean(DelegationRepository.class).grant(publish.id())).isEqualTo(publish);
        assertThat(count("select count(*) from t11b6_grant_events where entity_id=?",edit.id())).isEqualTo(2);
        assertThat(signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.EDIT_DRAFT,admin,at(20),r.until,at(20));
        assertThatThrownBy(()->grant(Kind.CONCEPT,concept,Faculty.EDIT_DRAFT)).isInstanceOf(DataAccessException.class);
    }
    @Test void grantsCannotCrossResourceRecipientOrAdministratorInstitutions() {
        assertThatThrownBy(()->grant(Kind.CONCEPT,foreignConcept,Faculty.PUBLISH)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,foreignTeacher,Faculty.ASSIGN,admin,at(10),r.until,at(5))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.ASSIGN,foreignAdmin,at(10),r.until,at(5))).isInstanceOf(DataAccessException.class);
    }
    @Test void absentTeacherRoleAndInvalidAdministratorProvenanceReject() {
        assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,r.member,Faculty.ASSIGN,admin,at(10),r.until,at(5))).isInstanceOf(DataAccessException.class);
        authenticate(f.teacher);assertThatThrownBy(()->grant(Kind.CONCEPT,concept,Faculty.PUBLISH)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.PUBLISH,r.authorMember,at(10),r.until,at(5))).isInstanceOf(DataAccessException.class);
        SecurityContextHolder.clearContext();assertThatThrownBy(()->grant(Kind.CONCEPT,concept,Faculty.PUBLISH)).isInstanceOf(IllegalStateException.class);
    }
    @Test void futureExpiredRevokedAdminAndTeacherGrantsRejectDesignation() {
        long adminRole=count("select id from membership_role_grants where membership_id=? and role='ADMIN_INSTITUCION'",admin);
        r.institutions.revokeRole(adminRole,at(3));
        assertThatThrownBy(()->grant(Kind.CONCEPT,concept,Faculty.PUBLISH)).isInstanceOf(DataAccessException.class);
        r.institutions.grantRole(admin,ADMIN_INSTITUCION,at(20),at(30));
        for(int when:List.of(10,30))assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.PUBLISH,admin,at(when),r.until,at(when))).isInstanceOf(DataAccessException.class);
        r.institutions.grantRole(admin,ADMIN_INSTITUCION,at(30),null);
        long teacherRole=count("select id from membership_role_grants where membership_id=? and role='DOCENTE'",teacher);
        r.institutions.revokeRole(teacherRole,at(4));
        assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.PUBLISH,admin,at(30),r.until,at(30))).isInstanceOf(DataAccessException.class);
    }
    @Test void sameFacultyOverlapRejectedForConceptAndEvaluationAndOtherFacultiesRemainAbsent() {
        for(Kind kind:Kind.values()) {
            long resource=kind==Kind.CONCEPT?concept:r.evaluation;grant(kind,resource,Faculty.EDIT_DRAFT);
            assertThatThrownBy(()->grant(kind,resource,Faculty.EDIT_DRAFT)).isInstanceOf(DataAccessException.class);
        }
        assertThat(count("select count(*) from t11b6_resource_grants where institution_id=? and faculty in ('PUBLISH','ASSIGN')",a)).isZero();
    }
    @Test void revocationRequiresCurrentSameSchoolAdminButDoesNotRequireRecipientStillActive() {
        var g=grant(Kind.EVALUATION,r.evaluation,Faculty.ASSIGN);
        assertThatThrownBy(()->service.revokeResource(g.id(),foreignAdmin,at(20))).isInstanceOf(DataAccessException.class);
        authenticate(f.teacher);assertThatThrownBy(()->service.revokeResource(g.id(),admin,at(20))).isInstanceOf(DataAccessException.class);
        authenticate(f.peerStudent);r.institutions.transitionMembership(teacher,InstitutionMembership.Status.ENDED,at(15));
        assertThat(service.revokeResource(g.id(),admin,at(20)).status()).isEqualTo(Status.REVOKED);
    }
    @Test void revocationBeforeScheduledStartRetainsEvidenceAndAllowsNewGrant() {
        var g=service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.ASSIGN,admin,at(100),r.until,at(5));service.revokeResource(g.id(),admin,at(20));
        grant(Kind.CONCEPT,concept,Faculty.ASSIGN);
        assertThat(count("select count(*) from t11b6_grant_events where entity_id=?",g.id())).isEqualTo(2);
    }
    @Test void temporalValidationAndTypedReferencesCannotBeBypassed() {
        assertThatThrownBy(()->service.planCoTeacher(a,c,teacher,t,r.until.plusSeconds(1),at(3))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.ASSIGN,admin,at(10),at(10),at(5))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.grantResource(a,Kind.CONCEPT,concept,teacher,Faculty.ASSIGN,admin,at(4),r.until,at(5))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("insert into t11b6_resource_grants(institution_id,concept_id,evaluation_id,membership_id,faculty,valid_from,valid_until,granted_at,granted_by,grantor_membership_id,reason) values(?,?,?,?, 'PUBLISH',?,?,?, ?,?,'DESIGNATED')",a,concept,r.evaluation,teacher,Timestamp.from(at(10)),Timestamp.from(r.until),Timestamp.from(at(5)),f.peerStudent.getId(),admin)).isInstanceOf(DataAccessException.class);
    }
    @Test void historiesSourcesSlotsAndGrantIdentityProtectedFromDirectMutation() {
        active(teacher);var g=grant(Kind.CONCEPT,concept,Faculty.EDIT_DRAFT);
        for(String q:List.of("update t11b6_resource_grants set faculty='PUBLISH' where id="+g.id(),"delete from t11b6_resource_grants",
                "update t11b6_co_assignments set membership_id="+r.authorMember,"update t11b6_co_events set snapshot='{}'",
                "delete from t11b6_grant_events","truncate t11b6_co_assignments cascade","delete from t11b6_teaching_slots",
                "insert into t11b6_grant_events(entity_id,institution_id,operation,snapshot) values("+g.id()+","+a+",'UPDATE','{}')"))
            assertThatThrownBy(()->sql().execute(q)).isInstanceOf(DataAccessException.class);
    }
    @Test void auditFailureRevertsGrantHistoryQuotaAndReplacement() {
        var old=responsible(r.authorMember);active(teacher);long next=r.membership(a,f.outsider.getId(),DOCENTE);var before=state();
        assertThatThrownBy(()->previous.base.tx().executeWithoutResult(s->{
            grant(Kind.CONCEPT,concept,Faculty.EDIT_DRAFT);r.academic.replaceResponsible(old.id(),next,at(20),r.until);
            sql().execute("alter table system_logs rename to unavailable_b6_audit");
            grant(Kind.CONCEPT,concept,Faculty.PUBLISH);
        })).isInstanceOf(AuditUnavailableException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void quotaFailureRevertsCoTeachingAndGrantsIncludingAudit() {
        var before=state();assertThatThrownBy(()->previous.base.tx().executeWithoutResult(s->{
            active(teacher);sql().update("update t11b6_usage set rows_used=100000 where id=1");grant(Kind.CONCEPT,concept,Faculty.ASSIGN);
        })).isInstanceOf(DataAccessException.class);assertThat(state()).isEqualTo(before);
        previous.base.tx().executeWithoutResult(s->{active(teacher);grant(Kind.CONCEPT,concept,Faculty.ASSIGN);s.setRollbackOnly();});assertThat(state()).isEqualTo(before);
    }
    @Test void auditHasActorSafeTypedTargetAndCorrelation() {
        var g=grant(Kind.CONCEPT,concept,Faculty.EDIT_DRAFT);
        var log=sql().queryForMap("select * from system_logs where target_type='ResourceManagerGrant' and target_id=?",g.id());
        assertThat(log).containsEntry("actor_user_id",f.peerStudent.getId()).containsEntry("outcome","SUCCESS").containsEntry("metadata","institutionId="+a);
        assertThat(log.get("correlation_id")).isNotNull();assertThat(log.toString()).doesNotContain("Synthetic",WhiteboardSecurityTransportDbTest.PASSWORD);
    }
    @Test void newRelationsNeverGrantLegacyAuthorEditingStudentAccessOrGrading() throws Exception {
        SecurityContextHolder.clearContext();String token=f.login(f.otherTeacher);var before=signatures("user_accounts","student_profiles","evaluation_attempts","evaluations","concept_contents");
        active(teacher);authenticate(f.peerStudent);grant(Kind.CONCEPT,concept,Faculty.EDIT_DRAFT);grant(Kind.EVALUATION,r.evaluation,Faculty.ASSIGN);
        SecurityContextHolder.clearContext();
        var response=f.request("GET","/api/evaluations/teacher/"+r.evaluation,token,null);
        assertThat(response.statusCode()).isEqualTo(400);assertThat(response.body()).contains("No tienes permiso para modificar esta evaluación.");
        assertThat(signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        assertThat(count("select count(*) from t11b6_resource_grants where membership_id=?",r.authorMember)).isZero();
    }
    @Test void successorRepeatsAndDetectsPredecessorAndOwnDivergenceWithoutRepair() {
        var before=state();var certs=versions();sql().execute(expansion);assertThat(state()).isEqualTo(before);assertThat(versions()).isEqualTo(certs);
        for(String q:List.of("alter table teaching_assignments disable trigger t11b6_slot_sync","alter table t11b6_resource_grants disable trigger t11b6_guard","alter table academic_classes disable trigger t11b2_guard")) {
            previous.base.tx().executeWithoutResult(s->{sql().execute(q);assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        }
        sql().execute(expansion);
    }
    @Test void responsibleAndCoTeacherRaceAcrossTwoProcessesHasOneCommitAndOne23P01() throws Exception {
        Path dir=Files.createDirectories(Path.of("target","t11b6-processes",f.schema));Path go=dir.resolve("go");var children=new ArrayList<Process>();
        try(Connection barrier=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            barrier.setAutoCommit(false);
            try {
                for(int i=1;i<=2;i++) {
                    var cmd=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),TeachingSlotProcess.class.getName(),
                        f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,i==1?"RESPONSABLE":"CO_DOCENTE",""+a,""+c,""+teacher,
                        dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString());
                    children.add(new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready")))&&System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(Files.exists(dir.resolve("1.ready"))).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();
                barrier.createStatement().execute("select id from institution_memberships where id="+teacher+" for update");Files.writeString(go,"go");
                List<Integer> pids=List.of();deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline) {pids=sql().queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like 'insert into %assignments(%'",Integer.class);if(pids.size()==2)break;Thread.sleep(20);}
                assertThat(pids).hasSize(2);barrier.commit();
                for(Process p:children){assertThat(p.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
                var results=List.of(Files.readString(dir.resolve("1.result")),Files.readString(dir.resolve("2.result")));
                assertThat(results).containsExactlyInAnyOrder("COMMITTED","REJECTED:23P01");
                assertThat(count("select count(*) from t11b6_teaching_slots where class_id=? and membership_id=?",c,teacher)).isEqualTo(1);
                Files.writeString(dir.resolve("evidence.json"),f.json.writeValueAsString(Map.of("osPids",children.stream().map(Process::pid).toList(),"blockedPostgresPids",pids,"results",results,"constraint","class/membership/effective exclusion across RESPONSABLE and CO_DOCENTE")));
            } finally {barrier.rollback();for(Process p:children)if(p.isAlive())p.destroyForcibly();}
        }
    }
    @Test void nativeDumpRestoreRecertifiesChainAndPreservesEveryFixtureTable() throws Exception {
        var old=responsible(r.authorMember);active(teacher);var g=grant(Kind.CONCEPT,concept,Faculty.EDIT_DRAFT);service.revokeResource(g.id(),admin,at(20));
        long next=r.membership(a,f.outsider.getId(),DOCENTE);r.academic.replaceResponsible(old.id(),next,at(20),r.until);
        var tables=sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' order by relname",String.class);
        var before=signatures(tables.toArray(String[]::new));
        Path dir=Files.createDirectories(Path.of("target","t11b6-native",f.schema));String db="chemicallab_t11b6_restore_"+UUID.randomUUID().toString().replace("-","");
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));boolean created=false;
        try {
            r.nativeTool("pg_dump",args,List.of("-Fc","-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            r.nativeTool("createdb",args,List.of(db),dir);created=true;
            r.nativeTool("pg_restore",args,List.of("--exit-on-error","--single-transaction","-d",db,dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            try(Connection connection=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement q=connection.createStatement()) {
                q.execute("set search_path to "+f.schema+",public");q.execute(expansion);
                Map<String,String> recovered=new TreeMap<>();for(String table:tables)try(var rows=q.executeQuery(r.signature(table))){rows.next();recovered.put(table,rows.getString(1));}
                assertThat(recovered).isEqualTo(before);
                for(String mutation:List.of("update system_logs set target_type='REST'","delete from system_logs","truncate system_logs cascade"))assertThatThrownBy(()->q.execute(mutation)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
                q.execute("alter table teaching_assignments disable trigger t11b6_slot_sync");assertThatThrownBy(()->q.execute(expansion)).isInstanceOf(SQLException.class).hasMessageContaining("divergence");
                q.execute("alter table teaching_assignments enable trigger t11b6_slot_sync");q.execute(expansion);
                Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source",before,"restored",recovered,"tables",tables.size(),"certifier","B6 v1 frozen chain; all predecessor ledgers intact")));
            }
        } finally {if(created)r.nativeTool("dropdb",args,List.of(db),dir);}
    }
}

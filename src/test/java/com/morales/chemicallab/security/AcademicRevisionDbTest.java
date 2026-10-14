package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.AuditUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.service.AcademicRevisionMaintenanceService.Kind.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcademicRevisionDbTest {
    final GlobalAccessIdentifierDbTest previous=new GlobalAccessIdentifierDbTest();
    final ResourceReconciliationDbTest r=previous.r;
    final WhiteboardSecurityTransportDbTest f=previous.f;
    AcademicRevisionMaintenanceService service;
    AcademicRevisionRepository repository;
    String expansion;
    JdbcTemplate sql(){return f.sql();}
    long count(String q,Object... args){return sql().queryForObject(q,Long.class,args);}
    UUID op(){return UUID.randomUUID();}
    @BeforeAll void start() throws Exception {
        previous.start();service=f.app.getBean(AcademicRevisionMaintenanceService.class);repository=f.app.getBean(AcademicRevisionRepository.class);
        expansion=r.script("t11b8-v1");var before=previous.previous.signatures("t11b7_schema_version","t11b6_schema_version","t11b3_schema_version");
        previous.previous.previous.base.tx().executeWithoutResult(s->{sql().execute(expansion);s.setRollbackOnly();});
        assertThat(sql().queryForObject("select to_regclass(current_schema()||'.t11b8_schema_version') is null",Boolean.class)).isTrue();
        sql().execute(expansion);sql().execute(expansion);
        assertThat(previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
    }
    @BeforeEach void fixture() throws Exception {previous.fixture();SecurityContextHolder.clearContext();}
    @AfterEach void close(){SecurityContextHolder.clearContext();f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    Map<String,String> state(){return previous.previous.signatures("t11b8_resources","t11b8_revisions","t11b8_deliveries","t11b8_recipients","t11b8_budgets","t11b8_fixture_attempts","t11b8_events","t11b8_operations","t11b8_creations","storage_usage","system_logs");}
    String payload(int maximum,String title) {
        return """
            {"resource":{"title":"%s","max_attempts":%d,"time_limit_minutes":30,
              "allow_chemical_calculator":false,"allow_periodic_table":false,"track_tab_exit":false,
              "question_display_mode":"ALL_AT_ONCE","randomize_questions":false},
             "questions":[{"id":17,"question_text":"Synthetic open question","question_type":"OPEN_TEXT",
                "points":1,"order_index":0,"expected_answer":"Synthetic criterion","required":true}],"options":[]}
            """.formatted(title,maximum);
    }
    long fresh(int maximum){return service.createNew(EVALUATION,r.institution,f.teacher.getId(),r.authorMember,payload(maximum,"New evaluation"),op());}
    long draft(long resource){return count("select id from t11b8_revisions where resource_id=? order by revision_number desc limit 1",resource);}
    AcademicRevision published(long resource){long id=draft(resource);var d=repository.revision(id);return service.publish(id,d.version(),op());}
    long delivered(AcademicRevision rev,long clazz){return service.assign(rev.id(),r.institution,clazz,Instant.now().minusSeconds(60),Instant.now().plusSeconds(600),op());}
    long admitted(long delivery,long episode){return service.recipient(delivery,f.student.getId(),episode,0,true,op());}
    AcademicRevision successor(AcademicRevision base,String title){var d=service.nextDraft(base.id(),op());d=service.edit(d.id(),1,payload(3,title),op());return service.publish(d.id(),d.version(),op());}
    long legacy(){return service.attachLegacy(EVALUATION,r.evaluation,op());}
    AcademicRevision legacyDraft(long res){long b=count("select id from t11b3_evaluation_revisions where evaluation_id=? order by id limit 1",r.evaluation);return service.draftFromLegacy(res,b,op());}
    AcademicRevision legacyPublished(long res){var d=legacyDraft(res);d=service.edit(d.id(),1,payload(3,"Legacy academic successor"),op());return service.publish(d.id(),2,op());}

    @Test void differentAcademicDraftPublishesAndOldRevisionIsImmutable() {
        long res=fresh(3);var old=published(res);var d=service.nextDraft(old.id(),op());
        assertThat(d.payload()).isEqualTo(old.payload());assertThat(d.parentId()).isEqualTo(old.id());
        d=service.edit(d.id(),1,payload(3,"Different chemistry"),op());assertThat(d.payload()).isNotEqualTo(old.payload());
        var next=service.publish(d.id(),2,op());assertThat(next.status()).isEqualTo("PUBLISHED");assertThat(next.number()).isEqualTo(2);assertThat(next.publishedAt()).isNotNull();
        assertThat(repository.revision(old.id())).isEqualTo(old);
        var before=state();assertThatThrownBy(()->service.edit(old.id(),old.version(),payload(3,"Mutation"),op())).isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->sql().update("update t11b8_revisions set payload=payload||'{\"partial\":true}',payload_checksum=md5((payload||'{\"partial\":true}')::text),version=version+1 where id=?",old.id())).isInstanceOf(DataAccessException.class);
    }
    @Test void publicationCannotIncludePartialConcurrentEditInSql() {
        long res=fresh(3);long id=draft(res);var before=state();
        assertThatThrownBy(()->sql().update("update t11b8_revisions set status='PUBLISHED',payload=?::jsonb,payload_checksum=md5(?::jsonb::text),version=2 where id=?",payload(3,"Partial"),payload(3,"Partial"),id)).isInstanceOf(DataAccessException.class).hasMessageContaining("cannot edit");
        assertThat(state()).isEqualTo(before);
    }
    @Test void severalClassesAndRevisionsConsumeOneF09Budget() {
        long res=fresh(3);var first=published(res);var second=successor(first,"Revision two");
        long a=admitted(delivered(first,r.classId),r.episode),b=admitted(delivered(second,r.otherClass),r.otherEpisode);
        assertThat(count("select count(*) from t11b8_budgets where resource_id=?",res)).isEqualTo(1);
        long one=service.reserveFixtureAttempt(a,op());assertThatThrownBy(()->service.reserveFixtureAttempt(b,op())).isInstanceOf(DataAccessException.class);
        service.finishFixtureAttempt(one,1,"{\"17\":\"answer one\"}",18,op());
        long two=service.reserveFixtureAttempt(b,op());service.finishFixtureAttempt(two,1,"{\"17\":\"answer two\"}",19,op());
        long three=service.reserveFixtureAttempt(a,op());service.finishFixtureAttempt(three,1,"{}",20,op());
        assertThat(sql().queryForList("select attempt_number from t11b8_fixture_attempts where resource_id=? order by attempt_number",Integer.class,res)).containsExactly(1,2,3);
        assertThatThrownBy(()->service.reserveFixtureAttempt(a,op())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.reserveFixtureAttempt(b,op())).isInstanceOf(DataAccessException.class);
    }
    @Test void successorPreservesAttemptsAnswersGradeConfigurationAndDeliveries() {
        long res=fresh(3);var first=published(res);long delivery=delivered(first,r.classId),rec=admitted(delivery,r.episode);
        long attempt=service.reserveFixtureAttempt(rec,op());service.finishFixtureAttempt(attempt,1,"{\"17\":\"untouched\"}",17,op());
        var old=previous.previous.signatures("t11b8_deliveries","t11b8_recipients","t11b8_fixture_attempts","evaluations","evaluation_attempts","evaluation_answers","evaluation_attempt_adjustments","user_accounts");
        var second=successor(first,"Different question version");assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(previous.previous.signatures(old.keySet().toArray(String[]::new))).isEqualTo(old);
        assertThat(count("select revision_id from t11b8_deliveries where id=?",delivery)).isEqualTo(first.id());
        assertThat(count("select revision_id from t11b8_fixture_attempts where id=?",attempt)).isEqualTo(first.id());
    }
    @Test void explicitNewEvaluationHasDistinctRootAndTechnicalCopyDoesNot() {
        long first=fresh(3),second=fresh(3);assertThat(first).isNotEqualTo(second);
        assertThat(count("select evaluation_id from t11b8_resources where id=?",first)).isNotEqualTo(count("select evaluation_id from t11b8_resources where id=?",second));
        var published=published(first);var copy=service.nextDraft(published.id(),op());copy=service.publish(copy.id(),1,op());
        assertThat(copy.resourceId()).isEqualTo(first);assertThat(copy.payload()).isEqualTo(published.payload());
        long a=admitted(delivered(published,r.classId),r.episode);long b=admitted(delivered(published(second),r.classId),r.episode);
        service.reserveFixtureAttempt(a,op());service.reserveFixtureAttempt(b,op());
        assertThat(count("select count(*) from t11b8_fixture_attempts where attempt_number=1")).isGreaterThanOrEqualTo(2);
    }
    @Test void legacyRootCannotBeLaunderedByAnotherOriginOrFreshRegistration() {
        long res=legacy();assertThat(count("select legacy_group_id from t11b8_resources where id=?",res)).isEqualTo(count("select id from t11b3_opportunity_groups where evaluation_id=?",r.evaluation));
        var before=state();
        assertThatThrownBy(()->sql().update("update t11b8_resources set creation='EXPLICIT_NEW',legacy_group_id=null,version=version+1 where id=?",res)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("insert into t11b8_resources(evaluation_id,institution_id,author_account_id,author_membership_id,creation,max_attempts,operation_id) values(?,?,?,?,'EXPLICIT_NEW',3,?)",r.evaluation,r.institution,f.teacher.getId(),r.authorMember,op())).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void legacyBudgetsRemainObservationsAndLegacyActiveLocksAreAuthoritative() {
        long attempt=r.attempt(1,AttemptStatus.IN_PROGRESS,r.assignment,false);long res=legacy();var rev=legacyPublished(res);
        long rec=admitted(delivered(rev,r.classId),r.episode);
        var old=previous.previous.signatures("evaluation_attempts","t11b3_opportunity_groups","t11b3_account_attempt_budgets","t11b3_attempt_consumptions");
        assertThatThrownBy(()->service.reserveFixtureAttempt(rec,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("legacy writers");
        assertThatThrownBy(()->sql().update("insert into t11b8_budgets values(?,?)",res,f.student.getId())).isInstanceOf(DataAccessException.class);
        assertThat(count("select id from evaluation_attempts where id=?",attempt)).isEqualTo(attempt);
        assertThat(previous.previous.signatures(old.keySet().toArray(String[]::new))).isEqualTo(old);
    }
    @Test void legacyLiveClosureAndSnapshotClosureCannotReopenThroughNewDelivery() {
        long res=legacy();var rev=legacyPublished(res);long delivery=delivered(rev,r.classId);admitted(delivery,r.episode);
        sql().update("update evaluations set status='ARCHIVED' where id=?",r.evaluation);
        assertThatThrownBy(()->service.assign(rev.id(),r.institution,r.otherClass,Instant.now(),null,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("closed root");
        service.resourceState(res,1,"AVAILABLE",true,false,op());sql().update("update evaluations set status='PUBLISHED' where id=?",r.evaluation);
        assertThatThrownBy(()->service.resourceState(res,2,"AVAILABLE",false,false,op())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.assign(rev.id(),r.institution,r.otherClass,Instant.now(),null,op())).isInstanceOf(DataAccessException.class);
    }
    @Test void referencesAcrossInstitutionsAndUnpublishedRevisionsAreRejected() {
        long res=fresh(3);long id=draft(res);
        assertThatThrownBy(()->service.assign(id,r.institution,r.classId,Instant.now(),null,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("published");
        var rev=published(res);
        assertThatThrownBy(()->service.assign(rev.id(),r.foreign,r.foreignClass,Instant.now(),null,op())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.assign(rev.id(),r.institution,r.foreignClass,Instant.now(),null,op())).isInstanceOf(DataAccessException.class);
        long d=delivered(rev,r.classId);
        assertThatThrownBy(()->admitted(d,r.otherEpisode)).isInstanceOf(DataAccessException.class).hasMessageContaining("enrollment");
        assertThatThrownBy(()->service.recipient(d,f.peerStudent.getId(),r.episode,0,true,op())).isInstanceOf(DataAccessException.class);
    }
    @Test void readmissionKeepsOneRecipientHistoryAndConsumedBudget() {
        long res=fresh(1);var rev=published(res);long d=delivered(rev,r.classId),rec=admitted(d,r.episode);
        long a=service.reserveFixtureAttempt(rec,op());service.finishFixtureAttempt(a,1,"{}",20,op());
        service.recipient(d,f.student.getId(),r.episode,1,false,op());
        r.academic.transitionEnrollment(r.episode,Enrollment.Status.WITHDRAWN,Instant.now().minusSeconds(30));
        var e=r.academic.createEnrollment(r.institution,r.classId,r.member,Instant.now().minusSeconds(20),r.until,Instant.now().minusSeconds(21));
        r.academic.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,Instant.now().minusSeconds(20));
        assertThat(service.recipient(d,f.student.getId(),e.id(),2,true,op())).isEqualTo(rec);
        assertThat(count("select count(*) from t11b8_recipients where delivery_id=?",d)).isEqualTo(1);
        assertThat(count("select count(*) from t11b8_events where entity_table='t11b8_recipients' and entity_id=?",rec)).isEqualTo(3);
        assertThatThrownBy(()->service.reserveFixtureAttempt(rec,op())).isInstanceOf(DataAccessException.class);
    }
    @Test void allWindowsFutureEmptyAndNoDeadlineMustCloseBeforeCorrection() {
        long res=fresh(3);var rev=published(res);long a=delivered(rev,r.classId);
        long b=service.assign(rev.id(),r.institution,r.otherClass,Instant.now().plusSeconds(600),null,op());
        service.closeDelivery(a,1,op());assertThatThrownBy(()->service.resourceState(res,1,"AVAILABLE",true,false,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("all delivery");
        service.closeDelivery(b,1,op());service.resourceState(res,1,"AVAILABLE",true,true,op());
        assertThatThrownBy(()->service.resourceState(res,2,"AVAILABLE",false,false,op())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->admitted(a,r.episode)).isInstanceOf(DataAccessException.class);
        var newer=service.nextDraft(rev.id(),op());newer=service.publish(newer.id(),1,op());var frozen=newer;
        assertThatThrownBy(()->service.assign(frozen.id(),r.institution,r.classId,Instant.now(),null,op())).isInstanceOf(DataAccessException.class);
    }
    @Test void unfinishedGradePreventsExposureEvenAfterRootCloses() {
        long res=fresh(3);var rev=published(res);long d=delivered(rev,r.classId),rec=admitted(d,r.episode);
        long a=service.reserveFixtureAttempt(rec,op());service.closeDelivery(d,1,op());
        assertThatThrownBy(()->service.resourceState(res,1,"AVAILABLE",true,true,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("correction");
        service.finishFixtureAttempt(a,1,"{}",18,op());service.resourceState(res,1,"AVAILABLE",true,true,op());
        assertThat(count("select count(*) from t11b8_resources where id=? and correction_exposed",res)).isEqualTo(1);
    }
    @Test void availabilityArchivePreservesEvidenceAndDoesNotReturnConsumption() {
        long res=fresh(3);var rev=published(res);long rec=admitted(delivered(rev,r.classId),r.episode);long a=service.reserveFixtureAttempt(rec,op());
        var old=previous.previous.signatures("t11b8_revisions","t11b8_fixture_attempts","t11b8_deliveries","t11b8_budgets");
        service.resourceState(res,1,"ARCHIVED",false,false,op());assertThat(previous.previous.signatures(old.keySet().toArray(String[]::new))).isEqualTo(old);
        assertThatThrownBy(()->service.resourceState(res,2,"AVAILABLE",false,false,op())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("delete from t11b8_fixture_attempts where id=?",a)).isInstanceOf(DataAccessException.class);
    }
    @Test void managerOrResponsibleChangesCannotRewriteAuthorOrPriorContent() {
        long res=fresh(3);var rev=published(res);var before=state();
        assertThatThrownBy(()->sql().update("update t11b8_resources set author_account_id=?,author_membership_id=null,version=version+1 where id=?",f.otherTeacher.getId(),res)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update t11b8_resources set institution_id=?,author_membership_id=null,version=version+1 where id=?",r.foreign,res)).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);assertThat(repository.revision(rev.id())).isEqualTo(rev);
    }
    @Test void outdatedVersionsAndExactRetriesHaveNoPartialOrDuplicateEffects() {
        UUID creation=op();String p=payload(3,"Retry creation");long res=service.createNew(EVALUATION,r.institution,f.teacher.getId(),r.authorMember,p,creation);
        assertThat(service.createNew(EVALUATION,r.institution,f.teacher.getId(),r.authorMember,p,creation)).isEqualTo(res);
        long id=draft(res);UUID edit=op();service.edit(id,1,payload(3,"Edited"),edit);var before=state();
        service.edit(id,1,payload(3,"Edited"),edit);assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->service.edit(id,1,payload(3,"Stale"),op())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Stale");
        assertThatThrownBy(()->service.edit(id,1,payload(3,"Different"),edit)).isInstanceOf(IllegalArgumentException.class);
        UUID publish=op();service.publish(id,2,publish);before=state();service.publish(id,2,publish);assertThat(state()).isEqualTo(before);
    }
    @Test void attemptsRecipientAndAssignmentRetriesPreserveConsumptionAndAudit() {
        long res=fresh(3);var rev=published(res);Instant from=Instant.now().minusSeconds(20),due=Instant.now().plusSeconds(600);UUID assign=op();
        long d=service.assign(rev.id(),r.institution,r.classId,from,due,assign);assertThat(service.assign(rev.id(),r.institution,r.classId,from,due,assign)).isEqualTo(d);
        UUID recipient=op();long rec=service.recipient(d,f.student.getId(),r.episode,0,true,recipient);service.recipient(d,f.student.getId(),r.episode,0,true,recipient);
        UUID start=op();long a=service.reserveFixtureAttempt(rec,start);var before=state();assertThat(service.reserveFixtureAttempt(rec,start)).isEqualTo(a);assertThat(state()).isEqualTo(before);
        UUID end=op();service.finishFixtureAttempt(a,1,"{}",20,end);before=state();service.finishFixtureAttempt(a,1,"{}",20,end);assertThat(state()).isEqualTo(before);
    }
    @Test void revisionCannotIncreaseOrPartitionMaximum() {
        long res=fresh(3);long id=draft(res);var before=state();
        assertThatThrownBy(()->service.edit(id,1,payload(4,"Increased"),op())).isInstanceOf(DataAccessException.class).hasMessageContaining("maximum");
        assertThatThrownBy(()->sql().update("update t11b8_resources set max_attempts=4,version=2 where id=?",res)).isInstanceOf(DataAccessException.class);assertThat(state()).isEqualTo(before);
    }
    @Test void invalidConfigurationOptionsAndPayloadLimitsPreserveDraft() {
        long res=fresh(3);long id=draft(res);var before=state();
        for(String p:List.of(payload(3,"Bad").replace("OPEN_TEXT","INVALID_TYPE"),payload(11,"Bad"),payload(3,"Bad").replace("30,","241,")))
            assertThatThrownBy(()->service.edit(id,1,p,op())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.edit(id,1,"{\"resource\":{},\"resource\":{}}",op())).isInstanceOf(IllegalArgumentException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void atomicAuditFailureRollsBackPublicationHistoryAndQuota() {
        long res=fresh(3);long id=draft(res);var before=state();
        sql().execute("alter table system_logs rename to system_logs_unavailable");
        try {assertThatThrownBy(()->service.publish(id,1,op())).isInstanceOf(AuditUnavailableException.class);}
        finally {sql().execute("alter table system_logs_unavailable rename to system_logs");}
        assertThat(state()).isEqualTo(before);assertThat(repository.revision(id).status()).isEqualTo("DRAFT");service.publish(id,1,op());
    }
    @Test void quotaFailureRollsBackEditedPayloadHistoryOperationAndAudit() {
        long res=fresh(3);long id=draft(res);var before=state();long cap=count("select maximum from storage_limits where resource='REVISION_COUNT'");
        sql().update("update storage_limits set maximum=1 where resource='REVISION_COUNT'");
        try {assertThatThrownBy(()->service.edit(id,1,payload(3,"Larger content"),op())).isInstanceOf(DataAccessException.class).hasMessageContaining("STORAGE_QUOTA_EXCEEDED");}
        finally {sql().update("update storage_limits set maximum=? where resource='REVISION_COUNT'",cap);}
        assertThat(state()).isEqualTo(before);service.edit(id,1,payload(3,"Larger content"),op());
    }
    @Test void auditFailureOnFreshAcademicCreationAlsoRollsBackOriginalAnchor() {
        var old=previous.previous.signatures("evaluations","t11b8_resources","t11b8_revisions","storage_usage");
        sql().execute("alter table system_logs rename to system_logs_unavailable");
        try {assertThatThrownBy(()->fresh(3)).isInstanceOf(AuditUnavailableException.class);}
        finally {sql().execute("alter table system_logs_unavailable rename to system_logs");}
        assertThat(previous.previous.signatures(old.keySet().toArray(String[]::new))).isEqualTo(old);
    }
    @Test void generatedHistoryCannotBeForgedDeletedOrTruncated() {
        long res=fresh(3);var before=state();
        for(String q:List.of("delete from t11b8_events","update t11b8_events set snapshot='{}'","truncate t11b8_events cascade",
            "insert into t11b8_events(resource_id,entity_table,entity_id,snapshot) values("+res+",'fake',1,'{}')","delete from t11b8_operations","truncate t11b8_budgets cascade"))
            assertThatThrownBy(()->sql().execute(q)).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void publicRoleContextCannotInvokeInternalMaintainerAndAuditStaysSafe() {
        previous.previous.authenticate(f.peerStudent);assertThatThrownBy(()->fresh(3)).isInstanceOf(IllegalStateException.class).hasMessageContaining("T12");
        SecurityContextHolder.clearContext();long res=fresh(3);
        var log=sql().queryForMap("select * from system_logs where target_type='AcademicResource' and target_id=? order by id desc limit 1",res);
        assertThat(log.get("outcome")).isEqualTo("SUCCESS");assertThat(log.get("actor_user_id")).isNull();
        assertThat(log.toString()).doesNotContain("Synthetic criterion","New evaluation","FIXTURE",WhiteboardSecurityTransportDbTest.PASSWORD);
    }
    @Test void conceptMaterialBytesListsAndPreviousDeliverySurviveAcademicEdit() throws Exception {
        long concept=previous.previous.concept(r.institution,r.authorMember);
        // New immutable B3 baseline after a material is deliberately created in the source.
        var content=f.app.getBean(ConceptContentRepository.class).findById(concept).orElseThrow();
        byte[] bytes;try(var doc=new org.apache.pdfbox.pdmodel.PDDocument();var out=new java.io.ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());doc.save(out);bytes=out.toByteArray();
        }
        f.app.getBean(ConceptMaterialRepository.class).saveAndFlush(ConceptMaterial.builder().conceptContent(content).type(MaterialType.FILE)
            .originalFileName("fixture.pdf").contentType("application/pdf").fileData(bytes).fileSize((long)bytes.length).build());
        // B3 revisions remain unchanged; a NEW concept has an explicit anchor and owns copied bytes.
        String payload=sql().queryForObject("select (t11b3_snapshot('CONCEPT',?)-'assignments')::text",String.class,concept);
        long res=service.createNew(CONCEPT,r.institution,f.teacher.getId(),r.authorMember,payload,op());var first=published(res);long delivery=delivered(first,r.classId);
        var node=f.json.readTree(first.payload());var material=node.path("materials").get(0);assertThat(HexFormat.of().parseHex(material.path("file_data").asText().substring(2))).isEqualTo(bytes);
        var before=previous.previous.signatures("concept_materials","t11b8_deliveries","t11b8_recipients");
        var next=service.nextDraft(first.id(),op());var changed=f.json.readTree(next.payload());((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("resource")).put("explanation","Different conceptual explanation");
        service.edit(next.id(),1,changed.toString(),op());service.publish(next.id(),2,op());
        assertThat(repository.revision(first.id())).isEqualTo(first);assertThat(count("select revision_id from t11b8_deliveries where id=?",delivery)).isEqualTo(first.id());
        assertThat(previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
    }
    @Test void conceptEmptyContentBadLinksAndInvalidBytesCannotPublish() {
        String p="{\"resource\":{\"title\":\"Concept\",\"category\":\"Chemistry\"},\"materials\":[],\"steps\":[],\"points\":[],\"examples\":[]}";
        long res=service.createNew(CONCEPT,r.institution,f.teacher.getId(),r.authorMember,p,op());long id=draft(res);
        assertThatThrownBy(()->service.publish(id,1,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("empty");
        String bad=p.replace("\"materials\":[]","\"materials\":[{\"type\":\"FILE\",\"file_data\":\"\\\\x010203\",\"file_size\":3,\"content_type\":\"application/pdf\",\"original_file_name\":\"bad.pdf\"}]");
        assertThatThrownBy(()->service.edit(id,1,bad,op())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void repeatAndDivergenceCertifySuccessorWithoutRewritingPredecessors() {
        fresh(3);var before=state();var old=previous.previous.signatures("t11b1_schema_version","t11b2_schema_version","t11b3_schema_version","t11b7_schema_version");
        sql().execute(expansion);sql().execute(previous.expansion);assertThat(state()).isEqualTo(before);
        previous.previous.previous.base.tx().executeWithoutResult(s->{sql().execute("alter table t11b8_revisions disable trigger t11b8_guard");
            assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        previous.previous.previous.base.tx().executeWithoutResult(s->{sql().execute("alter table t11b7_identifiers disable trigger t11b7_guard");
            assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        assertThat(previous.previous.signatures(old.keySet().toArray(String[]::new))).isEqualTo(old);sql().execute(expansion);
    }
    List<String> race(String mode1,long id1,String mode2,long id2) throws Exception {
        Path dir=Files.createDirectories(Path.of("target","t11b8-processes",f.schema,mode1+"-"+mode2));Path go=dir.resolve("go");var children=new ArrayList<Process>();
        try(Connection barrier=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            barrier.setAutoCommit(false);
            try {
                barrier.createStatement().execute("select t11b8_lock()");
                for(int i=1;i<=2;i++) {
                    var cmd=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AcademicRevisionProcess.class.getName(),
                        f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,i==1?mode1:mode2,""+(i==1?id1:id2),
                        dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString());
                    children.add(new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready")))&&System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(Files.exists(dir.resolve("1.ready"))).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();Files.writeString(go,"go");
                List<Integer> pids=List.of();deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline){pids=sql().queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query='select t11b8_lock()'",Integer.class);if(pids.size()==2)break;Thread.sleep(20);}
                assertThat(pids).hasSize(2);barrier.commit();
                for(Process p:children){assertThat(p.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
                var results=List.of(Files.readString(dir.resolve("1.result")),Files.readString(dir.resolve("2.result")));
                Files.writeString(dir.resolve("evidence.json"),f.json.writeValueAsString(Map.of("osPids",children.stream().map(Process::pid).toList(),"blockedPostgresPids",pids,"results",results,"scope","SQL integrity; mandatory audit verified separately by service tests")));
                return results;
            } finally {barrier.rollback();for(Process p:children)if(p.isAlive())p.destroyForcibly();}
        }
    }
    @Test void twoProcessesEditAndPublishSameVersionHaveOneCoherentWinner() throws Exception {
        long res=fresh(3),id=draft(res);var result=race("EDIT",id,"PUBLISH",id);
        assertThat(result).containsExactlyInAnyOrder("COMMITTED","REJECTED:40001");
        var rev=repository.revision(id);assertThat(rev.version()).isEqualTo(2);
        if(rev.status().equals("PUBLISHED"))assertThat(rev.payload()).contains("New evaluation");
        else assertThat(rev.payload()).contains("Race edit");
        assertThat(count("select count(*) from t11b8_events where entity_table='t11b8_revisions' and entity_id=?",id)).isEqualTo(2);
    }
    @Test void suspendedEnrollmentOrRevokedStudentRoleCannotConsumePreparedRecipient() {
        long res=fresh(3);var rev=published(res);long rec=admitted(delivered(rev,r.classId),r.episode);
        r.academic.transitionEnrollment(r.episode,Enrollment.Status.SUSPENDED,Instant.now().minusSeconds(1));
        var before=state();assertThatThrownBy(()->service.reserveFixtureAttempt(rec,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("stale enrollment");assertThat(state()).isEqualTo(before);
    }
    @Test void expiredWindowCannotBeExtendedOrCreateAnotherDeliveryToReopenRoot() throws Exception {
        long res=fresh(3);var rev=published(res);
        long d=service.assign(rev.id(),r.institution,r.classId,Instant.now().minusSeconds(1),Instant.now().plusMillis(900),op());
        long rec=admitted(d,r.episode);long a=service.reserveFixtureAttempt(rec,op());Thread.sleep(1100);
        assertThatThrownBy(()->service.finishFixtureAttempt(a,1,"{\"17\":\"late\"}",20,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("expired");
        service.finishFixtureAttempt(a,1,"{}",0,op());
        assertThatThrownBy(()->service.assign(rev.id(),r.institution,r.otherClass,Instant.now(),null,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("closed root");
        assertThatThrownBy(()->sql().update("update t11b8_deliveries set due_at=statement_timestamp()+interval '1 day',version=2 where id=?",d)).isInstanceOf(DataAccessException.class);
        service.resourceState(res,1,"AVAILABLE",true,true,op());
    }
    @Test void payloadCannotRewriteHistoricalAuthorAndExistingAnchorCannotBecomeNew() {
        long res=legacy();var d=legacyDraft(res);String rewritten=d.payload().replace("\"user_id\": "+f.teacher.getId(),"\"user_id\": "+f.otherTeacher.getId());
        assertThat(rewritten).isNotEqualTo(d.payload());var before=state();
        assertThatThrownBy(()->service.edit(d.id(),1,rewritten,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("author immutable");assertThat(state()).isEqualTo(before);
        var teacher=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        long source=f.app.getBean(EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Existing draft").createdByTeacher(teacher).maxAttempts(3).build()).getId();
        previous.previous.previous.base.tx().executeWithoutResult(s->{
            sql().update("update evaluations set title='Touched existing draft' where id=?",source);
            assertThatThrownBy(()->sql().update("insert into t11b8_resources(evaluation_id,institution_id,author_account_id,author_membership_id,creation,max_attempts,operation_id) values(?,?,?,?,'EXPLICIT_NEW',3,?)",source,r.institution,f.teacher.getId(),r.authorMember,op())).isInstanceOf(DataAccessException.class).hasMessageContaining("explicit creation command");s.setRollbackOnly();
        });
    }
    @Test void auditFailureDuringAdmissionAndConsumptionRollsBackEveryAssociatedRow() {
        long res=fresh(3);var rev=published(res);long d=delivered(rev,r.classId);var before=state();
        sql().execute("alter table system_logs rename to system_logs_unavailable");
        try {assertThatThrownBy(()->admitted(d,r.episode)).isInstanceOf(AuditUnavailableException.class);}
        finally {sql().execute("alter table system_logs_unavailable rename to system_logs");}
        assertThat(state()).isEqualTo(before);long rec=admitted(d,r.episode);before=state();
        sql().execute("alter table system_logs rename to system_logs_unavailable");
        try {assertThatThrownBy(()->service.reserveFixtureAttempt(rec,op())).isInstanceOf(AuditUnavailableException.class);}
        finally {sql().execute("alter table system_logs_unavailable rename to system_logs");}
        assertThat(state()).isEqualTo(before);service.reserveFixtureAttempt(rec,op());
    }
    @Test void twoProcessesAllocateDistinctSequentialAcademicRevisions() throws Exception {
        long res=fresh(3);var rev=published(res);assertThat(race("NEXT",rev.id(),"NEXT",rev.id())).containsExactly("COMMITTED","COMMITTED");
        assertThat(sql().queryForList("select revision_number from t11b8_revisions where resource_id=? order by revision_number",Integer.class,res)).containsExactly(1,2,3);
    }
    @Test void twoClassesCompeteForLastF09OpportunityAcrossProcesses() throws Exception {
        long res=fresh(1);var rev=published(res);long a=admitted(delivered(rev,r.classId),r.episode),b=admitted(delivered(rev,r.otherClass),r.otherEpisode);
        assertThat(race("START",a,"START",b)).containsExactlyInAnyOrder("COMMITTED","REJECTED:P0001");
        assertThat(count("select count(*) from t11b8_fixture_attempts where resource_id=?",res)).isEqualTo(1);
        assertThat(count("select attempt_number from t11b8_fixture_attempts where resource_id=?",res)).isEqualTo(1);
    }
    @Test void nativeDumpRestorePreservesAllTablesBytesBudgetsAndCertifiesSuccessor() throws Exception {
        long res=fresh(3);var rev=published(res);long d=delivered(rev,r.classId),rec=admitted(d,r.episode);long a=service.reserveFixtureAttempt(rec,op());
        service.finishFixtureAttempt(a,1,"{\"17\":\"Restore answer\"}",19,op());successor(rev,"Restored successor");
        var tables=sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' order by relname",String.class);var before=previous.previous.signatures(tables.toArray(String[]::new));
        Path dir=Files.createDirectories(Path.of("target","t11b8-native",f.schema));String db="chemicallab_t11b8_restore_"+UUID.randomUUID().toString().replace("-","");
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));boolean created=false;
        try {
            r.nativeTool("pg_dump",args,List.of("-Fc","-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            r.nativeTool("createdb",args,List.of(db),dir);created=true;r.nativeTool("pg_restore",args,List.of("--exit-on-error","--single-transaction","-d",db,dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            try(Connection connection=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement q=connection.createStatement()) {
                q.execute("set search_path to "+f.schema+",public");q.execute(expansion);Map<String,String> recovered=new TreeMap<>();
                for(String table:tables)try(var rows=q.executeQuery(r.signature(table))){rows.next();recovered.put(table,rows.getString(1));}assertThat(recovered).isEqualTo(before);
                for(String mutation:List.of("update system_logs set target_type='REST'","delete from system_logs","truncate system_logs cascade","truncate t11b8_events cascade"))assertThatThrownBy(()->q.execute(mutation)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
                assertThatThrownBy(()->q.execute("select t11b8_lock()")).isInstanceOf(SQLException.class).hasMessageContaining("fixtures only");
                q.execute("alter table t11b8_revisions disable trigger t11b8_guard");assertThatThrownBy(()->q.execute(expansion)).isInstanceOf(SQLException.class).hasMessageContaining("divergence");q.execute("alter table t11b8_revisions enable trigger t11b8_guard");q.execute(expansion);
                Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source",before,"restored",recovered,"tables",tables.size(),"certifier","B8 v1 frozen B7 chain; predecessors unchanged")));
            }
        } finally {if(created)r.nativeTool("dropdb",args,List.of(db),dir);}
    }
}

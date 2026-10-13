package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.AuditUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.service.BoardActivityReconciliationService.SourceKind.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoardActivityReconciliationDbTest {
    final ResourceReconciliationDbTest base=new ResourceReconciliationDbTest();
    final WhiteboardSecurityTransportDbTest f=base.f;
    BoardActivityReconciliationService service;
    String expansion, predecessor;
    long participant;
    @BeforeAll void start() throws Exception {
        base.start(); service=f.app.getBean(BoardActivityReconciliationService.class); predecessor=base.script("t11b4-v1"); expansion=base.script("t11b4-v2");
        assertThatThrownBy(()->f.sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("requires certified B4 v1");
        f.sql().execute(predecessor);
        Map<String,String> before=versions();
        tx().executeWithoutResult(s->{f.sql().execute(expansion);s.setRollbackOnly();});
        assertThat(f.sql().queryForObject("select to_regclass(current_schema()||'.t11b4_apply_version') is null",Boolean.class)).isTrue();
        f.sql().execute(expansion);f.sql().execute(expansion);
        assertThat(versions()).isEqualTo(before);
    }
    Map<String,String> versions() {
        Map<String,String> result=new TreeMap<>();
        for(String t:List.of("t11b1_schema_version","t11b2_schema_version","t11b2_audit_version","t11b3_schema_version","t11b3_apply_version","t11b4_schema_version","audit_schema_version","storage_schema_version")) result.put(t,f.sql().queryForObject(base.signature(t),String.class));
        return result;
    }
    @BeforeEach void fixture() {
        base.fixture();
        f.sql().update("update whiteboard_participants set joined_at='2026-02-01 10:00:00' where session_id=?",f.board);
        participant=f.sql().queryForObject("select id from whiteboard_participants where session_id=? and student_id=?",Long.class,f.board,f.studentProfile(f.student));
        base.membership(base.foreign,f.student.getId(),MembershipRoleGrant.InstitutionalRole.ESTUDIANTE);
    }
    @AfterEach void close(){base.close();}
    @AfterAll void stop() throws Exception {base.stop();}
    TransactionTemplate tx(){return new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));}
    Map<String,Object> pupil(boolean withEnrollment) {
        var m=new HashMap<String,Object>(Map.of("participantId",participant,"accountId",f.student.getId(),"evidence","FIXTURE-JOIN"));
        if(withEnrollment){m.put("enrollmentId",base.episode);m.put("sourceTimezone","America/Lima");}return m;
    }
    Map<String,Object> mapping() {return new HashMap<>(Map.of("institutionId",base.institution,"classId",base.classId,"authorAccountId",f.teacher.getId(),"authorMembershipId",base.authorMember,"participants",List.of(pupil(true))));}
    String json(Object m) throws Exception {return f.json.writeValueAsString(m);}
    long proposed(BoardActivityReconciliationService.SourceKind k,long pk,Object map) throws Exception {return service.propose(k,pk,json(map));}
    void approve(long id){service.approve(id,f.sql().queryForObject("select md5(source_checksum||proposal_checksum) from t11b4_reconciliations where id=?",String.class,id),"FIXTURE-REVIEW");}
    long approved(BoardActivityReconciliationService.SourceKind k,long pk,Object map) throws Exception {long id=proposed(k,pk,map);approve(id);return id;}
    long appliedBoard() throws Exception {long id=approved(BOARD,f.board,mapping());assertThat(service.apply(id)).isEqualTo("APPLIED");return id;}
    long count(String query,Object... args){return base.count(query,args);}
    String status(long id){return f.sql().queryForObject("select status from t11b4_reconciliations where id=?",String.class,id);}
    String source(long board) {return f.sql().queryForObject("select t11b4_snapshot('BOARD',?,'{}')::text",String.class,board);}
    Map<String,String> rehearsal() {
        Map<String,String> result=new TreeMap<>();
        for(String t:List.of("t11b4_reconciliations","t11b4_reconciliation_events","t11b4_board_contexts","t11b4_participant_contexts","t11b4_usage_contexts","t11b4_audit_contexts","storage_usage","system_logs")) result.put(t,f.sql().queryForObject(base.signature(t),String.class));return result;
    }
    @Test void originalAuthorsTombstonesCaptureRevisionAndParticipantsArePreserved() throws Exception {
        f.sql().update("insert into whiteboard_object_ownership(board_id,object_kind,object_id,owner_user_id,deleted) values(?,'TEXT','deleted',?,true),(?,'SHAPE','legacy',null,false)",f.board,f.student.getId(),f.board);
        f.sql().update("update whiteboard_sessions set current_state_json='{}',state_revision=19,final_snapshot_data=decode('01020304','hex'),status='CLOSED' where id=?",f.board);
        String before=source(f.board); appliedBoard();
        assertThat(source(f.board)).isEqualTo(before);
        assertThat(count("select count(*) from t11b4_ownership_contexts where board_id=?",f.board)).isEqualTo(2);
        assertThat(f.sql().queryForMap("select * from t11b4_capture_contexts where board_id=?",f.board)).containsEntry("state_revision",19L).containsEntry("actual_bytes",4).containsEntry("source_current",true);
        assertThat(count("select count(*) from t11b4_participant_contexts where board_id=?",f.board)).isEqualTo(1);
        assertThat(count("select count(*) from whiteboard_participants where session_id=?",f.board)).isEqualTo(2);
        assertThat(count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='REVISION_BYTES'",f.teacher.getId())).isGreaterThan(before.length());
    }
    @Test void coincidentGroupsInTwoInstitutionsAndSharedAccountRemainDistinct() throws Exception {
        appliedBoard();long other=f.board(f.teacher,"3","A");
        var map=mapping();map.put("institutionId",base.foreign);map.put("classId",base.foreignClass);map.remove("authorMembershipId");map.put("participants",List.of());
        assertThat(service.apply(approved(BOARD,other,map))).isEqualTo("APPLIED");
        assertThat(count("select count(distinct institution_id) from t11b4_board_contexts where board_id in (?,?)",f.board,other)).isEqualTo(2);
        assertThat(count("select count(*) from institution_memberships where account_id=?",f.student.getId())).isEqualTo(2);
    }
    @Test void postgresRejectsCrossInstitutionClassEvenWithoutService() throws Exception {
        var map=mapping();map.put("classId",base.foreignClass);long entry=approved(BOARD,f.board,map);
        assertThatThrownBy(()->f.sql().update("insert into t11b4_board_contexts values(?,?,?,?,?,?)",f.board,base.institution,base.foreignClass,f.teacher.getId(),base.authorMember,entry)).isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        assertThat(service.apply(entry)).isEqualTo("CONFLICT");assertThat(count("select count(*) from t11b4_board_contexts where board_id=?",f.board)).isZero();
    }
    @Test void wrongEnrollmentClassOrAccountRollsBackAllContextRows() throws Exception {
        for(long wrong:List.of(base.otherEpisode,base.episode)) {
            var p=pupil(true);if(wrong==base.otherEpisode)p.put("enrollmentId",wrong);else p.put("accountId",f.peerStudent.getId());
            var map=mapping();map.put("participants",List.of(p));long id=approved(BOARD,f.board,map);
            assertThat(service.apply(id)).isEqualTo("CONFLICT");assertThat(count("select count(*) from t11b4_board_contexts where board_id=?",f.board)).isZero();
        }
    }
    @Test void joinOutsideOriginalEpisodeDoesNotAdoptEnrollment() throws Exception {
        f.sql().update("update whiteboard_participants set joined_at='2025-01-01' where id=?",participant);
        assertThat(service.apply(approved(BOARD,f.board,mapping()))).isEqualTo("CONFLICT");
    }
    @Test void demonstratedParticipantWithoutEnrollmentDoesNotInventAttendance() throws Exception {
        var map=mapping();map.put("participants",List.of(pupil(false)));assertThat(service.apply(approved(BOARD,f.board,map))).isEqualTo("APPLIED");
        assertThat(f.sql().queryForMap("select account_id,enrollment_id from t11b4_participant_contexts where participant_id=?",participant)).containsEntry("account_id",f.student.getId()).containsEntry("enrollment_id",null);
    }
    @Test void readmissionDoesNotRewriteOriginalParticipantEpisode() throws Exception {
        appliedBoard();base.academic.transitionEnrollment(base.episode,Enrollment.Status.WITHDRAWN,base.from.plusSeconds(10000000));
        var next=base.academic.createEnrollment(base.institution,base.classId,base.member,base.from.plusSeconds(10000000),base.until,base.from.plusSeconds(10000000));
        base.academic.transitionEnrollment(next.id(),Enrollment.Status.ACTIVE,base.from.plusSeconds(10000000));
        assertThat(count("select enrollment_id from t11b4_participant_contexts where participant_id=?",participant)).isEqualTo(base.episode);
    }
    @Test void unresolvedMapPreservesSourceWithoutInventedInstitution() throws Exception {
        String before=source(f.board);long id=approved(BOARD,f.board,Map.of());assertThat(service.apply(id)).isEqualTo("UNRESOLVED");
        assertThat(service.apply(id)).isEqualTo("UNRESOLVED");assertThat(source(f.board)).isEqualTo(before);assertThat(count("select count(*) from t11b4_board_contexts where board_id=?",f.board)).isZero();
    }
    @Test void approvalBindsMapSourceAndFixtureEvidence() throws Exception {
        long id=proposed(BOARD,f.board,mapping());var before=rehearsal();
        assertThatThrownBy(()->service.approve(id,"incorrect","FIXTURE-REVIEW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.approve(id,f.sql().queryForObject("select md5(source_checksum||proposal_checksum) from t11b4_reconciliations where id=?",String.class,id),"REAL-APPROVAL")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->f.sql().update("update t11b4_reconciliations set proposed='{}' where id=?",id)).isInstanceOf(DataAccessException.class);
        assertThat(rehearsal()).isEqualTo(before);
    }
    @Test void proposedIntactAndChangedRejectBeforeStaleWithNoMutations() throws Exception {
        long id=proposed(BOARD,f.board,mapping());
        for(boolean changed:List.of(false,true)) {
            if(changed)f.sql().update("update whiteboard_sessions set name='Changed fictitious source' where id=?",f.board);
            var before=rehearsal();
            assertThatThrownBy(()->service.apply(id)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(()->f.sql().queryForObject("select t11b4_apply(?)",String.class,id)).isInstanceOf(DataAccessException.class);
            assertThat(rehearsal()).isEqualTo(before);assertThat(status(id)).isEqualTo("PROPOSED");
        }
    }
    @Test void sourceAndReferenceDriftBecomeTerminalStale() throws Exception {
        long id=approved(BOARD,f.board,mapping());f.sql().update("update whiteboard_participants set last_seen_at=last_seen_at+interval '1 second' where id=?",participant);
        assertThat(service.apply(id)).isEqualTo("STALE");var before=rehearsal();assertThat(service.apply(id)).isEqualTo("STALE");assertThat(rehearsal()).isEqualTo(before);
        long second=approved(BOARD,f.otherBoard,mapping());base.institutions.changeInstitutionStatus(base.institution,Institution.Status.SUSPENDED);
        assertThat(service.apply(second)).isEqualTo("STALE");
    }
    @Test void quotaAuditFailureAndInterruptionRestoreBusinessHistoryAndCounters() throws Exception {
        long id=approved(BOARD,f.board,mapping());var before=rehearsal();
        f.sql().update("update storage_limits set maximum=1 where resource='REVISION_BYTES'");
        try {assertThatThrownBy(()->service.apply(id)).isInstanceOf(DataAccessException.class);}finally{f.sql().update("update storage_limits set maximum=268435456 where resource='REVISION_BYTES'");}
        assertThat(rehearsal()).isEqualTo(before);
        failAudit();try {assertThatThrownBy(()->service.apply(id)).isInstanceOf(AuditUnavailableException.class);}finally{recoverAudit();}
        assertThat(rehearsal()).isEqualTo(before);assertThat(status(id)).isEqualTo("APPROVED");
        tx().executeWithoutResult(s->{assertThat(service.apply(id)).isEqualTo("APPLIED");s.setRollbackOnly();});assertThat(rehearsal()).isEqualTo(before);
        assertThat(service.apply(id)).isEqualTo("APPLIED");var after=rehearsal();assertThat(service.apply(id)).isEqualTo("APPLIED");assertThat(rehearsal()).isEqualTo(after);
    }
    void failAudit(){f.sql().execute("create function b4_fail_audit() returns trigger language plpgsql as $$ begin if NEW.target_type='BoardActivityReconciliation' then raise exception 'Fixture audit unavailable'; end if; return NEW; end $$");f.sql().execute("create trigger b4_fail_audit before insert on system_logs for each row execute function b4_fail_audit()");}
    void recoverAudit(){f.sql().execute("drop trigger b4_fail_audit on system_logs");f.sql().execute("drop function b4_fail_audit()");}
    @Test void staleTransitionAuditFailureRollsBackThenRetriesOnce() throws Exception {
        long id=approved(BOARD,f.board,mapping());f.sql().update("update whiteboard_sessions set state_revision=state_revision+1 where id=?",f.board);var before=rehearsal();
        failAudit();try {assertThatThrownBy(()->service.apply(id)).isInstanceOf(AuditUnavailableException.class);}finally{recoverAudit();}
        assertThat(rehearsal()).isEqualTo(before);assertThat(status(id)).isEqualTo("APPROVED");assertThat(service.apply(id)).isEqualTo("STALE");
        assertThat(count("select count(*) from system_logs where target_type='BoardActivityReconciliation' and target_id=? and event_type='RESOURCE_RECONCILIATION_STALE'",id)).isEqualTo(1);
    }
    @Test void inconsistentSqlReturnCannotEmitFictitiousSuccess() throws Exception {
        long id=approved(BOARD,f.board,mapping());var before=rehearsal();tx().executeWithoutResult(s->{
            f.sql().execute("create or replace function t11b4_apply(entry bigint,actor bigint default null) returns text language sql as $$ select 'STALE'::text $$");
            assertThatThrownBy(()->service.apply(id)).isInstanceOf(IllegalStateException.class);s.setRollbackOnly();});assertThat(rehearsal()).isEqualTo(before);f.sql().execute(expansion);
    }
    Map<String,Object> factMap(){return new HashMap<>(Map.of("scope","INSTITUTION","institutionId",base.institution,"classId",base.classId,"boardId",f.board,"evidence","FIXTURE-SERVER-RELATION"));}
    long usage(){return f.sql().queryForObject("select min(id) from usage_events where resource_type='WhiteboardSession' and resource_id=?",Long.class,""+f.board);}
    long audit(LogEventType event,String target,Long targetId){tx().executeWithoutResult(s->f.app.getBean(AuditLogService.class).recordInfo(event,target,targetId,null,null,null,null));return count("select max(id) from system_logs");}
    Map<String,Object> typedBoardMap(){var m=factMap();m.remove("boardId");return m;}
    String applyAudit(boolean direct,long id){return direct?f.sql().queryForObject("select t11b4_apply(?)",String.class,id):service.apply(id);}
    void directAuditContext(long entry){f.sql().update("""
        insert into t11b4_audit_contexts(event_id,scope,institution_id,class_id,board_id,account_id,evidence,reconciliation_id)
        select source_pk,proposed->>'scope',(proposed->>'institutionId')::bigint,(proposed->>'classId')::bigint,
        (proposed->>'boardId')::bigint,(source_snapshot->'author'->>'user_id')::bigint,proposed->>'evidence',id
        from t11b4_reconciliations where id=?
        """,entry);}
    @Test void adminBoardTargetRejectsForeignInstitutionAndClassWithOrWithoutBoardId() throws Exception {
        appliedBoard();
        for(boolean direct:List.of(false,true)) for(boolean explicit:List.of(false,true)) for(boolean foreign:List.of(false,true)) {
            long event=audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board);
            var map=explicit?factMap():typedBoardMap();map.put("classId",foreign?base.foreignClass:base.otherClass);
            if(foreign)map.put("institutionId",base.foreign);
            long id=approved(AUDIT,event,map);var before=rehearsal();
            assertThatThrownBy(()->directAuditContext(id)).isInstanceOf(DataAccessException.class);
            assertThat(rehearsal()).isEqualTo(before);
            assertThat(applyAudit(direct,id)).isEqualTo("CONFLICT");
            assertThat(count("select count(*) from t11b4_audit_contexts where event_id=?",event)).isZero();
            var after=rehearsal();assertThat(applyAudit(direct,id)).isEqualTo("CONFLICT");assertThat(rehearsal()).isEqualTo(after);
        }
    }
    @Test void adminBoardTargetValidWithoutBoardIdKeepsOriginalLogAndDetectsParentDrift() throws Exception {
        appliedBoard();List<Long> entries=new ArrayList<>();
        for(boolean direct:List.of(false,true)) for(boolean explicit:List.of(false,true)) {
            long event=audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board);
            String original=f.sql().queryForObject("select to_jsonb(s)::text from system_logs s where id=?",String.class,event);
            long id=approved(AUDIT,event,explicit?factMap():typedBoardMap());entries.add(id);
            assertThat(applyAudit(direct,id)).isEqualTo("APPLIED");var after=rehearsal();
            assertThat(applyAudit(direct,id)).isEqualTo("APPLIED");assertThat(rehearsal()).isEqualTo(after);
            assertThat(f.sql().queryForObject("select board_id from t11b4_audit_contexts where event_id=?",Long.class,event)).isEqualTo(explicit?f.board:null);
            assertThat(f.sql().queryForObject("select to_jsonb(s)::text from system_logs s where id=?",String.class,event)).isEqualTo(original);
        }
        f.sql().update("update whiteboard_sessions set state_revision=state_revision+1 where id=?",f.board);
        for(long id:entries) {
            assertThat(f.sql().queryForObject("select source_current from t11b4_fact_observations where reconciliation_id=?",Boolean.class,id)).isFalse();
            assertThat(service.apply(id)).isEqualTo("STALE");
            var after=rehearsal();assertThat(f.sql().queryForObject("select t11b4_apply(?)",String.class,id)).isEqualTo("STALE");assertThat(rehearsal()).isEqualTo(after);
        }
    }
    @Test void adminBoardTargetMissingOrUnreconciledParentCannotReceiveInstitution() throws Exception {
        for(boolean direct:List.of(false,true)) for(Long board:Arrays.asList(f.otherBoard,999999999L,null)) {
            long event=audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",board);
            long id=approved(AUDIT,event,typedBoardMap());var before=rehearsal();
            assertThatThrownBy(()->directAuditContext(id)).isInstanceOf(DataAccessException.class);assertThat(rehearsal()).isEqualTo(before);
            assertThat(applyAudit(direct,id)).isEqualTo("CONFLICT");
            assertThat(count("select count(*) from t11b4_audit_contexts where event_id=?",event)).isZero();
        }
    }
    @Test void adminBoardTargetStaleParentAtProposalConflictsAndDriftAfterApprovalIsStale() throws Exception {
        appliedBoard();List<Long> entries=new ArrayList<>();
        for(boolean direct:List.of(false,true)) entries.add(approved(AUDIT,audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board),typedBoardMap()));
        f.sql().update("update whiteboard_sessions set state_revision=state_revision+1 where id=?",f.board);
        for(int i=0;i<2;i++) {
            long id=entries.get(i);assertThatThrownBy(()->directAuditContext(id)).isInstanceOf(DataAccessException.class);
            assertThat(applyAudit(i==1,id)).isEqualTo("STALE");
            long stale=approved(AUDIT,audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board),typedBoardMap());
            assertThatThrownBy(()->directAuditContext(stale)).isInstanceOf(DataAccessException.class);
            assertThat(applyAudit(i==1,stale)).isEqualTo("CONFLICT");
        }
        assertThat(count("select count(*) from t11b4_audit_contexts c join system_logs s on s.id=c.event_id where s.target_type='WhiteboardSession' and s.target_id=?",f.board)).isZero();
    }
    @Test void adminBoardTargetWithoutResolutionRemainsUnresolved() throws Exception {
        for(boolean direct:List.of(false,true)) {
            long event=audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board);long id=approved(AUDIT,event,Map.of());
            assertThat(applyAudit(direct,id)).isEqualTo("UNRESOLVED");var after=rehearsal();
            assertThat(applyAudit(direct,id)).isEqualTo("UNRESOLVED");assertThat(rehearsal()).isEqualTo(after);
        }
        assertThat(count("select count(*) from t11b4_audit_contexts c join system_logs s on s.id=c.event_id where s.target_type='WhiteboardSession' and s.target_id=?",f.board)).isZero();
    }
    @Test void adminBoardTargetRollbackRestoresContextHistoryAuditAndQuotas() throws Exception {
        appliedBoard();long id=approved(AUDIT,audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board),typedBoardMap());var before=rehearsal();
        f.sql().update("update storage_limits set maximum=1 where resource='REVISION_BYTES'");
        try {for(boolean direct:List.of(false,true))assertThatThrownBy(()->applyAudit(direct,id)).isInstanceOf(DataAccessException.class);}
        finally{f.sql().update("update storage_limits set maximum=268435456 where resource='REVISION_BYTES'");}
        assertThat(rehearsal()).isEqualTo(before);
        failAudit();try{assertThatThrownBy(()->service.apply(id)).isInstanceOf(AuditUnavailableException.class);}finally{recoverAudit();}
        assertThat(rehearsal()).isEqualTo(before);assertThat(status(id)).isEqualTo("APPROVED");
        for(boolean direct:List.of(false,true)) {tx().executeWithoutResult(s->{assertThat(applyAudit(direct,id)).isEqualTo("APPLIED");s.setRollbackOnly();});assertThat(rehearsal()).isEqualTo(before);}
        assertThat(service.apply(id)).isEqualTo("APPLIED");var after=rehearsal();assertThat(service.apply(id)).isEqualTo("APPLIED");assertThat(rehearsal()).isEqualTo(after);
        assertThat(count("select count(*) from system_logs where target_type='BoardActivityReconciliation' and target_id=? and event_type='RESOURCE_RECONCILIATION_APPLIED'",id)).isEqualTo(1);
        var wrong=typedBoardMap();wrong.put("classId",base.otherClass);
        long conflict=approved(AUDIT,audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board),wrong);var pending=rehearsal();
        failAudit();try{assertThatThrownBy(()->service.apply(conflict)).isInstanceOf(AuditUnavailableException.class);}finally{recoverAudit();}
        assertThat(rehearsal()).isEqualTo(pending);assertThat(status(conflict)).isEqualTo("APPROVED");
        tx().executeWithoutResult(s->{assertThat(applyAudit(true,conflict)).isEqualTo("CONFLICT");s.setRollbackOnly();});assertThat(rehearsal()).isEqualTo(pending);
        assertThat(service.apply(conflict)).isEqualTo("CONFLICT");
        assertThat(count("select count(*) from system_logs where target_type='BoardActivityReconciliation' and target_id=? and event_type='RESOURCE_RECONCILIATION_APPLIED'",conflict)).isZero();
    }
    @Test void typedBoardAuditChargesEveryAdditionalSnapshotCopyIncludingCapture() throws Exception {
        f.sql().update("update whiteboard_sessions set final_snapshot_data=decode('010203040506','hex') where id=?",f.board);appliedBoard();
        long event=audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",f.board);
        long before=count("select coalesce(sum(used),0) from storage_usage where scope='SYSTEM' and owner_id=0 and resource='REVISION_BYTES'");
        long id=approved(AUDIT,event,typedBoardMap());assertThat(service.apply(id)).isEqualTo("APPLIED");
        long bytes=0;
        for(String table:List.of("t11b4_reconciliations","t11b4_reconciliation_events","t11b4_audit_contexts"))
            bytes+=count("select coalesce(sum(octet_length(to_jsonb(t)::text)),0) from "+table+" t where "+(table.equals("t11b4_reconciliations")?"id":"reconciliation_id")+"=?",id);
        assertThat(count("select used from storage_usage where scope='SYSTEM' and owner_id=0 and resource='REVISION_BYTES'")-before).isEqualTo(bytes);
        assertThat(f.sql().queryForObject("select source_snapshot->'event_parent'->'reconciliation'->'source'->'source'->>'final_snapshot_data' from t11b4_reconciliations where id=?",String.class,id)).isEqualTo("\\x010203040506");
    }
    @Test void demonstratedGlobalAuditWithBoardTargetRetainsGlobalProvenance() throws Exception {
        appliedBoard();long event=audit(LogEventType.SYSTEM_HEALTH_CHECK,"WhiteboardSession",f.board);
        long id=approved(AUDIT,event,Map.of("scope","GLOBAL","evidence","FIXTURE-GLOBAL-TYPED"));
        assertThat(service.apply(id)).isEqualTo("APPLIED");f.sql().update("update whiteboard_sessions set state_revision=state_revision+1 where id=?",f.board);
        assertThat(f.sql().queryForObject("select source_current from t11b4_audit_observations where event_id=?",Boolean.class,event)).isTrue();
        assertThat(service.apply(id)).isEqualTo("APPLIED");
        assertThat(service.apply(approved(AUDIT,event,typedBoardMap()))).isEqualTo("CONFLICT");
    }
    @Test void usageAndAuditAreLinkedAdditivelyWithOriginalActorAndExactBoard() throws Exception {
        appliedBoard();long u=usage(),a=audit(LogEventType.WHITEBOARD_SESSION_CREATED,"WhiteboardSession",f.board);
        String original=f.sql().queryForObject("select to_jsonb(s)::text from system_logs s where id=?",String.class,a);
        assertThat(service.apply(approved(USAGE,u,factMap()))).isEqualTo("APPLIED");assertThat(service.apply(approved(AUDIT,a,factMap()))).isEqualTo("APPLIED");
        assertThat(f.sql().queryForObject("select to_jsonb(s)::text from system_logs s where id=?",String.class,a)).isEqualTo(original);
        assertThat(count("select count(*) from t11b4_fact_observations where source_pk in (?,?) and source_current",u,a)).isGreaterThanOrEqualTo(2);
    }
    @Test void globalAuditEventsCannotReceiveAnArbitrarySchool() throws Exception {
        long a=audit(LogEventType.SYSTEM_HEALTH_CHECK,"UserAccount",f.student.getId());
        assertThat(service.apply(approved(AUDIT,a,Map.of("scope","INSTITUTION","institutionId",base.institution,"evidence","FIXTURE-GLOBAL")))).isEqualTo("CONFLICT");
        assertThat(service.apply(approved(AUDIT,a,Map.of("scope","GLOBAL","evidence","FIXTURE-GLOBAL")))).isEqualTo("APPLIED");
        assertThat(f.sql().queryForMap("select * from t11b4_audit_contexts where event_id=?",a)).containsEntry("institution_id",null).containsEntry("account_id",null);
        assertThat(count("select used from storage_usage where scope='SYSTEM' and owner_id=0 and resource='REVISION_BYTES'")).isGreaterThan(0);
    }
    @Test void legacyGlobalAccountManagementCannotBeInstitutionalized() throws Exception {
        for(LogEventType event:List.of(LogEventType.USER_CREATED,LogEventType.USER_UPDATED,LogEventType.USER_DEACTIVATED,LogEventType.USER_REACTIVATED,LogEventType.PERMISSIONS_CHANGED)) {
            audit(event,"UserAccount",f.student.getId());long original=count("select max(id) from system_logs where event_type=? and target_id=?",event.name(),f.student.getId());
            assertThat(service.apply(approved(AUDIT,original,Map.of("scope","INSTITUTION","institutionId",base.institution,"evidence","FIXTURE-GLOBAL-ACCOUNT")))).isEqualTo("CONFLICT");
            assertThat(service.apply(approved(AUDIT,original,Map.of("scope","GLOBAL","evidence","FIXTURE-GLOBAL-ACCOUNT")))).isEqualTo("APPLIED");
        }
    }
    @Test void disappearingSourceMarksApprovedSnapshotStaleWithoutInventingANewOrigin() throws Exception {
        long empty=f.board(f.teacher,"3","A");var map=mapping();map.put("participants",List.of());long id=approved(BOARD,empty,map);
        f.sql().update("delete from whiteboard_sessions where id=?",empty);assertThat(service.apply(id)).isEqualTo("STALE");
        var before=rehearsal();assertThat(service.apply(id)).isEqualTo("STALE");assertThat(rehearsal()).isEqualTo(before);
        assertThatThrownBy(()->proposed(BOARD,empty,map)).isInstanceOf(DataAccessException.class);assertThat(rehearsal()).isEqualTo(before);
    }
    @Test void auditCannotContradictTheInstitutionAndClassOfItsTypedParent() throws Exception {
        long event=audit(LogEventType.ACADEMIC_CLASS_CREATED,"AcademicClass",base.classId);
        assertThat(service.apply(approved(AUDIT,event,Map.of("scope","INSTITUTION","institutionId",base.foreign,"classId",base.foreignClass,"evidence","FIXTURE-CROSS-PARENT")))).isEqualTo("CONFLICT");
        assertThat(service.apply(approved(AUDIT,event,Map.of("scope","INSTITUTION","institutionId",base.institution,"classId",base.classId,"evidence","FIXTURE-CLASS-PARENT")))).isEqualTo("APPLIED");
    }
    @Test void resourceActivityChecksTheB3ParentAndDetectsReferenceDrift() throws Exception {
        long resource=base.approved(ResourceReconciliationService.SourceKind.EVALUATION,base.evaluation,base.standardMap());assertThat(base.service.apply(resource)).isEqualTo("APPLIED");
        long event=f.sql().queryForObject("insert into usage_events(user_id,username,user_role,module,event_type,resource_type,resource_id,occurred_at) values(?,?,'ESTUDIANTE','EVALUATIONS','EVALUATION_OPENED','Evaluation',?,localtimestamp) returning id",Long.class,f.student.getId(),f.student.getUsername(),""+base.evaluation);
        assertThat(service.apply(approved(USAGE,event,Map.of("scope","INSTITUTION","institutionId",base.foreign,"evidence","FIXTURE-RESOURCE-PARENT")))).isEqualTo("CONFLICT");
        var map=Map.of("scope","INSTITUTION","institutionId",base.institution,"evidence","FIXTURE-RESOURCE-PARENT");
        long id=approved(USAGE,event,map);assertThat(service.apply(id)).isEqualTo("APPLIED");
        f.sql().update("update evaluations set title='Changed fictitious parent' where id=?",base.evaluation);assertThat(service.apply(id)).isEqualTo("STALE");
    }
    @Test void boardEventCrossInstitutionAndStaleParentAreRejected() throws Exception {
        long id=appliedBoard();var cross=factMap();cross.put("institutionId",base.foreign);cross.put("classId",base.foreignClass);
        assertThat(service.apply(approved(USAGE,usage(),cross))).isEqualTo("CONFLICT");
        f.sql().update("update whiteboard_sessions set state_revision=state_revision+1 where id=?",f.board);
        assertThat(service.apply(approved(USAGE,usage(),factMap()))).isEqualTo("CONFLICT");assertThat(service.apply(id)).isEqualTo("STALE");
    }
    @Test void ledgerAndContextHistoryCannotBeRewrittenOrManuallyInvented() throws Exception {
        long id=appliedBoard();
        for(String q:List.of("update t11b4_participant_contexts set enrollment_id=null","delete from t11b4_board_contexts","truncate t11b4_reconciliation_events","insert into t11b4_reconciliation_events(reconciliation_id,status,snapshot) values("+id+",'APPLIED','{}')")) assertThatThrownBy(()->f.sql().execute(q)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->f.sql().execute("update system_logs set target_type='WhiteboardSession'")).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }
    @Test void predecessorFingerprintsRepeatAndCatalogDriftIsRejectedWithoutRepair() {
        var before=versions();f.sql().execute(expansion);f.sql().execute(base.expansion);assertThat(versions()).isEqualTo(before);
        f.sql().execute("alter table t11b4_participant_contexts disable trigger t11b4_reference");
        try {assertThatThrownBy(()->f.sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");}finally{f.sql().execute("alter table t11b4_participant_contexts enable trigger t11b4_reference");}f.sql().execute(expansion);
    }
    @Test void serializedQuotaMatchesEveryStoredCopyIncludingCaptureAndHistory() throws Exception {
        f.sql().update("update whiteboard_sessions set final_snapshot_data=decode('010203040506','hex') where id=?",f.board);long id=appliedBoard();
        long bytes=0,rows=0;
        for(String t:List.of("t11b4_reconciliations","t11b4_reconciliation_events","t11b4_board_contexts","t11b4_participant_contexts","t11b4_usage_contexts","t11b4_audit_contexts")) {
            String predicate=t.equals("t11b4_reconciliations")?"id":"reconciliation_id";
            bytes+=count("select coalesce(sum(octet_length(to_jsonb(t)::text)),0) from "+t+" t where "+predicate+"=?",id);
            rows+=count("select count(*) from "+t+" where "+predicate+"=?",id);
        }
        assertThat(count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='REVISION_BYTES'",f.teacher.getId())).isEqualTo(bytes);
        assertThat(count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='REVISION_COUNT'",f.teacher.getId())).isEqualTo(rows);
        assertThat(count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='SNAPSHOT_BYTES'",f.teacher.getId())).isEqualTo(6);
    }
    @Test void proposalAndApprovalAuditFailuresRollbackTheirOwnHistoryAndQuota() throws Exception {
        var before=rehearsal();failAudit();try {assertThatThrownBy(()->proposed(BOARD,f.board,mapping())).isInstanceOf(AuditUnavailableException.class);}finally{recoverAudit();}
        assertThat(rehearsal()).isEqualTo(before);long id=proposed(BOARD,f.board,mapping());var pending=rehearsal();
        failAudit();try {assertThatThrownBy(()->approve(id)).isInstanceOf(AuditUnavailableException.class);}finally{recoverAudit();}
        assertThat(rehearsal()).isEqualTo(pending);assertThat(status(id)).isEqualTo("PROPOSED");approve(id);assertThat(service.apply(id)).isEqualTo("APPLIED");
    }
    @Test void actorComesFromSessionPrincipalAndCannotReplaceOriginalBoardAuthor() throws Exception {
        var principal=new SessionPrincipal(f.otherTeacher.getId(),f.otherTeacher.getUsername(),UUID.randomUUID(),0,Role.DOCENTE,false);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal,null,List.of()));
        try {
            long id=appliedBoard();assertThat(count("select changed_by from t11b4_reconciliations where id=?",id)).isEqualTo(f.otherTeacher.getId());
            assertThat(count("select actor_user_id from system_logs where target_type='BoardActivityReconciliation' and target_id=? and event_type='RESOURCE_RECONCILIATION_APPLIED'",id)).isEqualTo(f.otherTeacher.getId());
            assertThat(count("select author_account_id from t11b4_board_contexts where board_id=?",f.board)).isEqualTo(f.teacher.getId());
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void globalUsageAndUnresolvedGenericActivityDoNotReceiveASchool() throws Exception {
        long id=f.sql().queryForObject("insert into usage_events(user_id,username,user_role,module,event_type,occurred_at) values(?,?,'ESTUDIANTE','SYSTEM_STATUS','MODULE_ACCESS',localtimestamp) returning id",Long.class,f.student.getId(),f.student.getUsername());
        assertThat(service.apply(approved(USAGE,id,Map.of("scope","INSTITUTION","institutionId",base.institution,"evidence","FIXTURE-GLOBAL")))).isEqualTo("CONFLICT");
        assertThat(service.apply(approved(USAGE,id,Map.of("scope","GLOBAL","evidence","FIXTURE-GLOBAL")))).isEqualTo("APPLIED");
        long generic=f.sql().queryForObject("insert into usage_events(user_id,username,user_role,module,event_type,occurred_at) values(?,?,'ESTUDIANTE','DASHBOARD','MODULE_ACCESS',localtimestamp) returning id",Long.class,f.student.getId(),f.student.getUsername());
        assertThat(service.apply(approved(USAGE,generic,Map.of()))).isEqualTo("UNRESOLVED");assertThat(count("select count(*) from t11b4_usage_contexts where event_id=?",generic)).isZero();
    }
    @Test void changedLegacyCheckIsDetectedDespiteEquivalentCastNormalization() {
        tx().executeWithoutResult(s->{
            String expression=f.sql().queryForObject("select pg_get_expr(conbin,conrelid) from pg_constraint where conrelid='usage_events'::regclass and conname='usage_events_user_role_check'",String.class);
            f.sql().execute("alter table usage_events drop constraint usage_events_user_role_check");
            f.sql().execute("alter table usage_events add constraint usage_events_user_role_check check(("+expression+") or user_role='UNKNOWN')");
            assertThatThrownBy(()->f.sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();
        });f.sql().execute(expansion);
    }
    @Test void actualApplyRaceAcrossTwoJvmsProducesOneContextAndOneSuccess() throws Exception {
        long id=approved(BOARD,f.board,mapping());Path dir=Files.createDirectories(Path.of("target","t11b4-processes",f.schema));List<Process> children=new ArrayList<>();Path go=dir.resolve("go");
        try(Connection barrier=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            barrier.setAutoCommit(false);
            try {
                for(int i=1;i<=2;i++) {
                    var cmd=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx384m","-Dspring.devtools.restart.enabled=false","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),BoardActivityReconciliationProcess.class.getName(),f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,""+id,dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString());
                    children.add(new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
                while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready")))&&children.stream().allMatch(Process::isAlive)&&System.nanoTime()<deadline)Thread.sleep(30);
                assertThat(Files.exists(dir.resolve("1.ready"))).as("child logs %s",dir).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();
                barrier.createStatement().execute("lock table whiteboard_sessions in share row exclusive mode");Files.writeString(go,"go");
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);List<Integer> dbPids=List.of();
                while(System.nanoTime()<deadline) {
                    dbPids=f.sql().queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%select t11b4_lock_sources()%'",Integer.class);
                    if(dbPids.size()==2)break;Thread.sleep(30);
                }
                assertThat(dbPids).hasSize(2);barrier.commit();
                for(Process p:children){assertThat(p.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
                assertThat(Files.readString(dir.resolve("1.result"))).isEqualTo("APPLIED");assertThat(Files.readString(dir.resolve("2.result"))).isEqualTo("APPLIED");
                assertThat(count("select count(*) from t11b4_board_contexts where board_id=?",f.board)).isEqualTo(1);
                assertThat(count("select count(*) from system_logs where target_type='BoardActivityReconciliation' and target_id=? and event_type='RESOURCE_RECONCILIATION_APPLIED'",id)).isEqualTo(1);
                Files.writeString(dir.resolve("evidence.json"),json(Map.of("osPids",children.stream().map(Process::pid).toList(),"blockedPostgresPids",dbPids,"results",List.of("APPLIED","APPLIED"))));
            }finally{barrier.rollback();for(Process p:children)if(p.isAlive())p.destroyForcibly();}
        }
    }
    @Test void nativeRestorePreservesAllTablesAndCertifiesPredecessorsAndB4() throws Exception {
        appliedBoard();assertThat(service.apply(approved(USAGE,usage(),factMap()))).isEqualTo("APPLIED");
        Path dir=Files.createDirectories(Path.of("target","t11b4-native",f.schema));String db="chemicallab_t11b4_restore_"+UUID.randomUUID().toString().replace("-","");
        Map<String,String> original=new TreeMap<>();for(String t:f.sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' order by relname",String.class)) original.put(t,f.sql().queryForObject(base.signature(t),String.class));
        String marker="SELECT md5(string_agg(definition,E'\\n' ORDER BY definition)) INTO actual FROM (";
        String catalogSql="select definition from ("+expansion.substring(expansion.lastIndexOf(marker)+marker.length(),expansion.lastIndexOf(") definitions;"))+ ") definitions order by definition";
        List<String> originalCatalog=f.sql().queryForList(catalogSql,String.class);Files.writeString(dir.resolve("original-catalog.json"),json(originalCatalog));
        String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));List<String> args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));boolean created=false;
        try {
            base.nativeTool("pg_dump",args,List.of("-Fc","-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            base.nativeTool("createdb",args,List.of(db),dir);created=true;base.nativeTool("pg_restore",args,List.of("--exit-on-error","--single-transaction","-d",db,dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            try(Connection restored=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement sql=restored.createStatement()) {
                sql.execute("set search_path to "+f.schema+", public");
                List<String> restoredCatalog=new ArrayList<>();try(ResultSet rows=sql.executeQuery(catalogSql)){while(rows.next())restoredCatalog.add(rows.getString(1));}
                Files.writeString(dir.resolve("restored-catalog.json"),json(restoredCatalog));assertThat(restoredCatalog).isEqualTo(originalCatalog);
                sql.execute(base.b1);sql.execute(base.expansion);sql.execute(expansion);
                Map<String,String> recovered=new TreeMap<>();for(String t:original.keySet())try(ResultSet rows=sql.executeQuery(base.signature(t))){rows.next();recovered.put(t,rows.getString(1));}assertThat(recovered).isEqualTo(original);
                for(String q:List.of("update system_logs set target_type='REST'","delete from system_logs","truncate system_logs cascade")) assertThatThrownBy(()->sql.execute(q)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
                assertThatThrownBy(()->sql.execute("select t11b4_apply((select min(id) from t11b4_reconciliations))")).isInstanceOf(SQLException.class).hasMessageContaining("fixtures only");
                sql.execute("alter table t11b4_board_contexts disable trigger t11b4_reference");assertThatThrownBy(()->sql.execute(expansion)).isInstanceOf(SQLException.class).hasMessageContaining("divergence");
                Files.writeString(dir.resolve("signatures.json"),json(Map.of("source",original,"restored",recovered,"tables",original.size(),"auditGuards","UPDATE/DELETE/TRUNCATE rejected","realBackfill","disabled")));
            }
        }finally{if(created)base.nativeTool("dropdb",args,List.of(db),dir);}
    }
}

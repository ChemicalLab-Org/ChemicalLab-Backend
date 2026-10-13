package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal fictitious rehearsal only. No endpoint, scheduler, permissions or legacy writer.
 * Snapshots reserve opportunities; they never authorize admission or correction exposure.
 * All copies, ledger/history, quotas and mandatory T08 SUCCESS share the business transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ResourceReconciliationService {
    public enum SourceKind { CONCEPT, EVALUATION }
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public long propose(SourceKind kind,long sourceId,String explicitMap) {
        fixtureOnly();
        jdbc.queryForObject("select pg_advisory_xact_lock(1133,hashtext(?)) is null",Boolean.class,kind.name()+":"+sourceId);
        Long prior=jdbc.query("""
            select id from t11b3_reconciliations where source_kind=? and source_pk=?
            and source_checksum=md5((t11b3_snapshot(?,?)||jsonb_build_object('map_context',t11b3_map_snapshot(?::jsonb)))::text)
            and proposal_checksum=md5(?::jsonb::text)
            """,rs->rs.next()?rs.getLong(1):null,kind.name(),sourceId,kind.name(),sourceId,explicitMap,explicitMap);
        if(prior!=null)return prior;
        long id=jdbc.queryForObject("""
            insert into t11b3_reconciliations(origin,source_kind,source_pk,source_version,source_snapshot,source_checksum,
              proposed,proposal_checksum,changed_by)
            select 'FICTITIOUS_LEGACY',?,?,'jsonb-v1',s,md5(s::text),?::jsonb,md5(?::jsonb::text),?
            from (select t11b3_snapshot(?,?)||jsonb_build_object('map_context',t11b3_map_snapshot(?::jsonb)) s) source
            returning id
            """,Long.class,kind.name(),sourceId,explicitMap,explicitMap,actor(),kind.name(),sourceId,explicitMap);
        evidence(LogEventType.RESOURCE_RECONCILIATION_PROPOSED,id);
        return id;
    }

    /** Approval binds both the source snapshot and exact proposed map. F08 real approvers stay pending. */
    public void approve(long id,String expectedChecksum,String fixtureEvidence) {
        fixtureOnly();
        var row=jdbc.queryForMap("select status,approval_evidence,md5(source_checksum||proposal_checksum) checksum from t11b3_reconciliations where id=? for update",id);
        if(!row.get("checksum").equals(expectedChecksum))throw new IllegalArgumentException("Proposal checksum mismatch");
        if(!row.get("status").equals("PROPOSED")) {
            if(!java.util.Objects.equals(row.get("approval_evidence"),fixtureEvidence))throw new IllegalArgumentException("Approval evidence mismatch");
            return;
        }
        jdbc.update("""
            update t11b3_reconciliations set status='APPROVED',approval_evidence=?,approved_checksum=?,approved_by=?,changed_by=? where id=?
            """,fixtureEvidence,expectedChecksum,actor(),actor(),id);
        evidence(LogEventType.RESOURCE_RECONCILIATION_APPROVED,id);
    }

    public String apply(long id) {
        fixtureOnly();
        // SQL acquires fixture table locks before the ledger row. Keep the same order in all processes.
        jdbc.execute("""
            lock table evaluations,evaluation_assignments,evaluation_questions,evaluation_options,evaluation_attempts,
            evaluation_answers,evaluation_attempt_adjustments,evaluation_attempt_events,student_profiles,teacher_profiles,
            concept_contents,concept_materials,concept_assignments,concept_content_formation_steps,concept_content_key_points,
            concept_content_examples,institutions,institution_memberships,academic_classes,enrollments in share row exclusive mode
            """);
        String previous=jdbc.queryForObject("select status from t11b3_reconciliations where id=? for update",String.class,id);
        String result=jdbc.queryForObject("select t11b3_apply(?,?)",String.class,id,actor());
        if(!result.equals(previous))evidence(switch(result) {
            case "APPLIED" -> LogEventType.RESOURCE_RECONCILIATION_APPLIED;
            case "CONFLICT" -> LogEventType.RESOURCE_RECONCILIATION_CONFLICT;
            case "STALE" -> LogEventType.RESOURCE_RECONCILIATION_STALE;
            default -> throw new IllegalStateException("Unexpected reconciliation status");
        },id);
        return result;
    }

    public long technicalRevision(SourceKind kind,long revisionId) {
        fixtureOnly();
        String table=kind==SourceKind.CONCEPT?"t11b3_concept_revisions":"t11b3_evaluation_revisions";
        String key=kind==SourceKind.CONCEPT?"concept_id":"evaluation_id";
        // Trusted internal clone: same frozen payload and provenance. No new opportunity root.
        jdbc.execute("lock table "+table+" in share row exclusive mode");
        long id=jdbc.queryForObject("insert into "+table+"("+key+",institution_id,revision_number,payload,payload_checksum,reconciliation_id) "
            +"select r."+key+",r.institution_id,(select max(revision_number)+1 from "+table+" where "+key+"=r."+key+")"
            +",r.payload,r.payload_checksum,r.reconciliation_id from "+table+" r where r.id=? returning id",Long.class,revisionId);
        audit.recordInfo(LogEventType.RESOURCE_REVISION_COPIED,"ResourceRevision",id,null,null,null,null);
        return id;
    }

    private void fixtureOnly() {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select current_database()='chemicallab_t01_test' and inet_server_addr()='127.0.0.1'::inet and current_schema() ~ '^t03_[a-f0-9]{32}$'",Boolean.class)))
            throw new IllegalStateException("Only owned disposable PostgreSQL fixtures are supported; real backfill disabled");
    }
    private Long actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null&&auth.getPrincipal() instanceof SessionPrincipal p?p.userId():null;
    }
    private void evidence(LogEventType type,long id) {
        audit.recordInfo(type,"ResourceReconciliation",id,null,type.name(),type.name(),null);
    }
}

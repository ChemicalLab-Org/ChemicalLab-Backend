package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owned fictitious rehearsals only. No controllers, jobs, legacy writers or access grants. */
@Service
@RequiredArgsConstructor
@Transactional
public class BoardActivityReconciliationService {
    public enum SourceKind { BOARD, USAGE, AUDIT }
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public long propose(SourceKind kind, long sourceId, String explicitMap) {
        lockSources();
        jdbc.queryForObject("select pg_advisory_xact_lock(1134,hashtext(?)) is null", Boolean.class, kind.name()+":"+sourceId);
        Long prior=jdbc.query("""
            select id from t11b4_reconciliations where source_kind=? and source_pk=?
            and source_checksum=md5(t11b4_snapshot(?,?,?::jsonb)::text)
            and proposal_checksum=md5(?::jsonb::text)
            """, rs->rs.next()?rs.getLong(1):null, kind.name(),sourceId,kind.name(),sourceId,explicitMap,explicitMap);
        if(prior!=null) return prior;
        long id=jdbc.queryForObject("""
            insert into t11b4_reconciliations(origin,source_kind,source_pk,source_version,source_snapshot,
                source_checksum,proposed,proposal_checksum,changed_by)
            select 'FICTITIOUS_LEGACY',?,?,'jsonb-v1',s,md5(s::text),?::jsonb,md5(?::jsonb::text),?
            from (select t11b4_snapshot(?,?,?::jsonb) s) source returning id
            """, Long.class,kind.name(),sourceId,explicitMap,explicitMap,actor(),kind.name(),sourceId,explicitMap);
        evidence(LogEventType.RESOURCE_RECONCILIATION_PROPOSED,id);
        return id;
    }

    public void approve(long id, String expectedChecksum, String fixtureEvidence) {
        lockSources();
        var row=jdbc.queryForMap("select status,approval_evidence,md5(source_checksum||proposal_checksum) checksum from t11b4_reconciliations where id=? for update",id);
        if(!row.get("checksum").equals(expectedChecksum)) throw new IllegalArgumentException("Proposal checksum mismatch");
        if(!row.get("status").equals("PROPOSED")) {
            if(!java.util.Objects.equals(row.get("approval_evidence"),fixtureEvidence)) throw new IllegalArgumentException("Approval evidence mismatch");
            return;
        }
        jdbc.update("update t11b4_reconciliations set status='APPROVED',approval_evidence=?,approved_checksum=?,approved_by=?,changed_by=? where id=?",
            fixtureEvidence,expectedChecksum,actor(),actor(),id);
        evidence(LogEventType.RESOURCE_RECONCILIATION_APPROVED,id);
    }

    public String apply(long id) {
        // Same table-before-ledger order as SQL. Locks exist only for the disposable rehearsal.
        lockSources();
        String previous=jdbc.queryForObject("select status from t11b4_reconciliations where id=? for update",String.class,id);
        String result=jdbc.queryForObject("select t11b4_apply(?,?)",String.class,id,actor());
        String persisted=jdbc.queryForObject("select status from t11b4_reconciliations where id=?",String.class,id);
        if(!java.util.Objects.equals(result,persisted)) throw new IllegalStateException("Reconciliation result does not match persisted status");
        if(!persisted.equals(previous)) evidence(switch(persisted) {
            case "APPLIED" -> LogEventType.RESOURCE_RECONCILIATION_APPLIED;
            case "CONFLICT", "UNRESOLVED" -> LogEventType.RESOURCE_RECONCILIATION_CONFLICT;
            case "STALE" -> LogEventType.RESOURCE_RECONCILIATION_STALE;
            default -> throw new IllegalStateException("Unexpected reconciliation status");
        },id);
        return persisted;
    }

    private void lockSources() { jdbc.execute("select t11b4_lock_sources()"); }
    private Long actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getPrincipal() instanceof SessionPrincipal p ? p.userId() : null;
    }
    private void evidence(LogEventType type,long id) {
        audit.recordInfo(type,"BoardActivityReconciliation",id,null,type.name(),type.name(),null);
    }
}

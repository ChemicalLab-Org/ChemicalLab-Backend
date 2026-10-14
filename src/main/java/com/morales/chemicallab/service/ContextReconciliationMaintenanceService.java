package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** P05 fixture-only versioned reconciliation. No public caller or live authorization.
 * JDBC evidence, versions, quota deltas and mandatory T08 share one transaction.
 */
@Service @RequiredArgsConstructor @Transactional
public class ContextReconciliationMaintenanceService {
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;
    private static final ObjectMapper JSON=new ObjectMapper();

    public long inventory(UUID operation) { internal();return command("INVENTORY",Map.of(),operation); }

    public long propose(String root,String mapping,String reason,UUID operation) {
        internal();return command("PROPOSE",Map.of("root",parse(root),"map",parse(mapping),"reason",reason),operation);
    }

    public String approvalChecksum(long proposal) {
        fixture();return jdbc.queryForObject("select md5(source_checksum||proposal_checksum||references_snapshot::text||heads::text||measurement_checksum) from t11b10_proposals where id=?",String.class,proposal);
    }

    /** A principal identifies the approver; a map cannot nominate a different account.
     * The fixture DB checks the exact current privilege and destination institution.
     */
    public long approve(long proposal,String role,long privilege,String checksum,String evidence,UUID operation) {
        fixture();var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof SessionPrincipal principal))
            throw new IllegalStateException("Authenticated fixture approver required");
        return command("APPROVE",Map.of("proposal",proposal,"role",role,"account",principal.userId(),"privilege",privilege,"checksum",checksum,"evidence",evidence),operation);
    }

    public long reject(long proposal,String reason,UUID operation) {
        internal();return command("REJECT",Map.of("proposal",proposal,"reason",reason),operation);
    }
    public String apply(long proposal,UUID operation) {
        internal();command("APPLY",Map.of("proposal",proposal),operation);
        return jdbc.queryForObject("select t11p05_status(?)",String.class,proposal);
    }
    private void fixture() {jdbc.execute("select t11p05_lock()");}
    private void internal() {
        if(SecurityContextHolder.getContext().getAuthentication()!=null)
            throw new IllegalStateException("Internal fixture maintainer only; T12 authorization pending");
        fixture();
    }
    private long command(String action,Map<String,?> request,UUID operation) {
        Objects.requireNonNull(operation,"Operation UUID required");String value=json(request);
        Long prior=jdbc.query("select result,action=? and request=?::jsonb identical from t11b10_commands where operation_id=?",rs->{
            if(!rs.next())return null;
            if(!rs.getBoolean(2))throw new IllegalArgumentException("Operation UUID reused with another request");
            return rs.getLong(1);
        },action,value,operation);
        if(prior!=null)return prior;
        long id=jdbc.queryForObject("insert into t11b10_commands(operation_id,action,request) values(?,?,?::jsonb) returning result",Long.class,operation,action,value);
        audit.recordInfo(LogEventType.ADMIN_ACTION,"ContextResolution",id,null,null,null,null);
        jdbc.execute("select t11p05_quota_check()");return id;
    }
    private static Object parse(String value) {
        try{return JSON.readTree(value);}catch(Exception failure){throw new IllegalArgumentException("Invalid fixture JSON",failure);}
    }
    private static String json(Object value) {
        try{return JSON.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("Invalid fixture request",failure);}
    }
}

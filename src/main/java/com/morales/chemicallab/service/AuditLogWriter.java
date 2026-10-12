package com.morales.chemicallab.service;
import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.repository.SystemLogRepository;
import com.morales.chemicallab.security.audit.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
/** Success participates in the business commit; rejection evidence follows business rollback. */
@Component @RequiredArgsConstructor
public class AuditLogWriter {
    private final SystemLogRepository systemLogRepository;
    private final JdbcTemplate jdbc;
    private final AuditSignals signals;
    @Transactional(propagation=Propagation.MANDATORY)
    public void persist(SystemLog log){
        try {systemLogRepository.saveAndFlush(log);}
        catch(RuntimeException ex){
            // Flush includes pending business entities: preserve the T07-B quota contract.
            if(com.morales.chemicallab.security.storage.StorageErrors.quota(ex))throw ex;
            signals.failure("required_persist_failed");throw new AuditUnavailableException();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){
                signals.count(status==STATUS_COMMITTED?"required_committed":"required_rolled_back");
                if(status==STATUS_UNKNOWN)signals.failure("commit_unknown");
            }
        });
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=2)
    public boolean persistRejection(SystemLog log){
        jdbc.execute("set local lock_timeout='250ms'");
        jdbc.execute("set local statement_timeout='1500ms'");
        int reserved=jdbc.update("""
            update audit_rejection_budget set
                used=case when window_start<date_trunc('minute',statement_timestamp()) then 1 else used+1 end,
                window_start=date_trunc('minute',statement_timestamp())
            where id=1 and window_start<=date_trunc('minute',statement_timestamp())
                and (window_start<date_trunc('minute',statement_timestamp()) or used<120)
            """);
        if(reserved==0)return false;
        systemLogRepository.saveAndFlush(log);
        return true;
    }
}

package com.morales.chemicallab.security.audit;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.AuditLogWriter;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** No pending queue. Bounds DB attempts even when the destination is down. */
@Component
public class AuditRejections {
    private final AuditLogWriter writer;
    private final AuditSignals signals;
    private final Semaphore writers=new Semaphore(2);
    private long window=System.nanoTime();
    private int admitted;
    public AuditRejections(AuditLogWriter writer,AuditSignals signals){this.writer=writer;this.signals=signals;}
    private synchronized boolean admit(){
        long now=System.nanoTime();
        if(now-window>=60_000_000_000L){window=now;admitted=0;}
        if(admitted>=120)return false;
        admitted++;return true;
    }
    public void record(LogEventType event,SessionPrincipal actor,String targetType,Long targetId,String reason,String declared){
        if(!admit() || !writers.tryAcquire()){signals.count("rejection_suppressed_local");return;}
        try {
            var evidence=SystemLog.builder().eventType(event).category(event.getDefaultCategory())
                .severity(event==LogEventType.OPERATION_FAILED?LogSeverity.ERROR:LogSeverity.WARNING)
                .actorUserId(actor==null?null:actor.userId()).actorUsername(actor==null?null:AuditSafety.text(actor.username(),100))
                .actorRole(actor==null?null:actor.role()).targetType(AuditSafety.target(targetType)).targetId(targetId)
                .action(event.name()).description(event.name()).metadata(AuditSafety.metadata(reason))
                .declaredIdentifier(AuditSafety.identifierFingerprint(declared))
                .outcome(event==LogEventType.OPERATION_FAILED?"FAILED":"REJECTED")
                .correlationId(AuditContext.correlation()).createdAt(LocalDateTime.now(java.time.ZoneId.of("America/Lima"))).build();
            if(!writer.persistRejection(evidence)) signals.count("rejection_suppressed_shared");
            else signals.count("rejection_committed");
        } catch(RuntimeException ex){signals.failure("rejection_persist_failed");}
        finally {writers.release();}
    }
}

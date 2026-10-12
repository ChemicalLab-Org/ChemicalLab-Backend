package com.morales.chemicallab.security.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-cardinality counters; emergency logging never uses the failed database writer. */
@Component
public class AuditSignals {
    private final MeterRegistry registry;
    private final AtomicLong lastWarning=new AtomicLong();
    public AuditSignals(MeterRegistry registry){this.registry=registry;}
    public void count(String signal){registry.counter("security.audit", "signal", signal).increment();}
    public void failure(String signal){
        count(signal);
        long now=System.nanoTime(), previous=lastWarning.get();
        if((previous==0 || now-previous>=60_000_000_000L) && lastWarning.compareAndSet(previous,now))
            LoggerFactory.getLogger(AuditSignals.class).error("SECURITY_AUDIT_EVIDENCE_UNAVAILABLE signal={}",signal);
    }
}

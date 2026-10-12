package com.morales.chemicallab.service;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** One explicit batch; no scheduler. Only expendable usage_events, never security/academic records. */
@Service @RequiredArgsConstructor
public class TechnicalRetentionService {
    private final JdbcTemplate jdbc;
    @Value("${app.retention.delete-enabled:false}") private boolean enabled;
    @Value("${app.retention.batch-size:500}") private int batchSize;
    @Value("${app.retention.minimum-age-days:180}") private int minimumAgeDays;
    public record Result(boolean simulation,boolean coordinated,int candidates,int deleted,List<Long> ids,LocalDateTime cutoff){}
    @Transactional(timeout=15)
    public Result batch(LocalDateTime cutoff,boolean simulation) {
        if(batchSize<1 || batchSize>1000 || minimumAgeDays<1 || cutoff==null || cutoff.isAfter(LocalDateTime.now().minusDays(minimumAgeDays)))
            throw new IllegalArgumentException("Retención: lote 1–1000 y fecha anterior al plazo mínimo configurado.");
        if(!simulation && !enabled) throw new IllegalArgumentException("La eliminación técnica está desactivada. Usa simulación.");
        // A schema-local row lock serializes cooperating instances, and releases on rollback/crash.
        var lock=jdbc.queryForList("select id from technical_retention_state where id=1 for update skip locked",Integer.class);
        if(lock.isEmpty())return new Result(simulation,false,0,0,List.of(),cutoff);
        var ids=jdbc.queryForList("select id from usage_events where occurred_at<? order by occurred_at,id limit ?",Long.class,cutoff,batchSize);
        int deleted=0;
        if(!simulation && !ids.isEmpty()) {
            // Selected IDs are bounded; quota releases belong to this same transaction.
            for(long id:ids)deleted+=jdbc.update("delete from usage_events where id=? and occurred_at<?",id,cutoff);
            jdbc.update("update technical_retention_state set last_cutoff=?,last_deleted=? where id=1",cutoff,deleted);
        }
        return new Result(simulation,true,ids.size(),deleted,List.copyOf(ids),cutoff);
    }
}

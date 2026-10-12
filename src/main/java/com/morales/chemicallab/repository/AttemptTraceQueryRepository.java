package com.morales.chemicallab.repository;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.AttemptEventType;
import com.morales.chemicallab.security.storage.QueryLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

/** Scalar summaries cover the entire attempt; the timeline alone is paginated. */
@Component @RequiredArgsConstructor
public class AttemptTraceQueryRepository {
    private final JdbcTemplate jdbc;
    private final QueryLimits limits;
    public record Trace(long total,long exits,long returns,long exitAttempts,List<String> tools,QueryPage<AttemptEventResponse> page) {}
    public Trace trace(long attempt,int page,int size) {
        limits.page(page,size);
        var counts=jdbc.queryForMap("""
            select count(*) total,count(*) filter(where event_type in ('TAB_HIDDEN','WINDOW_BLUR')) exits,
            count(*) filter(where event_type in ('TAB_VISIBLE','WINDOW_FOCUS')) returns,
            count(*) filter(where event_type='EXIT_ATTEMPTED') exit_attempts
            from evaluation_attempt_events where attempt_id=?
            """,attempt);
        var tools=jdbc.queryForList("""
            select tool from (select trim(substring(metadata from '(?:^|;)\\s*tool=([^;]*)')) tool,
            min(occurred_at) first_seen from evaluation_attempt_events where attempt_id=? and event_type='TOOL_OPENED'
            group by tool) x where tool is not null and tool<>'' order by first_seen,tool limit 101
            """,String.class,attempt);
        if(tools.size()>100)throw new IllegalArgumentException("Demasiadas herramientas históricas; requieren revisión.");
        var rows=jdbc.query("select id,event_type,description,metadata,occurred_at from evaluation_attempt_events where attempt_id=? order by occurred_at,id limit ? offset ?",
            (r,n)->new AttemptEventResponse(r.getLong(1),AttemptEventType.valueOf(r.getString(2)),r.getString(3),r.getString(4),r.getObject(5,LocalDateTime.class)),attempt,size,(long)page*size);
        long total=((Number)counts.get("total")).longValue();
        return new Trace(total,((Number)counts.get("exits")).longValue(),((Number)counts.get("returns")).longValue(),((Number)counts.get("exit_attempts")).longValue(),tools,QueryPage.of(rows,page,size,total));
    }
}

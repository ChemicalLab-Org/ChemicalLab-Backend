package com.morales.chemicallab.repository;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.storage.QueryLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Component
@org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,timeout=15)
public class StudentUsageQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final QueryLimits limits;
    private final String sql;
    public StudentUsageQueryRepository(NamedParameterJdbcTemplate jdbc, QueryLimits limits,
            @Value("classpath:security/student-usage.sql") Resource resource) throws IOException {
        this.jdbc=jdbc;this.limits=limits;this.sql=resource.getContentAsString(StandardCharsets.UTF_8);
    }
    public StudentUsageRecordsResponse records(Map<String,Object> filters,int page,int size) {
        limits.page(page,size);
        jdbc.getJdbcTemplate().execute("set local jit=off");
        var p=new HashMap<>(filters);p.put("limit",size);p.put("offset",(long)page*size);
        var summary=jdbc.queryForObject(sql+"""
            select count(*) total,count(*) filter(where role='ESTUDIANTE' and has_activity) active_students,
            round(avg(progress)::numeric,1)::double precision progress,round(avg(accuracy)::numeric,1)::double precision accuracy,
            coalesce(sum(sessions),0)::bigint sessions,
            (select module from events e join filtered f on f.user_id=e.user_id group by module order by count(*) desc,module limit 1) top_module,
            (select count(*) from events e join filtered f on f.user_id=e.user_id group by module order by count(*) desc,module limit 1) top_count
            from filtered
            """,p,(r,n)->new StudentUsageRecordsResponse.Summary(r.getLong("total"),r.getLong("active_students"),r.getObject("progress",Double.class),r.getObject("accuracy",Double.class),r.getLong("sessions"),r.getString("top_module"),r.getObject("top_count",Long.class)));
        var rows=jdbc.query(sql+" select * from filtered order by (role<>'ESTUDIANTE'),lower(coalesce(full_name,username)),user_id limit :limit offset :offset",p,this::record);
        return new StudentUsageRecordsResponse(summary,rows,QueryPage.of(rows,page,size,summary.totalUsers()));
    }
    public StudentUsageRecordDetailResponse detail(Long userId,int page,int size) {
        limits.page(page,size);
        jdbc.getJdbcTemplate().execute("set local jit=off");
        var p=empty();p.put("userId",userId);
        var rows=jdbc.query(sql+" select * from filtered",p,this::record);
        if(rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"El usuario indicado no existe.");
        var events=jdbc.query("select module,event_type,resource_type,description,occurred_at from usage_events where user_id=:userId order by occurred_at desc,id desc limit 20",p,
            (r,n)->new StudentUsageRecordDetailResponse.UsageEventItem(UsageModule.valueOf(r.getString(1)),UsageEventType.valueOf(r.getString(2)),r.getString(3),r.getString(4),r.getObject(5,LocalDateTime.class)));
        var incidents=jdbc.query("select severity,event_type,action,description,created_at from system_logs where actor_user_id=:userId and event_type<>'LOGIN_SUCCESS' and severity in ('WARNING','ERROR') order by created_at desc,id desc limit 20",p,
            (r,n)->new StudentUsageRecordDetailResponse.IncidentItem(LogSeverity.valueOf(r.getString(1)),LogEventType.valueOf(r.getString(2)),r.getString(3),r.getString(4),r.getObject(5,LocalDateTime.class)));
        String evaluations="""
            with student as (select id,grade,section from student_profiles where user_id=:userId),
            assigned as (select distinct a.evaluation_id from evaluation_assignments a join student s on trim(a.grade)=trim(s.grade) and upper(trim(a.section))=upper(trim(s.section))
            join evaluations e on e.id=a.evaluation_id where a.active and e.active and e.status='PUBLISHED'),
            attempts as (select a.* from evaluation_attempts a join student s on s.id=a.student_id),
            ids as (select evaluation_id from assigned union select evaluation_id from attempts),
            items as (select e.id,e.title,exists(select 1 from assigned x where x.evaluation_id=e.id) assigned,
            count(a.id) attempts,bool_or(a.status in ('SUBMITTED','PENDING_MANUAL_REVIEW','GRADED')) completed,
            max(coalesce(a.submitted_at,a.started_at)) last_attempt from ids join evaluations e on e.id=ids.evaluation_id
            left join attempts a on a.evaluation_id=e.id group by e.id)
            """;
        long total=jdbc.queryForObject(evaluations+" select count(*) from items",p,Long.class);
        p.put("limit",size);p.put("offset",(long)page*size);
        var items=jdbc.query(evaluations+" select * from items order by last_attempt desc nulls last,id desc limit :limit offset :offset",p,
            (r,n)->new StudentUsageRecordDetailResponse.EvaluationUsageItem(r.getLong("id"),r.getString("title"),r.getBoolean("assigned"),r.getLong("attempts"),r.getBoolean("completed"),r.getObject("last_attempt",LocalDateTime.class)));
        return new StudentUsageRecordDetailResponse(rows.get(0),events,items,incidents,QueryPage.of(items,page,size,total));
    }
    public static Map<String,Object> empty() {
        var p=new HashMap<String,Object>();for(String k:List.of("role","grade","section","userId","search","module","from","to"))p.put(k,null);p.put("onlyActivity",false);return p;
    }
    private StudentUsageRecordResponse record(ResultSet r,int n) throws SQLException {
        boolean student=r.getString("role").equals("ESTUDIANTE") && r.getObject("student_id")!=null;
        String modules=r.getString("visited_modules");
        long errors=r.getLong("errors"),warnings=r.getLong("warnings");
        var incidentParts=new ArrayList<String>();
        if(errors>0)incidentParts.add(errors+(errors==1?" error":" errores"));
        if(warnings>0)incidentParts.add(warnings+(warnings==1?" advertencia":" advertencias"));
        return new StudentUsageRecordResponse(r.getLong("user_id"),r.getObject("student_id",Long.class),r.getString("student_code"),r.getString("username"),r.getString("full_name"),Role.valueOf(r.getString("role")),r.getString("grade"),r.getString("section"),r.getLong("minutes"),r.getLong("sessions"),r.getInt("modules"),modules==null?List.of():List.of(modules.split(",")),student?r.getLong("assigned"):null,student?r.getLong("completed"):null,r.getObject("progress",Double.class),student?r.getLong("attempts"):null,student?r.getLong("correct"):null,student?r.getLong("incorrect"):null,r.getObject("accuracy",Double.class),student?r.getLong("feedback"):null,errors+warnings,incidentParts.isEmpty()?null:String.join(" · ",incidentParts),r.getObject("last_activity",LocalDateTime.class));
    }
}

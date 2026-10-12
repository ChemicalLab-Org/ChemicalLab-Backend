package com.morales.chemicallab.repository;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.storage.QueryLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Component @RequiredArgsConstructor
public class WhiteboardQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final QueryLimits limits;
    private static final String COLUMNS="""
        b.id,b.name,b.description,b.teacher_id,t.names||' '||t.last_names teacher_name,b.grade,b.section,b.status,
        b.interaction_enabled,b.final_snapshot_size,b.created_at,b.started_at,b.paused_at,b.resumed_at,b.closed_at,b.closed_by,
        (select count(*) from whiteboard_participants p where p.session_id=b.id) participants
        """;
    private static final String FROM=" from whiteboard_sessions b join teacher_profiles t on t.id=b.teacher_id ";
    private <T> QueryPage<T> query(String columns,String where,Map<String,Object> p,int page,int size,String order,org.springframework.jdbc.core.RowMapper<T> mapper) {
        limits.page(page,size);
        long total=jdbc.queryForObject("select count(*)"+FROM+where,p,Long.class);
        p.put("limit",size);p.put("offset",(long)page*size);
        var rows=jdbc.query("select "+columns+FROM+where+" order by "+order+" limit :limit offset :offset",p,mapper);
        return QueryPage.of(rows,page,size,total);
    }
    public QueryPage<WhiteboardSessionResponse> teacher(long teacherId,int page,int size) {
        var p=new HashMap<String,Object>();p.put("teacher",teacherId);
        return query(COLUMNS,"where b.teacher_id=:teacher",p,page,size,"b.created_at desc,b.id desc",(r,n)->
            new WhiteboardSessionResponse(r.getLong("id"),r.getString("name"),r.getString("description"),r.getLong("teacher_id"),r.getString("teacher_name"),r.getString("grade"),r.getString("section"),status(r),r.getBoolean("interaction_enabled"),r.getInt("participants"),snapshot(r),date(r,"created_at"),date(r,"started_at"),date(r,"paused_at"),date(r,"resumed_at"),date(r,"closed_at"),r.getString("closed_by")));
    }
    public QueryPage<WhiteboardAdminSessionResponse> admin(int page,int size) {
        return query(COLUMNS,"",new HashMap<>(),page,size,"b.created_at desc,b.id desc",(r,n)->new WhiteboardAdminSessionResponse(r.getLong("id"),r.getString("name"),r.getLong("teacher_id"),r.getString("teacher_name"),r.getString("grade"),r.getString("section"),status(r),r.getBoolean("interaction_enabled"),r.getInt("participants"),snapshot(r),date(r,"created_at"),date(r,"closed_at")));
    }
    private Map<String,Object> studentParams(StudentProfile student) {
        var p=new HashMap<String,Object>();p.put("grade",student.getGrade());p.put("section",student.getSection());p.put("student",student.getId());return p;
    }
    public QueryPage<WhiteboardStudentSessionResponse> active(StudentProfile student,int page,int size) {
        String columns=COLUMNS+",(select p.interaction_override from whiteboard_participants p where p.session_id=b.id and p.student_id=:student) override";
        return query(columns,"where b.grade=:grade and b.section=:section and b.status in ('ACTIVE','PAUSED')",studentParams(student),page,size,"b.created_at desc,b.id desc",(r,n)->{
            String override=r.getString("override");boolean joined=override!=null;
            boolean can=joined && status(r)==WhiteboardSessionStatus.ACTIVE && com.morales.chemicallab.service.WhiteboardInteractionPolicy.effective(r.getBoolean("interaction_enabled"),WhiteboardInteractionOverride.valueOf(override));
            return new WhiteboardStudentSessionResponse(r.getLong("id"),r.getString("name"),r.getString("description"),r.getString("teacher_name"),r.getString("grade"),r.getString("section"),status(r),r.getBoolean("interaction_enabled"),joined,can,snapshot(r),date(r,"created_at"),date(r,"started_at"),date(r,"closed_at"));
        });
    }
    public QueryPage<WhiteboardHistoryItemResponse> history(StudentProfile student,int page,int size) {
        return query(COLUMNS,"where b.grade=:grade and b.section=:section and b.status='CLOSED'",studentParams(student),page,size,"b.closed_at desc nulls last,b.id desc",(r,n)->new WhiteboardHistoryItemResponse(r.getLong("id"),r.getString("name"),r.getString("teacher_name"),r.getString("grade"),r.getString("section"),status(r),snapshot(r),date(r,"closed_at")));
    }
    private static LocalDateTime date(ResultSet r,String key)throws SQLException{return r.getObject(key,LocalDateTime.class);}
    private static boolean snapshot(ResultSet r)throws SQLException{return r.getLong("final_snapshot_size")>0;}
    private static WhiteboardSessionStatus status(ResultSet r)throws SQLException{return WhiteboardSessionStatus.valueOf(r.getString("status"));}
}

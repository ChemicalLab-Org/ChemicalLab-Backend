package com.morales.chemicallab.repository;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.storage.QueryLimits;
import com.morales.chemicallab.service.WhiteboardInteractionPolicy;
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
    private static final String METADATA="""
        b.id,b.name,b.description,b.teacher_id,t.names||' '||t.last_names teacher_name,b.grade,b.section,b.status,
        b.interaction_enabled,b.final_snapshot_size,b.created_at,b.started_at,b.paused_at,b.resumed_at,b.closed_at,b.closed_by
        """;
    private static final String COLUMNS=METADATA+",(select count(*) from whiteboard_participants p where p.session_id=b.id) participants";
    private static final String FROM=" from whiteboard_sessions b join teacher_profiles t on t.id=b.teacher_id ";
    private static final String SCOPE="""
        where exists(select 1 from board_runtime.boards s
          where s.board_id=b.id and s.institution_id=:institution and s.class_id=:class)
        """;
    private <T> QueryPage<T> query(String columns,String where,Map<String,Object> p,int page,int size,String order,org.springframework.jdbc.core.RowMapper<T> mapper) {
        limits.page(page,size);
        long total=jdbc.queryForObject("select count(*)"+FROM+where,p,Long.class);
        p.put("limit",size);p.put("offset",(long)page*size);
        var rows=jdbc.query("select "+columns+FROM+where+" order by "+order+" limit :limit offset :offset",p,mapper);
        return QueryPage.of(rows,page,size,total);
    }
    public QueryPage<WhiteboardSessionResponse> teacher(long teacherId,int page,int size) {
        var p=new HashMap<String,Object>();p.put("teacher",teacherId);
        return query(COLUMNS,"where b.teacher_id=:teacher",p,page,size,"b.created_at desc,b.id desc",WhiteboardQueryRepository::teacherRow);
    }
    public QueryPage<WhiteboardSessionResponse> scopedTeacher(long institution,long clazz,int page,int size) {
        String columns=METADATA+",(select count(*) from board_runtime.current_admissions a where a.board_id=b.id) participants";
        return query(columns,SCOPE,scope(institution,clazz),page,size,"b.created_at desc,b.id desc",WhiteboardQueryRepository::teacherRow);
    }
    public Optional<WhiteboardSessionResponse> scopedTeacherHeader(long institution, long clazz, long board) {
        var params = scope(institution, clazz);
        params.put("board", board);
        // Keep the shared lock and the same availability test as the entity, even for older rows.
        var rows = jdbc.query("select " + METADATA + ",octet_length(b.final_snapshot_data)>0 snapshot_available"
            + FROM + SCOPE + " and b.id=:board for share of b", params,
            (row, index) -> teacherRow(row, 0, row.getBoolean("snapshot_available")));
        return rows.stream().findFirst();
    }

    public List<WhiteboardParticipantResponse> currentParticipants(long board, String grade, String section, boolean enabled) {
        return jdbc.query("""
            select p.id,p.student_id,s.names,s.last_names,p.interaction_override,p.joined_at,p.last_seen_at
            from board_runtime.current_admissions a
            join whiteboard_participants p on p.id=a.participant_id
            join student_profiles s on s.id=p.student_id
            where a.board_id=:board
            order by p.id
            """, Map.of("board", board), (row, index) -> {
                var override = WhiteboardInteractionOverride.valueOf(row.getString("interaction_override"));
                return new WhiteboardParticipantResponse(row.getLong("id"), row.getLong("student_id"),
                    row.getString("names") + " " + row.getString("last_names"), "", grade, section, override,
                    WhiteboardInteractionPolicy.effective(enabled, override), date(row, "joined_at"), date(row, "last_seen_at"));
            });
    }
    public QueryPage<WhiteboardStudentSessionResponse> scopedActive(long institution,long clazz,long account,int page,int size) {
        var params=scope(institution,clazz);
        params.put("account",account);
        String columns=METADATA+"""
            ,(select p.interaction_override from board_runtime.current_admissions a
              join whiteboard_participants p on p.id=a.participant_id where a.board_id=b.id and a.account_id=:account) override
            """;
        return query(columns,SCOPE+" and b.status in ('ACTIVE','PAUSED')",params,page,size,"b.created_at desc,b.id desc",
            WhiteboardQueryRepository::studentRow);
    }
    public QueryPage<WhiteboardHistoryItemResponse> scopedHistory(long institution,long clazz,long account,int page,int size) {
        var params=scope(institution,clazz);
        params.put("account",account);
        return query(METADATA,SCOPE+"""
            and b.status='CLOSED' and exists(select 1 from board_runtime.current_admissions a
                where a.board_id=b.id and a.account_id=:account)
            """,params,page,size,"b.closed_at desc nulls last,b.id desc",WhiteboardQueryRepository::historyRow);
    }
    private static Map<String,Object> scope(long institution,long clazz) {
        return new HashMap<>(Map.of("institution",institution,"class",clazz));
    }
    private static WhiteboardSessionResponse teacherRow(ResultSet r,int row)throws SQLException {
        return teacherRow(r, r.getInt("participants"), snapshot(r));
    }
    private static WhiteboardSessionResponse teacherRow(ResultSet row, int participants, boolean snapshotAvailable)
            throws SQLException {
        return new WhiteboardSessionResponse(row.getLong("id"), row.getString("name"), row.getString("description"),
            row.getLong("teacher_id"), row.getString("teacher_name"), row.getString("grade"), row.getString("section"),
            status(row), row.getBoolean("interaction_enabled"), participants, snapshotAvailable,
            date(row, "created_at"), date(row, "started_at"), date(row, "paused_at"), date(row, "resumed_at"),
            date(row, "closed_at"), row.getString("closed_by"));
    }
    public QueryPage<WhiteboardAdminSessionResponse> admin(int page,int size) {
        return query(COLUMNS,"",new HashMap<>(),page,size,"b.created_at desc,b.id desc",(r,n)->new WhiteboardAdminSessionResponse(r.getLong("id"),r.getString("name"),r.getLong("teacher_id"),r.getString("teacher_name"),r.getString("grade"),r.getString("section"),status(r),r.getBoolean("interaction_enabled"),r.getInt("participants"),snapshot(r),date(r,"created_at"),date(r,"closed_at")));
    }
    private Map<String,Object> studentParams(StudentProfile student) {
        var p=new HashMap<String,Object>();p.put("grade",student.getGrade());p.put("section",student.getSection());p.put("student",student.getId());return p;
    }
    public QueryPage<WhiteboardStudentSessionResponse> active(StudentProfile student,int page,int size) {
        String columns=COLUMNS+",(select p.interaction_override from whiteboard_participants p where p.session_id=b.id and p.student_id=:student) override";
        return query(columns,"where b.grade=:grade and b.section=:section and b.status in ('ACTIVE','PAUSED')",studentParams(student),page,size,"b.created_at desc,b.id desc",WhiteboardQueryRepository::studentRow);
    }
    private static WhiteboardStudentSessionResponse studentRow(ResultSet r,int row)throws SQLException {
        String override=r.getString("override");boolean joined=override!=null;
        boolean can=joined && status(r)==WhiteboardSessionStatus.ACTIVE && com.morales.chemicallab.service.WhiteboardInteractionPolicy.effective(r.getBoolean("interaction_enabled"),WhiteboardInteractionOverride.valueOf(override));
        return new WhiteboardStudentSessionResponse(r.getLong("id"),r.getString("name"),r.getString("description"),r.getString("teacher_name"),r.getString("grade"),r.getString("section"),status(r),r.getBoolean("interaction_enabled"),joined,can,snapshot(r),date(r,"created_at"),date(r,"started_at"),date(r,"closed_at"));
    }
    public QueryPage<WhiteboardHistoryItemResponse> history(StudentProfile student,int page,int size) {
        return query(COLUMNS,"where b.grade=:grade and b.section=:section and b.status='CLOSED'",studentParams(student),page,size,"b.closed_at desc nulls last,b.id desc",WhiteboardQueryRepository::historyRow);
    }
    private static WhiteboardHistoryItemResponse historyRow(ResultSet r,int row)throws SQLException {
        return new WhiteboardHistoryItemResponse(r.getLong("id"),r.getString("name"),r.getString("teacher_name"),r.getString("grade"),r.getString("section"),status(r),snapshot(r),date(r,"closed_at"));
    }
    private static LocalDateTime date(ResultSet r,String key)throws SQLException{return r.getObject(key,LocalDateTime.class);}
    private static boolean snapshot(ResultSet r)throws SQLException{return r.getLong("final_snapshot_size")>0;}
    private static WhiteboardSessionStatus status(ResultSet r)throws SQLException{return WhiteboardSessionStatus.valueOf(r.getString("status"));}
}

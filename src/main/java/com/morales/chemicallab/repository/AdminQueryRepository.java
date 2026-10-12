package com.morales.chemicallab.repository;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.security.storage.QueryLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Explicit scalar projections: no passwords, binary columns or entity hydration. */
@Component @RequiredArgsConstructor
public class AdminQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final QueryLimits limits;
    private static final String FROM = """
        from user_accounts u
        left join student_profiles s on s.user_id=u.id and u.role='ESTUDIANTE'
        left join teacher_profiles t on t.user_id=u.id and u.role='DOCENTE'
        left join teacher_profiles responsible on responsible.id=s.teacher_id
        """;
    public QueryPage<AdminUserResponse> users(int page, int size, String search, String filter, Long self) {
        limits.page(page, size);
        var p = new HashMap<String,Object>();
        String where = " where true";
        if (filter != null && !filter.equals("all")) {
            if (filter.equals("active") || filter.equals("inactive")) {
                where += " and u.active=:active"; p.put("active", filter.equals("active"));
            } else { Role role = Role.valueOf(filter); where += " and u.role=:role"; p.put("role", role.name()); }
        }
        search = QueryLimits.search(search);
        if (search != null && !search.isEmpty()) {
            where += " and position(lower(:search) in lower(concat_ws(' ',u.username,u.email,s.student_code,s.names,s.last_names,t.names,t.last_names,case u.role when 'ADMINISTRADOR' then 'Administrador' when 'DOCENTE' then 'Docente' else 'Estudiante' end)))>0";
            p.put("search",search);
        }
        long total = jdbc.queryForObject("select count(*) " + FROM + where, p, Long.class);
        p.put("limit",size); p.put("offset",(long)page*size);
        var rows = jdbc.query("""
            select u.id, u.username, u.email, u.role, u.active, u.temporary_password, u.created_at,
            coalesce(s.names,t.names) names, coalesce(s.last_names,t.last_names) last_names,
            coalesce(s.names||' '||s.last_names,t.names||' '||t.last_names,u.username) full_name,
            s.student_code,s.grade,s.section,responsible.user_id teacher_user_id
            """ + FROM + where + " order by u.role, lower(coalesce(s.names||' '||s.last_names,t.names||' '||t.last_names,u.username)),u.id limit :limit offset :offset",
            p, (r,n)-> new AdminUserResponse(r.getLong("id"),r.getString("full_name"),r.getString("names"),r.getString("last_names"),r.getString("username"),r.getString("student_code"),r.getString("email"),Role.valueOf(r.getString("role")),r.getBoolean("active"),r.getBoolean("temporary_password"),r.getObject("created_at",java.time.LocalDateTime.class),r.getObject("teacher_user_id",Long.class),r.getString("grade"),r.getString("section"),Objects.equals(self,r.getLong("id"))));
        return QueryPage.of(rows,page,size,total);
    }
    public QueryPage<TeacherOptionResponse> teachers(int page,int size,String search,Long selected) {
        limits.page(page,size); search=QueryLimits.search(search);
        var p = new HashMap<String,Object>();
        String where=" where u.active=true and u.role='DOCENTE'";
        if (search!=null && !search.isBlank()) { where+=" and (position(lower(:search) in lower(t.names||' '||t.last_names||' '||u.username))>0 or u.id=:selected)"; p.put("search",search);p.put("selected",selected); }
        String from=" from teacher_profiles t join user_accounts u on u.id=t.user_id";
        long total=jdbc.queryForObject("select count(*)"+from+where,p,Long.class);
        p.put("limit",size);p.put("offset",(long)page*size);
        var rows=jdbc.query("select u.id,t.names||' '||t.last_names full_name,u.username"+from+where+" order by (u.id=coalesce(:selected,-1)) desc,lower(t.names||' '||t.last_names),u.id limit :limit offset :offset",withSelected(p,selected),(r,n)->new TeacherOptionResponse(r.getLong("id"),r.getString("full_name"),r.getString("username")));
        return QueryPage.of(rows,page,size,total);
    }
    private Map<String,Object> withSelected(Map<String,Object> p,Long selected) {p.put("selected",selected);return p;}
    public int nextStudentCode() {
        return jdbc.queryForObject("select coalesce(max(substring(student_code from 4)::integer),0)+1 from student_profiles where student_code ~ '^EST[0-9]{4}$'",Map.of(),Integer.class);
    }
}

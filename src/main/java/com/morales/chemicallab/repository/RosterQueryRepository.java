package com.morales.chemicallab.repository;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.security.storage.QueryLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
@Component @RequiredArgsConstructor
public class RosterQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final QueryLimits limits;
    private String filters(Map<String,Object> p,String search,String status,String grade,String section,boolean student) {
        String where="";search=QueryLimits.search(search);
        if(search!=null && !search.isBlank()){where+=" and (position(lower(:search) in lower(x.names||' '||x.last_names))>0 or "+(student?"position(lower(:search) in lower(x.student_code))>0":"position(lower(:search) in lower(u.username))>0 or position(lower(:search) in lower(u.email))>0")+")";p.put("search",search);}
        if(status!=null && !status.equals("all")){
            if(!List.of("active","inactive").contains(status))throw new IllegalArgumentException("Estado inválido.");
            where+=" and u.active=:active";p.put("active",status.equals("active"));
        }
        if(grade!=null && !grade.isBlank()){where+=" and x.grade=:grade";p.put("grade",grade);}
        if(section!=null && !section.isBlank()){where+=" and x.section=:section";p.put("section",section);}
        return where;
    }
    public QueryPage<TeacherResponse> teachers(int page,int size,String search,String status) {
        limits.page(page,size);var p=new HashMap<String,Object>();
        String from=" from teacher_profiles x join user_accounts u on u.id=x.user_id where true"+filters(p,search,status,null,null,false);
        long total=jdbc.queryForObject("select count(*)"+from,p,Long.class);p.put("limit",size);p.put("offset",(long)page*size);
        var rows=jdbc.query("select x.id,x.names,x.last_names,u.id user_id,u.username,u.email,u.active,u.temporary_password"+from+" order by lower(x.names||' '||x.last_names),x.id limit :limit offset :offset",p,
            (r,n)->new TeacherResponse(r.getLong("id"),r.getLong("user_id"),r.getString("username"),r.getString("email"),r.getString("names"),r.getString("last_names"),r.getBoolean("active"),r.getBoolean("temporary_password")));
        return QueryPage.of(rows,page,size,total);
    }
    public QueryPage<StudentResponse> students(long teacherId,int page,int size,String search,String grade,String section,String status) {
        limits.page(page,size);var p=new HashMap<String,Object>();p.put("teacher",teacherId);
        String from=" from student_profiles x join user_accounts u on u.id=x.user_id where x.teacher_id=:teacher"+filters(p,search,status,grade,section,true);
        long total=jdbc.queryForObject("select count(*)"+from,p,Long.class);p.put("limit",size);p.put("offset",(long)page*size);
        var rows=jdbc.query("select x.id,x.names,x.last_names,x.student_code,x.grade,x.section,x.teacher_id,u.id user_id,u.username,u.active,u.temporary_password"+from+" order by lower(x.names||' '||x.last_names),x.id limit :limit offset :offset",p,
            (r,n)->new StudentResponse(r.getLong("id"),r.getLong("user_id"),r.getString("student_code"),r.getString("username"),r.getString("names"),r.getString("last_names"),r.getString("grade"),r.getString("section"),r.getBoolean("active"),r.getBoolean("temporary_password"),r.getLong("teacher_id")));
        return QueryPage.of(rows,page,size,total);
    }
    public RosterSummary summary(long teacherId) {
        var p=Map.of("teacher",teacherId);
        var groups=jdbc.query("select distinct grade,section from student_profiles where teacher_id=:teacher order by grade,section limit 131",p,(r,n)->new RosterSummary.Group(r.getString(1),r.getString(2)));
        if(groups.size()>130)throw new com.morales.chemicallab.security.limits.InputRejectedException(409,"HISTORICAL_GROUPS_INVALID","Los grupos históricos necesitan revisión. No se han modificado.");
        return jdbc.queryForObject("select count(*) total,count(*) filter(where u.active) active from student_profiles x join user_accounts u on u.id=x.user_id where x.teacher_id=:teacher",p,(r,n)->new RosterSummary(r.getLong(1),r.getLong(2),groups));
    }
}

package com.morales.chemicallab.repository;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.storage.QueryLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@Component @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
public class SupervisionQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final QueryLimits limits;
    public SupervisionSummaryResponse summary() {
        return jdbc.queryForObject("""
            select (select count(*) from concept_contents) concepts,
            (select count(*) from concept_contents where status='PUBLISHED') published_concepts,
            (select count(*) from evaluations) evaluations,(select count(*) from evaluations where status='PUBLISHED') published_evaluations,
            (select count(*) from (select created_by_teacher_id from concept_contents union select created_by_teacher_id from evaluations) x) teachers,
            (select count(*) from (select grade,section from concept_assignments where active union select grade,section from evaluation_assignments where active) x) sections,
            (select count(*) from evaluation_attempts where status in ('SUBMITTED','GRADED')) submitted
            """,Map.of(),(r,n)->new SupervisionSummaryResponse(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6),r.getLong(7)));
    }
    private String where(boolean evaluation,Map<String,Object> p,String search,String status,String category,String section,boolean withAttempts) {
        String where=" where true";search=QueryLimits.search(search);
        if(search!=null && !search.isBlank()){where+=" and (position(lower(:search) in lower(r.title))>0 or position(lower(:search) in lower(t.names||' '||t.last_names))>0)";p.put("search",search);}
        if(status!=null && !status.isBlank()){if(evaluation)EvaluationStatus.valueOf(status);else ConceptStatus.valueOf(status);where+=" and r.status=:status";p.put("status",status);}
        if(!evaluation && category!=null && !category.isBlank()){where+=" and r.category=:category";p.put("category",QueryLimits.search(category));}
        if(section!=null && !section.isBlank()) {
            var parts=section.split(" ",-1);if(parts.length!=2)throw new IllegalArgumentException("Grado/sección inválidos.");
            where+=" and exists(select 1 from "+(evaluation?"evaluation_assignments a where a.evaluation_id":"concept_assignments a where a.concept_content_id")+"=r.id and a.active and a.grade=:grade and a.section=:section)";
            p.put("grade",parts[0]);p.put("section",parts[1]);
        }
        if(evaluation && withAttempts)where+=" and exists(select 1 from evaluation_attempts a where a.evaluation_id=r.id and a.status in ('SUBMITTED','GRADED'))";
        return where;
    }
    private Map<Long,List<SupervisionSectionRef>> sections(boolean evaluation,List<Long> ids) {
        var result=new HashMap<Long,List<SupervisionSectionRef>>();if(ids.isEmpty())return result;
        String table=evaluation?"evaluation_assignments":"concept_assignments",field=evaluation?"evaluation_id":"concept_content_id";
        jdbc.query("select * from (select "+field+" resource,grade,section,active,assigned_at,row_number() over(partition by "+field+" order by assigned_at desc nulls last,id desc) pos from "+table+" where "+field+" in (:ids)) x where pos<=130 order by resource,pos",Map.of("ids",ids),(org.springframework.jdbc.core.RowCallbackHandler)r->result.computeIfAbsent(r.getLong("resource"),k->new ArrayList<>()).add(new SupervisionSectionRef(r.getString("grade"),r.getString("section"),r.getBoolean("active"),r.getObject("assigned_at",LocalDateTime.class))));
        return result;
    }
    public QueryPage<SupervisionConceptResponse> concepts(int page,int size,String search,String status,String category,String section) {
        limits.page(page,size);var p=new HashMap<String,Object>();
        String from=" from concept_contents r join teacher_profiles t on t.id=r.created_by_teacher_id"+where(false,p,search,status,category,section,false);
        long total=jdbc.queryForObject("select count(*)"+from,p,Long.class);p.put("limit",size);p.put("offset",(long)page*size);
        var ids=jdbc.queryForList("select r.id"+from+" order by r.created_at desc nulls last,r.id desc limit :limit offset :offset",p,Long.class);
        if(ids.isEmpty())return QueryPage.of(List.of(),page,size,total);
        var groups=sections(false,ids);p.put("ids",ids);
        var rows=jdbc.query("""
            select r.id,r.title,r.category,r.status,t.names||' '||t.last_names teacher,r.created_at,r.updated_at,
            (select count(*) from concept_assignments a where a.concept_content_id=r.id) assignments,
            (select count(*) from concept_materials m where m.concept_content_id=r.id and m.active) materials,
            exists(select 1 from concept_materials m where m.concept_content_id=r.id and m.active and m.type='FILE') attachment
            from concept_contents r join teacher_profiles t on t.id=r.created_by_teacher_id where r.id in (:ids)
            order by r.created_at desc nulls last,r.id desc
            """,p,(r,n)->new SupervisionConceptResponse(r.getLong("id"),r.getString("title"),r.getString("category"),ConceptStatus.valueOf(r.getString("status")),r.getString("teacher"),r.getLong("assignments"),r.getLong("materials"),r.getBoolean("attachment"),groups.getOrDefault(r.getLong("id"),List.of()),r.getObject("created_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class)));
        return QueryPage.of(rows,page,size,total);
    }
    public QueryPage<SupervisionEvaluationResponse> evaluations(int page,int size,String search,String status,String section,boolean withAttempts) {
        limits.page(page,size);var p=new HashMap<String,Object>();
        String from=" from evaluations r join teacher_profiles t on t.id=r.created_by_teacher_id"+where(true,p,search,status,null,section,withAttempts);
        long total=jdbc.queryForObject("select count(*)"+from,p,Long.class);p.put("limit",size);p.put("offset",(long)page*size);
        var ids=jdbc.queryForList("select r.id"+from+" order by r.created_at desc nulls last,r.id desc limit :limit offset :offset",p,Long.class);
        if(ids.isEmpty())return QueryPage.of(List.of(),page,size,total);
        var groups=sections(true,ids);p.put("ids",ids);
        var rows=jdbc.query("""
            select r.id,r.title,r.topic,r.status,t.names||' '||t.last_names teacher,r.created_at,r.updated_at,
            (select count(*) from evaluation_assignments a where a.evaluation_id=r.id) assignments,
            (select count(*) from evaluation_questions q where q.evaluation_id=r.id and q.active) questions,
            (select count(*) from evaluation_attempts a where a.evaluation_id=r.id and a.status in ('SUBMITTED','GRADED')) submitted
            from evaluations r join teacher_profiles t on t.id=r.created_by_teacher_id where r.id in (:ids)
            order by r.created_at desc nulls last,r.id desc
            """,p,(r,n)->new SupervisionEvaluationResponse(r.getLong("id"),r.getString("title"),r.getString("topic"),EvaluationStatus.valueOf(r.getString("status")),r.getString("teacher"),r.getLong("questions"),r.getLong("assignments"),r.getLong("submitted"),groups.getOrDefault(r.getLong("id"),List.of()),r.getObject("created_at",LocalDateTime.class),r.getObject("updated_at",LocalDateTime.class)));
        return QueryPage.of(rows,page,size,total);
    }
    private static final String ASSIGNMENTS="""
        with assignments as (
        select a.id,'CONTENIDO' type,c.id resource,c.title,t.names||' '||t.last_names teacher,a.grade,a.section,a.active,a.assigned_at
        from concept_assignments a join concept_contents c on c.id=a.concept_content_id join teacher_profiles t on t.id=a.teacher_id
        union all
        select a.id,'EVALUACION',e.id,e.title,t.names||' '||t.last_names,a.grade,a.section,a.active,a.assigned_at
        from evaluation_assignments a join evaluations e on e.id=a.evaluation_id join teacher_profiles t on t.id=a.teacher_id)
        """;
    public QueryPage<SupervisionAssignmentResponse> assignments(int page,int size,String search,String type,String grade,String section,String active) {
        limits.page(page,size);var p=new HashMap<String,Object>();String where=" where true";
        search=QueryLimits.search(search);if(search!=null&&!search.isBlank()){where+=" and (position(lower(:search) in lower(title))>0 or position(lower(:search) in lower(teacher))>0)";p.put("search",search);}
        if(type!=null&&!type.isBlank()){if(!List.of("CONTENIDO","EVALUACION").contains(type))throw new IllegalArgumentException("Tipo inválido.");where+=" and type=:type";p.put("type",type);}
        if(grade!=null&&!grade.isBlank()){where+=" and grade=:grade";p.put("grade",grade);}
        if(section!=null&&!section.isBlank()){where+=" and section=:section";p.put("section",section);}
        if(active!=null&&!active.isBlank()){if(!List.of("true","false").contains(active))throw new IllegalArgumentException("Estado inválido.");where+=" and active=:active";p.put("active",Boolean.parseBoolean(active));}
        long total=jdbc.queryForObject(ASSIGNMENTS+"select count(*) from assignments"+where,p,Long.class);p.put("limit",size);p.put("offset",(long)page*size);
        var rows=jdbc.query(ASSIGNMENTS+"select * from assignments"+where+" order by assigned_at desc nulls last,type,id desc limit :limit offset :offset",p,(r,n)->new SupervisionAssignmentResponse(r.getString("type"),r.getLong("resource"),r.getString("title"),r.getString("teacher"),r.getString("grade"),r.getString("section"),r.getBoolean("active"),r.getObject("assigned_at",LocalDateTime.class)));
        return QueryPage.of(rows,page,size,total);
    }
    public record Options(List<String> categories,List<RosterSummary.Group> groups,List<RosterSummary.Group> activeConceptGroups,List<RosterSummary.Group> activeEvaluationGroups){}
    public Options options() {
        var categories=jdbc.queryForList("select distinct category from concept_contents order by category limit 101",Map.of(),String.class);
        var groups=groups("select grade,section from concept_assignments union select grade,section from evaluation_assignments");
        if(categories.size()>100)throw new IllegalArgumentException("Demasiadas categorías históricas; requieren revisión.");
        return new Options(categories,groups,groups("select grade,section from concept_assignments where active"),groups("select grade,section from evaluation_assignments where active"));
    }
    private List<RosterSummary.Group> groups(String from) {
        var groups=jdbc.query("select distinct grade,section from ("+from+") x order by grade,section limit 131",Map.of(),(r,n)->new RosterSummary.Group(r.getString(1),r.getString(2)));
        if(groups.size()>130)throw new IllegalArgumentException("Demasiados grupos históricos; requieren revisión.");return groups;
    }
}

package com.morales.chemicallab.service;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.AcademicRevision;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.security.files.FileValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** P03 internal maintainer. Only disposable fixtures; no public caller or production adoption.
 * Source locks, SQL guards, version history, copies, quotas and mandatory T08 share one commit.
 * EDIT_DRAFT, PUBLISH and ASSIGN remain independent capabilities to authorize in T12.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AcademicRevisionMaintenanceService {
    public enum Kind { CONCEPT, EVALUATION }
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;
    private final AcademicRevisionRepository revisions;
    private final FileValidationService files;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(20971522)
            .maxNameLength(100).maxNumberLength(32).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    /** Creates a genuinely new typed academic identity in the same transaction as its F09 root. */
    public long createNew(Kind kind,long institution,long author,long membership,String initialPayload,UUID operation) {
        lock();String request=request(kind,institution,author,membership,tree(initialPayload));
        Long prior=prior(operation,"CREATE_NEW",request);if(prior!=null)return prior;
        validate(kind,tree(initialPayload),false);
        long id=jdbc.queryForObject("""
            insert into t11b8_creations(operation_id,kind,institution_id,author_account_id,author_membership_id,payload)
              values(?,?,?,?,?,?::jsonb) returning resource_id
            """,Long.class,operation,kind.name(),institution,author,membership,initialPayload);
        done(operation,"CREATE_NEW",request,id,id,"AcademicResource");return id;
    }

    /** Attaches the existing B3 identity/root. Never accepts an origin override. */
    public long attachLegacy(Kind kind,long sourceId,UUID operation) {
        lock();String request=request(kind,sourceId);Long prior=prior(operation,"ATTACH_LEGACY",request);if(prior!=null)return prior;
        String table=kind==Kind.CONCEPT?"t11b3_concept_resources":"t11b3_evaluation_resources";
        String key=kind==Kind.CONCEPT?"concept_id":"evaluation_id";
        long id=jdbc.queryForObject("insert into t11b8_resources("+key+",institution_id,author_account_id,author_membership_id,creation,legacy_group_id,max_attempts,operation_id) "
            +"select r."+key+",r.institution_id,r.author_account_id,r.author_membership_id,'LEGACY',"
            +(kind==Kind.CONCEPT?"null,null":"g.id,g.max_attempts")+",? from "+table+" r "
            +(kind==Kind.CONCEPT?"":"join t11b3_opportunity_groups g on g.evaluation_id=r.evaluation_id ")+"where r."+key+"=? returning id",Long.class,operation,sourceId);
        done(operation,"ATTACH_LEGACY",request,id,id,"AcademicResource");return id;
    }

    public AcademicRevision draftFromLegacy(long resource,long baseRevision,UUID operation) {
        lock();String request=request(resource,baseRevision);Long prior=prior(operation,"DRAFT_LEGACY",request);if(prior!=null)return revisions.revision(prior);
        Kind kind=kind(resource);String table=kind==Kind.CONCEPT?"t11b3_concept_revisions":"t11b3_evaluation_revisions";
        String payload=jdbc.queryForObject("select payload::text from "+table+" where id=?",String.class,baseRevision);
        long id=insertDraft(resource,null,kind==Kind.CONCEPT?baseRevision:null,kind==Kind.EVALUATION?baseRevision:null,payload,operation);
        done(operation,"DRAFT_LEGACY",request,resource,id,"ResourceRevision");return revisions.revision(id);
    }

    /** Academic successor and technical copy both retain the exact same root. */
    public AcademicRevision nextDraft(long publishedRevision,UUID operation) {
        lock();String request=request(publishedRevision);Long prior=prior(operation,"NEXT_DRAFT",request);if(prior!=null)return revisions.revision(prior);
        var base=revisions.revision(publishedRevision);
        long id=insertDraft(base.resourceId(),base.id(),null,null,base.payload(),operation);
        done(operation,"NEXT_DRAFT",request,base.resourceId(),id,"ResourceRevision");return revisions.revision(id);
    }

    public AcademicRevision edit(long id,long expectedVersion,String payload,UUID operation) {
        lock();String request=request(id,expectedVersion,tree(payload));Long prior=prior(operation,"EDIT_DRAFT",request);if(prior!=null)return revisions.revision(prior);
        var r=revisions.revision(id);payload=preserveProvenance(tree(payload),tree(r.payload())).toString();validate(kind(r.resourceId()),tree(payload),false);
        changed(jdbc.update("update t11b8_revisions set payload=?::jsonb,payload_checksum=md5(?::jsonb::text),version=version+1 where id=? and version=?",payload,payload,id,expectedVersion));
        done(operation,"EDIT_DRAFT",request,r.resourceId(),id,"ResourceRevision");return revisions.revision(id);
    }

    public AcademicRevision publish(long id,long expectedVersion,UUID operation) {
        lock();String request=request(id,expectedVersion);Long prior=prior(operation,"PUBLISH",request);if(prior!=null)return revisions.revision(prior);
        var r=revisions.revision(id);validate(kind(r.resourceId()),tree(r.payload()),true);
        changed(jdbc.update("update t11b8_revisions set status='PUBLISHED',version=version+1 where id=? and version=?",id,expectedVersion));
        done(operation,"PUBLISH",request,r.resourceId(),id,"ResourceRevision");return revisions.revision(id);
    }

    public long assign(long revision,long institution,long clazz,Instant starts,Instant due,UUID operation) {
        lock();String request=request(revision,institution,clazz,starts,due);Long prior=prior(operation,"ASSIGN",request);if(prior!=null)return prior;
        var r=revisions.revision(revision);
        long id=jdbc.queryForObject("""
            insert into t11b8_deliveries(resource_id,institution_id,revision_id,class_id,starts_at,due_at,operation_id)
            values(?,?,?,?,?,?,?) returning id
            """,Long.class,r.resourceId(),institution,revision,clazz,time(starts),time(due),operation);
        done(operation,"ASSIGN",request,r.resourceId(),id,"AcademicDelivery");return id;
    }

    /** Withdrawal/readmission update one identity; events retain every episode. */
    public long recipient(long delivery,long account,long enrollment,long expectedVersion,boolean admitted,UUID operation) {
        lock();String request=request(delivery,account,enrollment,expectedVersion,admitted);Long prior=prior(operation,"RECIPIENT",request);if(prior!=null)return prior;
        var d=jdbc.queryForMap("select * from t11b8_deliveries where id=?",delivery);long resource=((Number)d.get("resource_id")).longValue();
        Long id=jdbc.query("select id from t11b8_recipients where delivery_id=? and account_id=?",rs->rs.next()?rs.getLong(1):null,delivery,account);
        if(id==null) {
            if(expectedVersion!=0||!admitted)throw new IllegalStateException("Stale recipient version");
            id=jdbc.queryForObject("insert into t11b8_recipients(delivery_id,resource_id,institution_id,account_id,enrollment_id) values(?,?,?,?,?) returning id",Long.class,delivery,resource,d.get("institution_id"),account,enrollment);
        } else changed(jdbc.update("update t11b8_recipients set status=?,enrollment_id=?,version=version+1 where id=? and version=?",admitted?"ADMITTED":"WITHDRAWN",enrollment,id,expectedVersion));
        if(admitted)jdbc.update("""
            insert into t11b8_budgets(resource_id,account_id) select id,? from t11b8_resources
             where id=? and creation='EXPLICIT_NEW' and evaluation_id is not null on conflict do nothing
            """,account,resource);
        done(operation,"RECIPIENT",request,resource,id,"DeliveryRecipient");return id;
    }

    public void closeDelivery(long id,long expectedVersion,UUID operation) {
        lock();String request=request(id,expectedVersion);if(prior(operation,"CLOSE_DELIVERY",request)!=null)return;
        long res=jdbc.queryForObject("select resource_id from t11b8_deliveries where id=?",Long.class,id);
        changed(jdbc.update("update t11b8_deliveries set status='CLOSED',version=version+1 where id=? and version=?",id,expectedVersion));
        done(operation,"CLOSE_DELIVERY",request,res,id,"AcademicDelivery");
    }

    public void resourceState(long id,long expectedVersion,String availability,boolean close,boolean expose,UUID operation) {
        lock();String request=request(id,expectedVersion,availability,close,expose);if(prior(operation,"RESOURCE_STATE",request)!=null)return;
        changed(jdbc.update("update t11b8_resources set availability=?,opportunities_closed=?,correction_exposed=?,version=version+1 where id=? and version=?",availability,close,expose,id,expectedVersion));
        done(operation,"RESOURCE_STATE",request,id,id,"AcademicResource");
    }

    /** Simulates F09 consumption only. Cannot consume a LEGACY root or call production startAttempt. */
    public long reserveFixtureAttempt(long recipient,UUID operation) {
        lock();String request=request(recipient);Long prior=prior(operation,"FIXTURE_START",request);if(prior!=null)return prior;
        long id=jdbc.queryForObject("""
            insert into t11b8_fixture_attempts(resource_id,institution_id,account_id,recipient_id,revision_id,
              attempt_number,configuration,question_order,started_at,operation_id)
            select rec.resource_id,rec.institution_id,rec.account_id,rec.id,d.revision_id,
              (select count(*)+1 from t11b8_fixture_attempts a where a.resource_id=rec.resource_id and a.account_id=rec.account_id),
              rev.payload->'resource','[]'::jsonb,statement_timestamp(),?
             from t11b8_recipients rec join t11b8_deliveries d on d.id=rec.delivery_id
              join t11b8_revisions rev on rev.id=d.revision_id where rec.id=? returning id
            """,Long.class,operation,recipient);
        long res=jdbc.queryForObject("select resource_id from t11b8_fixture_attempts where id=?",Long.class,id);
        done(operation,"FIXTURE_START",request,res,id,"AcademicAttemptFixture");return id;
    }

    public void finishFixtureAttempt(long id,long expectedVersion,String answers,int grade,UUID operation) {
        lock();String request=request(id,expectedVersion,tree(answers),grade);if(prior(operation,"FIXTURE_FINAL",request)!=null)return;
        long res=jdbc.queryForObject("select resource_id from t11b8_fixture_attempts where id=?",Long.class,id);
        changed(jdbc.update("update t11b8_fixture_attempts set status='FINAL',version=version+1,answers=?::jsonb,grade=?,grade_closed=true where id=? and version=?",answers,grade,id,expectedVersion));
        done(operation,"FIXTURE_FINAL",request,res,id,"AcademicAttemptFixture");
    }

    private long insertDraft(long resource,Long parent,Long baseConcept,Long baseEvaluation,String payload,UUID operation) {
        return jdbc.queryForObject("""
            insert into t11b8_revisions(resource_id,institution_id,parent_id,revision_number,payload,payload_checksum,
              base_concept_revision_id,base_evaluation_revision_id,operation_id)
            select r.id,r.institution_id,?,(select coalesce(max(revision_number),0)+1 from t11b8_revisions where resource_id=r.id),
              ?::jsonb,md5(?::jsonb::text),?,?,? from t11b8_resources r where r.id=? returning id
            """,Long.class,parent,payload,payload,baseConcept,baseEvaluation,operation,resource);
    }
    private Kind kind(long resource) {return jdbc.queryForObject("select case when evaluation_id is null then 'CONCEPT' else 'EVALUATION' end from t11b8_resources where id=?",(rs,n)->Kind.valueOf(rs.getString(1)),resource);}
    private void lock() {
        if(SecurityContextHolder.getContext().getAuthentication()!=null)throw new IllegalStateException("Internal fixture maintainer only; effective authorization belongs to T12");
        jdbc.execute("select t11b8_lock()");
    }
    private Long prior(UUID operation,String action,String request) {
        Objects.requireNonNull(operation,"Operation UUID required");
        return jdbc.query("select action=? and request=?::jsonb identical,result_id from t11b8_operations where operation_id=?",rs->{
            if(!rs.next())return null;if(!rs.getBoolean(1))throw new IllegalArgumentException("Operation UUID reused with another request");return rs.getLong(2);
        },action,request,operation);
    }
    private void done(UUID operation,String action,String request,long resource,long id,String target) {
        jdbc.update("insert into t11b8_operations(operation_id,resource_id,action,request,result_id) values(?,?,?,?::jsonb,?)",operation,resource,action,request,id);
        audit.recordInfo(LogEventType.ADMIN_ACTION,target,id,null,null,null,null);
    }
    private static void changed(int count){if(count!=1)throw new IllegalStateException("Stale version");}
    private static Timestamp time(Instant instant){return instant==null?null:Timestamp.from(instant);}
    private static String request(Object... fields) {
        var list=new ArrayList<Object>();for(Object value:fields)list.add(value instanceof Instant||value instanceof Enum<?>?value.toString():value);
        try{return JSON.writeValueAsString(list);}catch(Exception ex){throw new IllegalArgumentException("Invalid request",ex);}
    }
    private static JsonNode tree(String raw) {
        if(raw==null||raw.length()>33554432||raw.getBytes(StandardCharsets.UTF_8).length>33554432)throw new IllegalArgumentException("Bounded academic payload required");
        try{var p=JSON.readTree(raw);if(p==null||!p.isObject())throw new IllegalArgumentException("Academic object required");return p;}
        catch(java.io.IOException ex){throw new IllegalArgumentException("Invalid academic payload",ex);}
    }
    private static JsonNode preserveProvenance(JsonNode next,JsonNode old) {
        var object=(com.fasterxml.jackson.databind.node.ObjectNode)next;
        if(old.has("author")&&!next.has("author"))object.set("author",old.get("author"));
        if(next.path("resource").isObject()) {
            var cfg=(com.fasterxml.jackson.databind.node.ObjectNode)next.path("resource");
            for(String key:List.of("id","created_by_teacher_id","created_at","updated_at","status","active"))
                if(old.path("resource").has(key)&&!cfg.has(key))cfg.set(key,old.path("resource").get(key));
        }
        return next;
    }
    private void validate(Kind kind,JsonNode payload,boolean publishing) {
        jdbc.queryForObject("select t11b8_payload(?,?::jsonb,?) is null",Boolean.class,kind.name(),payload.toString(),publishing);
        bounded(payload,0,null);
        if(kind==Kind.CONCEPT)for(var m:payload.path("materials")) {
            if(m.path("type").asText().equals("FILE")) {
                byte[] bytes=HexFormat.of().parseHex(m.path("file_data").asText().substring(2));
                // Structural T06 validation in the isolated worker. Preserve original bytes in the revision.
                files.historical(bytes,m.path("content_type").asText(),m.path("original_file_name").asText(),false);
            }
        }
    }
    private static void bounded(JsonNode node,int depth,String key) {
        if(depth>12||node.size()>10000)throw new IllegalArgumentException("Academic collection/depth limit");
        if(node.isObject()) {
            if(node.size()>32)throw new IllegalArgumentException("Academic property limit");
            node.fields().forEachRemaining(e->bounded(e.getValue(),depth+1,e.getKey()));
        } else if(node.isArray())for(var child:node)bounded(child,depth+1,key);
        else if(node.isTextual()) {
            int limit="file_data".equals(key)?20971522:"explanation".equals(key)?20000:5000;
            if(node.asText().length()>limit)throw new IllegalArgumentException("Academic string limit");
        }
    }
}

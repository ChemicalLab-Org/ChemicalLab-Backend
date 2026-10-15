package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.dto.InstitutionalAttemptReview.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.*;
import jakarta.persistence.EntityManager;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;

/** Fixture-only T12-E. Every write shares the academic engine and one commit boundary. */
@Service
public class InstitutionalAttemptReviewService {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final EvaluationDeliveryAccessService access;
    private final EvaluationService legacy;
    private final AccountSessionService sessions;
    private final AuditLogWriter audit;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public InstitutionalAttemptReviewService(JdbcTemplate jdbc,Environment environment,EvaluationDeliveryAccessService access,
            EvaluationService legacy,AccountSessionService sessions,AuditLogWriter audit,EntityManager entities,
            ObjectMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.environment=environment;this.access=access;this.legacy=legacy;this.sessions=sessions;
        this.audit=audit;this.entities=entities;this.json=json;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(30);
    }
    private String enabled() {
        String schema=environment.getProperty("app.evaluations.institutional.fixture-schema","");
        if(!environment.getProperty("app.evaluations.institutional.enabled",Boolean.class,false)
            || !environment.getProperty("app.evaluations.institutional.review-enabled",Boolean.class,false)
            || !Arrays.equals(environment.getActiveProfiles(),new String[]{"test"}) || !schema.matches("t03_[a-f0-9]{32}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operación no habilitada.");
        return schema;
    }
    private Request request(SessionPrincipal actor,Contract contract,Action action,long institution,long clazz,
            long evaluation,long delivery,long revision,long attempt) {
        if(actor==null || actor.temporaryPassword() || contract!=Contract.LEGACY)throw denied();
        // The client selects an attempt. Its academic owner comes from its persisted relation.
        var owners=jdbc.queryForList("select s.user_id from evaluation_attempts a join student_profiles s on s.id=a.student_id where a.id=?",Long.class,attempt);
        if(owners.size()!=1)throw denied();
        return new Request(contract,action,institution,clazz,evaluation,delivery,revision,owners.get(0),attempt);
    }
    public Review read(SessionPrincipal actor,Contract contract,long institution,long clazz,long evaluation,long delivery,long revision,long attempt) {
        String schema=enabled();
        return guarded(()->transaction.execute(status->{
            InstitutionalFixtureUnit.lock(jdbc,schema,false);
            sessions.revalidate(actor);
            Request q=request(actor,contract,Action.REVIEW_ATTEMPT,institution,clazz,evaluation,delivery,revision,attempt);
            Context context=access.authorizeReview(actor,q);
            var answers=jdbc.query("""
                select a.id,q.id question_id,q.question_text,q.points,q.expected_answer,a.answer_text,a.reviewed,
                  a.points_awarded,a.teacher_feedback,t.user_id reviewed_by,a.reviewed_at
                from evaluation_questions q left join evaluation_answers a on a.question_id=q.id and a.attempt_id=?
                  left join teacher_profiles t on t.id=a.reviewed_by
                where q.evaluation_id=? and q.active and q.question_type='OPEN_TEXT' order by q.order_index,q.id limit 201
                """,(rs,n)->new Answer((Long)rs.getObject("id"),rs.getLong("question_id"),rs.getString("question_text"),rs.getInt("points"),
                    rs.getString("expected_answer"),rs.getString("answer_text"),rs.getBoolean("reviewed"),(Integer)rs.getObject("points_awarded"),
                    rs.getString("teacher_feedback"),(Long)rs.getObject("reviewed_by"),rs.getObject("reviewed_at",LocalDateTime.class)),attempt,evaluation);
            var adjustments=jdbc.query("""
                select a.*,t.user_id actor from evaluation_attempt_adjustments a join teacher_profiles t on t.id=a.created_by
                where a.attempt_id=? order by a.created_at,a.id limit 201
                """,(rs,n)->new AdjustmentDetail(rs.getLong("id"),rs.getBigDecimal("amount"),rs.getString("type"),rs.getString("reason"),
                    rs.getBoolean("active"),rs.getLong("actor"),rs.getObject("created_at",LocalDateTime.class)),attempt);
            if(answers.size()>200 || adjustments.size()>200)throw new IllegalStateException();
            Review result=jdbc.queryForObject("""
                select a.*,t.user_id closing_actor from evaluation_attempts a left join teacher_profiles t on t.id=a.grade_closed_by where a.id=?
                """,(rs,n)->new Review(attempt,q.recipientAccountId(),rs.getString("status"),(Integer)rs.getObject("score"),(Integer)rs.getObject("max_score"),
                    rs.getBigDecimal("final_score"),rs.getString("overall_feedback"),rs.getBoolean("grade_closed"),(Long)rs.getObject("closing_actor"),
                    rs.getObject("grade_closed_at",LocalDateTime.class),rs.getObject("submitted_at",LocalDateTime.class),rs.getObject("graded_at",LocalDateTime.class),
                    answers,adjustments,context.observedAt()),attempt);
            access.authorizeReview(actor,q);sessions.revalidate(actor);return result;
        }));
    }
    public Receipt write(SessionPrincipal actor,Contract contract,long institution,long clazz,long evaluation,long delivery,long revision,
            long attempt,String operation,Long child,UUID id,Integer score,BigDecimal amount,String text) {
        String schema=enabled();
        return guarded(()->transaction.execute(status->{
            InstitutionalFixtureUnit.lock(jdbc,schema,true);
            String ctl=InstitutionalFixtureUnit.reviewControl(schema);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select to_regprocedure(?::text) is not null",Boolean.class,ctl+".fresh(bigint)")))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operación no habilitada.");
            sessions.revalidate(actor);
            Request q=request(actor,contract,Action.REVIEW_ATTEMPT,institution,clazz,evaluation,delivery,revision,attempt);
            access.authorizeReview(actor,q);
            if(id==null || !Set.of("GRADE","COMPLETE","ADD","REMOVE","FEEDBACK","CLOSE").contains(operation))throw denied();
            Map<String,Object> proof=new TreeMap<>();
            proof.put("operation",operation);proof.put("actor",actor.userId());proof.put("recipient",q.recipientAccountId());
            proof.put("institution",institution);proof.put("class",clazz);proof.put("evaluation",evaluation);
            proof.put("delivery",delivery);proof.put("revision",revision);proof.put("attempt",attempt);proof.put("child",child);
            proof.put("score",score);proof.put("amount",amount);proof.put("text",text);
            String encoded=encode(proof);
            var old=jdbc.queryForList("select request::text,result::text from "+ctl+".saves where operation_id=?",id);
            if(!old.isEmpty()) {
                if(!tree((String)old.get(0).get("request")).equals(tree(encoded)))throw conflict();
                // The current REVIEW action includes closed grades. GRADE would reject
                // the very state the successful CLOSE changed. New writes still use GRADE.
                Receipt receipt=decode((String)old.get(0).get("result"));sessions.revalidate(actor);return receipt;
            }
            for(String predecessor:List.of(InstitutionalFixtureUnit.control(schema),InstitutionalFixtureUnit.lifecycleControl(schema)))
                if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+predecessor+".saves where operation_id=?)",Boolean.class,id)))throw conflict();
            access.authorizeReview(actor,new Request(contract,Action.GRADE_ATTEMPT,institution,clazz,evaluation,delivery,revision,q.recipientAccountId(),attempt));
            if(operation.equals("GRADE") && (child==null || score==null || score<0 || text!=null&&text.length()>2000))throw denied();
            if(operation.equals("REMOVE") && child==null)throw denied();
            if(operation.equals("ADD") && (amount==null || amount.abs().compareTo(new BigDecimal("20"))>0 || text==null || text.isBlank() || text.length()>500))throw denied();
            if(operation.equals("FEEDBACK") && text!=null && text.length()>1500)throw denied();
            if(operation.equals("ADD") && jdbc.queryForObject("select count(*) from evaluation_attempt_adjustments where attempt_id=?",Long.class,attempt)>=200)throw denied();
            jdbc.queryForObject("select "+ctl+".begin_save(?,?::jsonb)",Object.class,id,encoded);
            String previous=jdbc.queryForObject("select status from evaluation_attempts where id=?",String.class,attempt);
            Long changed=legacy.reviewInstitutionalAttempt(actor.userId(),attempt,operation,child,score,amount,text);
            entities.flush();
            Receipt result=jdbc.queryForObject("select status,grade_closed from evaluation_attempts where id=?",(rs,n)->new Receipt(id,attempt,changed,rs.getString(1),rs.getBoolean(2)),attempt);
            LogEventType type=switch(operation){case "GRADE"->LogEventType.EVALUATION_ANSWER_REVIEWED;case "COMPLETE"->LogEventType.EVALUATION_REVIEW_COMPLETED;
                case "ADD"->LogEventType.EVALUATION_ADJUSTMENT_ADDED;case "REMOVE"->LogEventType.EVALUATION_ADJUSTMENT_REMOVED;
                case "FEEDBACK"->LogEventType.EVALUATION_FEEDBACK_UPDATED;default->LogEventType.EVALUATION_GRADE_CLOSED;};
            SystemLog event=event(actor,type,operation,evaluation,id);audit.persist(event);
            if(operation.equals("GRADE") && !previous.equals("GRADED") && result.status().equals("GRADED"))
                audit.persist(event(actor,LogEventType.EVALUATION_REVIEW_COMPLETED,"REVIEW_COMPLETED",evaluation,id));
            jdbc.execute("set local synchronous_commit=on");
            jdbc.queryForObject("select "+ctl+".finish_operation(?,?,?::jsonb,?)",Object.class,id,attempt,encode(result),event.getId());
            access.authorizeReview(actor,q);sessions.revalidate(actor);return result;
        }));
    }
    private SystemLog event(SessionPrincipal actor,LogEventType type,String action,long evaluation,UUID id) {
        return SystemLog.builder().eventType(type).category(LogCategory.EVALUATION).severity(LogSeverity.INFO)
            .actorUserId(actor.userId()).actorRole(actor.role()).targetType("ContextResolution").targetId(evaluation)
            .action(action+"_INSTITUTIONAL_REVIEW").description(action+"_INSTITUTIONAL_REVIEW").outcome("SUCCESS").correlationId(id).build();
    }
    private <T>T guarded(Supplier<T> work) {
        try{return work.get();}
        catch(AuthenticationException ex){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no vigente.");}
        catch(ResponseStatusException ex){throw ex;}
        catch(IllegalArgumentException | jakarta.persistence.EntityNotFoundException | NoSuchElementException ex){throw denied();}
        catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Operación no disponible.");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException();}}
    private com.fasterxml.jackson.databind.JsonNode tree(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException();}}
    private Receipt decode(String value){try{return json.readValue(value,Receipt.class);}catch(Exception ex){throw new IllegalStateException();}}
    private ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Operación no autorizada.");}
    private ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Operación incompatible.");}
}

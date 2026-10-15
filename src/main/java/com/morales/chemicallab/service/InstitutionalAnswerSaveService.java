package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.dto.InstitutionalAnswerSave.*;
import com.morales.chemicallab.dto.SubmitEvaluationAnswerRequest;
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
import java.util.*;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;

/** One fixture-only LEGACY mutation. No start, submission, grade or NEW engine. */
@Service
public class InstitutionalAnswerSaveService {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final EvaluationDeliveryAccessService access;
    private final EvaluationService legacy;
    private final AccountSessionService sessions;
    private final AuditLogWriter audit;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public InstitutionalAnswerSaveService(JdbcTemplate jdbc,Environment environment,EvaluationDeliveryAccessService access,
            EvaluationService legacy,AccountSessionService sessions,AuditLogWriter audit,EntityManager entities,
            ObjectMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.environment=environment;this.access=access;this.legacy=legacy;this.sessions=sessions;
        this.audit=audit;this.entities=entities;this.json=json;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }
    public Saved save(SessionPrincipal principal,Contract contract,long institution,long clazz,long evaluation,
                      long delivery,long revision,long attempt,Command command) {
        String schema=environment.getProperty("app.evaluations.institutional.fixture-schema","");
        if(!environment.getProperty("app.evaluations.institutional.enabled",Boolean.class,false)
           || !environment.getProperty("app.evaluations.institutional.answer-save-enabled",Boolean.class,false)
           || !Arrays.equals(environment.getActiveProfiles(),new String[]{"test"}) || !schema.matches("t03_[a-f0-9]{32}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Guardado no habilitado.");
        try {
            if(principal==null || principal.temporaryPassword() || contract!=Contract.LEGACY || command==null
                || command.operationId()==null || command.questionId()==null || command.questionId()<=0)
                throw denied();
            Request request=new Request(Contract.LEGACY,Action.SAVE_OWN_ANSWER,institution,clazz,evaluation,delivery,revision,principal.userId(),attempt);
            return transaction.execute(status->{
                InstitutionalFixtureUnit.lock(jdbc,schema,true);
                String ctl=InstitutionalFixtureUnit.control(schema);
                sessions.revalidate(principal);
                access.authorizeSave(principal,request);
                legacy.requireInstitutionalWindow(attempt);
                // Validate both the selected row and its B10 provenance inside the same lock
                // interval, even where the old writer ignores an irrelevant body field.
                requireQuestion(evaluation,command.questionId());
                if(command.selectedOptionId()!=null) requireOption(command.questionId(),command.selectedOptionId());
                String type=jdbc.queryForObject("select question_type from evaluation_questions where id=?",String.class,command.questionId());
                Long option="OPEN_TEXT".equals(type)?null:command.selectedOptionId();
                String text="OPEN_TEXT".equals(type)?trim(command.answerText()):null;
                Map<String,Object> proof=new TreeMap<>();
                proof.put("actor",principal.userId());proof.put("institution",institution);proof.put("class",clazz);
                proof.put("evaluation",evaluation);proof.put("delivery",delivery);proof.put("revision",revision);
                proof.put("attempt",attempt);proof.put("question",command.questionId());
                // Bind the original input as well as its canonical academic content.
                proof.put("inputOption",command.selectedOptionId());proof.put("inputText",command.answerText());
                proof.put("option",option);proof.put("text",text);
                String encoded=encode(proof);
                String prior=jdbc.queryForObject("select "+ctl+".begin_save(?,?::jsonb)::text",String.class,command.operationId(),encoded);
                if(prior!=null) {
                    sessions.revalidate(principal);
                    legacy.requireInstitutionalWindow(attempt);
                    return decode(prior);
                }
                legacy.saveInstitutionalAnswer(principal.userId(),attempt,new SubmitEvaluationAnswerRequest(command.questionId(),option,text));
                entities.flush();
                long answer=jdbc.queryForObject("select id from evaluation_answers where attempt_id=? and question_id=?",Long.class,attempt,command.questionId());
                Saved result=jdbc.queryForObject("select current_question_index,clock_timestamp() at from evaluation_attempts where id=?",
                    (rs,n)->new Saved(command.operationId(),attempt,command.questionId(),option,text,rs.getInt(1),rs.getTimestamp(2).toInstant()),attempt);
                SystemLog event=SystemLog.builder().eventType(LogEventType.EVALUATION_UPDATED).category(LogCategory.EVALUATION)
                    .severity(LogSeverity.INFO).actorUserId(principal.userId()).actorRole(principal.role())
                    .targetType("ContextResolution").targetId(evaluation).action("SAVE_INSTITUTIONAL_ANSWER")
                    .description("SAVE_INSTITUTIONAL_ANSWER").outcome("SUCCESS").correlationId(command.operationId()).build();
                audit.persist(event);
                jdbc.execute("set local synchronous_commit=on");
                jdbc.queryForObject("select "+ctl+".finish_save(?,?,?::jsonb,?)",Object.class,command.operationId(),answer,encode(result),event.getId());
                // No cached ALLOW survives the mutation. Time can expire while manifests
                // are scanned, so recheck relations, session and the exclusive deadline last.
                access.authorizeSave(principal,request);
                sessions.revalidate(principal);
                legacy.requireInstitutionalWindow(attempt);
                return result;
            });
        } catch(AuthenticationException failure) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no vigente.");
        } catch(ResponseStatusException failure) {
            throw failure;
        } catch(IllegalArgumentException | jakarta.persistence.EntityNotFoundException | NoSuchElementException failure) {
            throw denied();
        } catch(RuntimeException failure) {
            // Never serialize SQL, source rows, entity DTOs or restricted academic text.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Guardado no disponible.");
        }
    }
    private void requireQuestion(long evaluation,long question) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from evaluation_questions where id=? and evaluation_id=? and active)",Boolean.class,question,evaluation)))throw denied();
    }
    private void requireOption(long question,long option) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from evaluation_options where id=? and question_id=? and active)",Boolean.class,option,question)))throw denied();
    }
    private String trim(String value){return value==null || value.trim().isEmpty()?null:value.trim();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException();}}
    private Saved decode(String value){try{return json.readValue(value,Saved.class);}catch(Exception ex){throw new IllegalStateException();}}
    private ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Guardado no autorizado.");}
}

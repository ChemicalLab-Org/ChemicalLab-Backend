package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.dto.InstitutionalAttemptLifecycle.*;
import com.morales.chemicallab.dto.SubmitEvaluationAnswerRequest;
import com.morales.chemicallab.dto.SubmitEvaluationAttemptRequest;
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

/** T12-D fixture lifecycle. Business, provenance, audit and quota use one commit. */
@Service
public class InstitutionalAttemptLifecycleService {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final EvaluationDeliveryAccessService access;
    private final EvaluationService legacy;
    private final AccountSessionService sessions;
    private final AuditLogWriter audit;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public InstitutionalAttemptLifecycleService(JdbcTemplate jdbc,Environment environment,EvaluationDeliveryAccessService access,
            EvaluationService legacy,AccountSessionService sessions,AuditLogWriter audit,EntityManager entities,
            ObjectMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.environment=environment;this.access=access;this.legacy=legacy;this.sessions=sessions;
        this.audit=audit;this.entities=entities;this.json=json;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }
    public Receipt start(SessionPrincipal principal,Contract contract,long institution,long clazz,long evaluation,
                         long delivery,long revision,Start command) {
        return execute(principal,contract,institution,clazz,evaluation,delivery,revision,null,
                command==null?null:command.operationId(),null);
    }
    public Receipt submit(SessionPrincipal principal,Contract contract,long institution,long clazz,long evaluation,
                          long delivery,long revision,long attempt,Submit command) {
        return execute(principal,contract,institution,clazz,evaluation,delivery,revision,attempt,
                command==null?null:command.operationId(),command==null?null:command.answers());
    }
    private Receipt execute(SessionPrincipal principal,Contract contract,long institution,long clazz,long evaluation,
                            long delivery,long revision,Long attempt,UUID operation,List<Answer> answers) {
        String schema=environment.getProperty("app.evaluations.institutional.fixture-schema","");
        if(!environment.getProperty("app.evaluations.institutional.enabled",Boolean.class,false)
            || !environment.getProperty("app.evaluations.institutional.attempt-lifecycle-enabled",Boolean.class,false)
            || !Arrays.equals(environment.getActiveProfiles(),new String[]{"test"}) || !schema.matches("t03_[a-f0-9]{32}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operación no habilitada.");
        try {
            if(principal==null || principal.temporaryPassword() || contract!=Contract.LEGACY || operation==null)throw denied();
            if(answers!=null && (answers.size()>200 || answers.stream().anyMatch(a->a==null || a.questionId()==null
                || a.questionId()<=0 || (a.selectedOptionId()!=null && a.selectedOptionId()<=0)
                || (a.answerText()!=null && a.answerText().length()>3000))))throw denied();
            boolean starting=attempt==null;
            Request request=new Request(contract,starting?Action.START_OWN_ATTEMPT:Action.SUBMIT_OWN_ATTEMPT,
                institution,clazz,evaluation,delivery,revision,principal.userId(),attempt);
            Map<String,Object> proof=new TreeMap<>();
            proof.put("operation",starting?"START":"SUBMIT");proof.put("actor",principal.userId());
            proof.put("institution",institution);proof.put("class",clazz);proof.put("evaluation",evaluation);
            proof.put("delivery",delivery);proof.put("revision",revision);proof.put("attempt",attempt);
            proof.put("inputAnswers",answers);
            String input=encode(proof);
            return transaction.execute(status->{
                InstitutionalFixtureUnit.lock(jdbc,schema,true);
                String ctl=InstitutionalFixtureUnit.writeControl(jdbc,schema);
                if(!Boolean.TRUE.equals(jdbc.queryForObject("select to_regprocedure(?::text) is not null",Boolean.class,InstitutionalFixtureUnit.lifecycleControl(schema)+".fresh(bigint)")))
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operación no habilitada.");
                sessions.revalidate(principal);
                access.authorizeLifecycle(principal,request);
                // Resolve uncertain commit only AFTER coordination. Revalidate current access,
                // but do not demand IN_PROGRESS from a receipt that itself closed the attempt.
                var receipts=jdbc.queryForList("select request::text,result::text from "+ctl+".saves where operation_id=?",operation);
                if(receipts.isEmpty() && ctl.startsWith("t12e_"))
                    receipts=jdbc.queryForList("select request::text,result::text from "+InstitutionalFixtureUnit.lifecycleControl(schema)+".saves where operation_id=?",operation);
                if(!receipts.isEmpty()) {
                    var old=receipts.get(0);
                    if(!jsonTree((String)old.get("request")).path("input").equals(jsonTree(input)))
                        throw new ResponseStatusException(HttpStatus.CONFLICT,"Operación incompatible.");
                    Receipt prior=decode((String)old.get("result"));
                    access.authorizeLifecycle(principal,new Request(contract,Action.READ_OWN_ATTEMPT,institution,clazz,
                        evaluation,delivery,revision,principal.userId(),prior.attemptId()));
                    sessions.revalidate(principal);
                    return prior;
                }
                if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+InstitutionalFixtureUnit.control(schema)+".saves where operation_id=?)",Boolean.class,operation)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Operación incompatible.");
                proof.put("input",jsonTree(input));
                boolean accept=!starting && legacy.institutionalAnswersOpen(attempt)
                    && !"ONE_BY_ONE".equals(jdbc.queryForObject("select question_display_mode from evaluations where id=?",String.class,evaluation));
                proof.put("acceptAnswers",accept);
                List<SubmitEvaluationAnswerRequest> normalized=new ArrayList<>();
                List<Map<String,Object>> canonical=new ArrayList<>();
                // Ignored bodies (expired / ONE_BY_ONE) cannot introduce content or new authority.
                if(accept && answers!=null) {
                    Set<Long> seen=new HashSet<>();
                    for(Answer answer:answers) {
                        if(!seen.add(answer.questionId()))throw denied();
                        var types=jdbc.queryForList("select question_type from evaluation_questions where id=? and evaluation_id=? and active",String.class,answer.questionId(),evaluation);
                        if(types.size()!=1)throw denied();
                        if(answer.selectedOptionId()!=null && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from evaluation_options where id=? and question_id=? and active)",Boolean.class,answer.selectedOptionId(),answer.questionId())))throw denied();
                        boolean open="OPEN_TEXT".equals(types.get(0));
                        String text=open && answer.answerText()!=null && !answer.answerText().trim().isEmpty()?answer.answerText().trim():null;
                        Long option=open?null:answer.selectedOptionId();
                        normalized.add(new SubmitEvaluationAnswerRequest(answer.questionId(),option,text));
                        Map<String,Object> item=new TreeMap<>();item.put("question",answer.questionId());item.put("option",option);item.put("text",text);canonical.add(item);
                    }
                }
                proof.put("answers",canonical);
                jdbc.queryForObject("select "+ctl+".begin_save(?,?::jsonb)",Object.class,operation,encode(proof));
                EvaluationAttempt changed;
                if(starting) {
                    long assignment=jdbc.queryForObject("select legacy_assignment_id from t11b3_evaluation_deliveries where id=?",Long.class,delivery);
                    changed=legacy.startInstitutionalAttempt(principal.userId(),assignment);
                } else changed=legacy.submitInstitutionalAttempt(principal.userId(),attempt,new SubmitEvaluationAttemptRequest(normalized));
                entities.flush();
                Receipt result=new Receipt(operation,changed.getId(),changed.getStatus().name(),changed.getAttemptNumber(),changed.getStartedAt(),changed.getSubmittedAt());
                String action=starting?"START_INSTITUTIONAL_ATTEMPT":"SUBMIT_INSTITUTIONAL_ATTEMPT";
                SystemLog event=SystemLog.builder().eventType(starting?LogEventType.EVALUATION_ATTEMPT_STARTED:LogEventType.EVALUATION_ATTEMPT_SUBMITTED)
                    .category(LogCategory.EVALUATION).severity(LogSeverity.INFO).actorUserId(principal.userId()).actorRole(principal.role())
                    .targetType("ContextResolution").targetId(evaluation).action(action).description(action).outcome("SUCCESS").correlationId(operation).build();
                audit.persist(event);
                if(!starting && changed.getStatus()==AttemptStatus.PENDING_MANUAL_REVIEW) {
                    audit.persist(SystemLog.builder().eventType(LogEventType.EVALUATION_ATTEMPT_PENDING_REVIEW)
                        .category(LogCategory.EVALUATION).severity(LogSeverity.INFO).actorUserId(principal.userId()).actorRole(principal.role())
                        .targetType("ContextResolution").targetId(evaluation).action("PENDING_INSTITUTIONAL_REVIEW")
                        .description("PENDING_INSTITUTIONAL_REVIEW").outcome("SUCCESS").correlationId(operation).build());
                }
                jdbc.execute("set local synchronous_commit=on");
                jdbc.queryForObject("select "+ctl+".finish_operation(?,?,?::jsonb,?)",Object.class,operation,changed.getId(),encode(result),event.getId());
                InstitutionalFixtureUnit.checkExecutionQuotas(jdbc,schema);
                access.authorizeLifecycle(principal,request);
                // Also resolves all newly derived children through the public read resolver.
                access.authorizeLifecycle(principal,new Request(contract,Action.READ_OWN_ATTEMPT,institution,clazz,evaluation,delivery,revision,principal.userId(),changed.getId()));
                sessions.revalidate(principal);
                if(accept && !legacy.institutionalAnswersOpen(changed.getId()))throw denied();
                return result;
            });
        } catch(AuthenticationException failure) {throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no vigente.");
        } catch(ResponseStatusException failure) {throw failure;
        } catch(IllegalArgumentException | jakarta.persistence.EntityNotFoundException | NoSuchElementException failure) {throw denied();
        } catch(RuntimeException failure) {throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Operación no disponible.");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException();}}
    private com.fasterxml.jackson.databind.JsonNode jsonTree(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException();}}
    private Receipt decode(String value){try{return json.readValue(value,Receipt.class);}catch(Exception ex){throw new IllegalStateException();}}
    private ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Operación no autorizada.");}
}

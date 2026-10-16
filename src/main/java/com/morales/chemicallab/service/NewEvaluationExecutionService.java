package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.morales.chemicallab.dto.NewEvaluationExecution.*;
import com.morales.chemicallab.dto.InstitutionalEvaluationRead;
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
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;

/** Fixture-only NEW_V1. No legacy academic writer or B8 simulation mutation. */
@Service
public class NewEvaluationExecutionService {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final EvaluationDeliveryAccessService access;
    private final AccountSessionService sessions;
    private final AuditLogWriter audit;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public NewEvaluationExecutionService(JdbcTemplate jdbc,Environment environment,EvaluationDeliveryAccessService access,
        AccountSessionService sessions,AuditLogWriter audit,EntityManager entities,ObjectMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.environment=environment;this.access=access;this.sessions=sessions;this.audit=audit;this.entities=entities;this.json=json;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(30);
    }
    private String enabled() {
        String schema=environment.getProperty("app.evaluations.institutional.fixture-schema","");
        if(!environment.getProperty("app.evaluations.institutional.enabled",Boolean.class,false)
           || !environment.getProperty("app.evaluations.institutional.new-execution-enabled",Boolean.class,false)
           || !Arrays.equals(environment.getActiveProfiles(),new String[]{"test"}) || !schema.matches("t03_[a-f0-9]{32}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operación no habilitada.");
        return schema;
    }
    private String ctl(String schema){return "t12f_"+schema.substring(4);}
    private Instant now(){return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant();}
    private Request request(SessionPrincipal actor,long institution,long clazz,long evaluation,long delivery,long revision,Long attempt,boolean teacher,Action action,String ctl) {
        if(actor==null || actor.temporaryPassword())throw denied();
        long recipient=actor.userId();
        if(teacher) {
            var rows=jdbc.queryForList("select account_id from "+ctl+".attempts where id=?",Long.class,attempt);
            if(rows.size()!=1)throw denied();recipient=rows.get(0);
        }
        return new Request(Contract.NEW,action,institution,clazz,evaluation,delivery,revision,recipient,attempt);
    }
    private <T>T unit(SessionPrincipal actor,boolean writing,java.util.function.Function<String,T> work) {
        String schema=enabled();
        return guarded(()->transaction.execute(status->{
            InstitutionalFixtureUnit.lock(jdbc,schema,writing);
            String ctl=ctl(schema);
            // F academic tables participate in the same order in every process/read.
            jdbc.execute("lock table "+ctl+".attempts,"+ctl+".operations in "+(writing?"share row exclusive":"share")+" mode");
            sessions.revalidate(actor);T result=work.apply(ctl);sessions.revalidate(actor);return result;
        }));
    }
    private Map<String,Object> binding(Request q) {
        var rows=jdbc.queryForList("""
            select d.resource_id,r.id recipient_id,r.enrollment_id,v.payload::text,v.payload_checksum,s.max_attempts,d.due_at,
              root.id root_version,head.resolution::text context
            from t11b8_deliveries d join t11b8_resources s on s.id=d.resource_id
             join t11b8_revisions v on v.id=d.revision_id
             join t11b8_recipients r on r.delivery_id=d.id and r.account_id=?
             join t11b10_versions head on head.source_table='t11b8_recipients' and head.source_pk=jsonb_build_object('id',r.id)
              and not exists(select 1 from t11b10_versions n where n.predecessor_id=head.id)
             join t11b10_versions root on root.source_table='evaluations' and root.source_pk=jsonb_build_object('id',s.evaluation_id)
              and not exists(select 1 from t11b10_versions n where n.predecessor_id=root.id)
            where d.id=? and d.revision_id=? and s.evaluation_id=? and d.class_id=? and d.institution_id=?
            """,q.recipientAccountId(),q.deliveryId(),q.revisionId(),q.evaluationId(),q.classId(),q.institutionId());
        if(rows.size()!=1)throw denied();
        var row=rows.get(0);JsonNode context=tree((String)row.get("context"));
        if(!"INSTITUTION".equals(context.path("scope").asText()) || context.path("institutionId").asLong()!=q.institutionId()
           || context.path("classId").asLong()!=q.classId() || context.path("accountId").asLong()!=q.recipientAccountId()
           || context.path("enrollmentId").asLong()!=number(row,"enrollment_id").longValue())throw denied();
        return row;
    }
    private Map<String,Object> attempt(String ctl,long id){return jdbc.queryForMap("select * from "+ctl+".attempts where id=?",id);}
    private PublishedEvaluationContent content(Map<String,Object> binding){return new PublishedEvaluationContent(tree((String)binding.get("payload")));}
    private Scope scope(Request q){return new Scope("NEW_V1",q.institutionId(),q.classId(),q.evaluationId(),q.deliveryId(),q.revisionId());}

    public Object read(SessionPrincipal actor,long institution,long clazz,long evaluation,long delivery,long revision,Long id,String projection,Long recipient) {
        return unit(actor,false,ctl->{
            boolean teacher=projection.equals("review");
            Action action=switch(projection){case "delivery"->Action.READ_DELIVERY;case "review"->Action.REVIEW_ATTEMPT;case "correction"->Action.READ_CORRECTION;default->Action.READ_OWN_ATTEMPT;};
            Request q=request(actor,institution,clazz,evaluation,delivery,revision,id,teacher,action,ctl);
            if(projection.equals("delivery") && recipient!=null)q=new Request(q.contract(),q.action(),institution,clazz,evaluation,delivery,revision,recipient,null);
            Context decision=access.authorizeExecution(actor,q);
            var binding=binding(q);
            if(decision.projection()==Projection.SUPERVISION_METADATA)
                return new InstitutionalEvaluationRead.Metadata(new InstitutionalEvaluationRead.Scope(Contract.NEW,institution,clazz,evaluation,delivery,revision),"SUPERVISION_METADATA",decision.observedAt());
            var content=content(binding);
            if(projection.equals("delivery")) {
                var cfg=content.configuration;
                return new InstitutionalEvaluationRead.Delivery(new InstitutionalEvaluationRead.Scope(Contract.NEW,institution,clazz,evaluation,delivery,revision),
                    cfg.path("title").asText(),nullableText(cfg,"description"),nullableText(cfg,"instructions"),nullableText(cfg,"topic"),content.minutes,
                    cfg.path("allow_chemical_calculator").asBoolean(),cfg.path("allow_periodic_table").asBoolean(),cfg.path("track_tab_exit").asBoolean(),
                    cfg.path("question_display_mode").asText(),content.random,content.questions.stream().map(x->new InstitutionalEvaluationRead.Question(
                        x.id(),x.text(),x.type(),x.points(),x.order(),x.required(),x.options().stream().map(o->new InstitutionalEvaluationRead.Option(o.id(),o.text(),o.order())).toList())).toList(),decision.observedAt());
            }
            var a=attempt(ctl,id);validateFrozen(a,binding,content);
            ObjectNode answers=object(a,"answers");
            if(projection.equals("review")) {
                var open=content.questions.stream().filter(PublishedEvaluationContent.Question::open).map(x->{
                    JsonNode value=answers.path(Long.toString(x.id()));
                    return new TeacherAnswer(x.id(),revision,x.id(),x.text(),x.expected(),x.points(),nullableText(value,"text"),value.path("reviewed").asBoolean(),
                        nullableInt(value,"points"),nullableText(value,"feedback"),nullableLong(value,"reviewer"),instant(value,"reviewedAt"));
                }).toList();
                List<Adjustment> adjustments=new ArrayList<>();
                for(JsonNode value:tree(a.get("adjustments").toString()))adjustments.add(new Adjustment(value.path("id").asLong(),value.path("amount").decimalValue(),value.path("reason").asText(),value.path("active").asBoolean(),value.path("actor").asLong(),instant(value,"at")));
                return new Review(scope(q),id,q.recipientAccountId(),(String)a.get("status"),(Integer)a.get("score"),(Integer)a.get("max_score"),(BigDecimal)a.get("final_score"),
                    (String)a.get("overall_feedback"),(Boolean)a.get("grade_closed"),(Long)a.get("grade_closed_by"),time(a,"grade_closed_at"),time(a,"submitted_at"),time(a,"graded_at"),open,List.copyOf(adjustments));
            }
            if(projection.equals("correction")) {
                List<CorrectedAnswer> corrected=content.questions.stream().map(x->{
                    JsonNode value=answers.path(Long.toString(x.id()));boolean reviewed=value.path("reviewed").asBoolean();
                    return new CorrectedAnswer(revision,x.id(),x.text(),x.type(),nullableLong(value,"option"),nullableText(value,"text"),x.open()?null:value.path("correct").asBoolean(),
                        x.open()&&!reviewed?null:nullableInt(value,"points"),reviewed?nullableText(value,"feedback"):null,
                        x.open()?null:x.options().stream().filter(PublishedEvaluationContent.Option::correct).map(PublishedEvaluationContent.Option::text).findFirst().orElse(null),x.explanation());
                }).toList();
                return new Correction(scope(q),id,(Integer)a.get("score"),(Integer)a.get("max_score"),(BigDecimal)a.get("final_score"),(String)a.get("overall_feedback"),corrected);
            }
            var own=new ArrayList<OwnAnswer>();
            for(var x:content.questions)if(answers.has(Long.toString(x.id()))) {
                var v=answers.get(Long.toString(x.id()));own.add(new OwnAnswer(revision,x.id(),nullableLong(v,"option"),nullableText(v,"text")));
            }
            return new Attempt(scope(q),id,(String)a.get("status"),number(a,"attempt_number").intValue(),time(a,"started_at"),time(a,"deadline"),order(a),number(a,"current_question_index").intValue(),List.copyOf(own));
        });
    }

    public Receipt write(SessionPrincipal actor,long institution,long clazz,long evaluation,long delivery,long revision,Long id,
        String operation,Long child,UUID key,Answer answer,List<Answer> submitted,Integer score,BigDecimal amount,String text) {
        if(key==null)throw denied();
        return unit(actor,true,ctl->{
            boolean teacher=Set.of("GRADE","COMPLETE","ADD","REMOVE","FEEDBACK","CLOSE").contains(operation);
            Request q=request(actor,institution,clazz,evaluation,delivery,revision,id,teacher,id==null?Action.READ_DELIVERY:teacher?Action.REVIEW_ATTEMPT:Action.READ_OWN_ATTEMPT,ctl);
            Map<String,Object> input=new TreeMap<>();input.put("engine","NEW_V1");input.put("operation",operation);input.put("actor",actor.userId());input.put("recipient",q.recipientAccountId());
            input.put("institution",institution);input.put("class",clazz);input.put("evaluation",evaluation);input.put("delivery",delivery);input.put("revision",revision);input.put("attempt",id);
            input.put("child",child);input.put("answer",answer);input.put("answers",submitted);input.put("score",score);input.put("amount",amount);input.put("text",text);
            String encoded=encode(input);
            var previous=jdbc.queryForList("select request::text,result::text from "+ctl+".operations where operation_id=?",key);
            if(!previous.isEmpty()) {
                Receipt receipt=decode((String)previous.get(0).get("result"));
                access.authorizeExecution(actor,new Request(Contract.NEW,teacher?Action.REVIEW_ATTEMPT:Action.READ_OWN_ATTEMPT,institution,clazz,evaluation,delivery,revision,q.recipientAccountId(),receipt.attemptId()));
                if(!tree((String)previous.get(0).get("request")).equals(tree(encoded)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Operación incompatible.");
                return receipt;
            }
            access.authorizeExecution(actor,q);
            var bound=binding(q);var content=content(bound);
            Action mutation=switch(operation){case "START"->Action.START_OWN_ATTEMPT;case "SAVE"->Action.SAVE_OWN_ANSWER;case "SUBMIT"->Action.SUBMIT_OWN_ATTEMPT;default->Action.GRADE_ATTEMPT;};
            access.authorizeExecution(actor,new Request(Contract.NEW,mutation,institution,clazz,evaluation,delivery,revision,q.recipientAccountId(),id));
            if(teacher && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from teacher_profiles where user_id=?)",Boolean.class,actor.userId())))throw denied();
            jdbc.queryForObject("select "+ctl+".begin_operation(?,?::jsonb,?::jsonb)",Object.class,key,encoded,bound.get("context"));
            long target;String previousStatus=id==null?null:(String)attempt(ctl,id).get("status");
            if(operation.equals("START"))target=start(ctl,q,bound,content);
            else {target=id;mutate(ctl,q,attempt(ctl,id),bound,content,operation,child,answer,submitted,score,amount,text,actor.userId());}
            var changed=attempt(ctl,target);
            Long resultChild=child;
            if(operation.equals("ADD"))resultChild=Long.valueOf(tree(changed.get("adjustments").toString()).size());
            if(operation.equals("SAVE"))resultChild=answer.questionId();
            Receipt result=new Receipt(key,target,resultChild,(String)changed.get("status"),number(changed,"attempt_number").intValue(),(Boolean)changed.get("grade_closed"),time(changed,"started_at"),time(changed,"submitted_at"));
            var event=event(actor,type(operation),operation,evaluation,key);audit.persist(event);
            if(operation.equals("SUBMIT") && changed.get("status").equals("PENDING_MANUAL_REVIEW"))audit.persist(event(actor,LogEventType.EVALUATION_ATTEMPT_PENDING_REVIEW,"PENDING_REVIEW",evaluation,key));
            if(operation.equals("GRADE") && !"GRADED".equals(previousStatus) && changed.get("status").equals("GRADED"))audit.persist(event(actor,LogEventType.EVALUATION_REVIEW_COMPLETED,"REVIEW_COMPLETED",evaluation,key));
            entities.flush();jdbc.execute("set local synchronous_commit=on");
            jdbc.queryForObject("select "+ctl+".finish_operation(?,?,?::jsonb,?)",Object.class,key,target,encode(result),event.getId());
            access.authorizeExecution(actor,new Request(Contract.NEW,teacher?Action.REVIEW_ATTEMPT:Action.READ_OWN_ATTEMPT,institution,clazz,evaluation,delivery,revision,q.recipientAccountId(),target));
            // A save that crossed the frozen deadline rolls back the whole receipt and audit.
            if(operation.equals("SAVE") && !answersOpen(changed,now()))throw denied();
            if(operation.equals("SUBMIT") && !content.sequential && submitted!=null && !submitted.isEmpty()
               && time(changed,"deadline")!=null && time(changed,"submitted_at").isBefore(time(changed,"deadline"))
               && !now().isBefore(time(changed,"deadline")))throw denied();
            return result;
        });
    }
    private long start(String ctl,Request q,Map<String,Object> binding,PublishedEvaluationContent content) {
        long used=jdbc.queryForObject("select count(*) from "+ctl+".attempts where resource_id=? and account_id=?",Long.class,binding.get("resource_id"),q.recipientAccountId());
        if(used>=number(binding,"max_attempts").longValue())throw denied();
        var active=jdbc.queryForList("select id,delivery_id,revision_id from "+ctl+".attempts where resource_id=? and account_id=? and status='IN_PROGRESS'",binding.get("resource_id"),q.recipientAccountId());
        if(!active.isEmpty())throw denied(); // No cross-delivery reuse/transfer, including with a new operationId.
        var order=new ArrayList<>(content.questions.stream().map(PublishedEvaluationContent.Question::id).toList());
        if(content.random)Collections.shuffle(order,new java.security.SecureRandom());
        Instant start=now(),deadline=content.minutes==null?null:start.plusSeconds(content.minutes*60L),due=time(binding,"due_at");
        if(due!=null && (deadline==null || due.isBefore(deadline)))deadline=due;
        if(deadline!=null && !start.isBefore(deadline))throw denied();
        return jdbc.queryForObject("insert into "+ctl+".attempts(resource_id,evaluation_id,institution_id,class_id,delivery_id,revision_id,recipient_id,account_id,enrollment_id,root_version,attempt_number,payload_checksum,configuration,question_order,started_at,deadline) values(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?) returning id",Long.class,
            binding.get("resource_id"),q.evaluationId(),q.institutionId(),q.classId(),q.deliveryId(),q.revisionId(),binding.get("recipient_id"),q.recipientAccountId(),binding.get("enrollment_id"),binding.get("root_version"),used+1,binding.get("payload_checksum"),content.configuration.toString(),encode(order),Timestamp.from(start),deadline==null?null:Timestamp.from(deadline));
    }
    private void mutate(String ctl,Request q,Map<String,Object> a,Map<String,Object> bound,PublishedEvaluationContent content,String operation,Long child,Answer answer,List<Answer> submitted,Integer score,BigDecimal amount,String text,long actor) {
        validateFrozen(a,bound,content);
        ObjectNode answers=object(a,"answers");ArrayNode adjustments=(ArrayNode)tree(a.get("adjustments").toString());Instant now=now();
        boolean student=operation.equals("SAVE") || operation.equals("SUBMIT");
        if((Boolean)a.get("grade_closed") || student!=a.get("status").equals("IN_PROGRESS"))throw denied();
        int cursor=number(a,"current_question_index").intValue();
        if(operation.equals("SAVE")) {
            if(!answersOpen(a,now))throw denied();
            save(q,content,answers,answer,order(a),cursor,now);
            if(content.sequential)cursor++;
        } else if(operation.equals("SUBMIT")) {
            boolean open=answersOpen(a,now);
            if(open && !content.sequential && submitted!=null) {
                if(submitted.size()>200)throw denied();Set<Long> seen=new HashSet<>();
                for(Answer value:submitted){if(value==null || !seen.add(value.questionId()))throw denied();save(q,content,answers,value,order(a),cursor,now);}
            }
            if(open)for(var question:content.questions)if(question.open() && question.required() && !answered(answers,question.id()))throw denied();
            for(var question:content.questions)if(question.open() && !answers.has(Long.toString(question.id())))answers.set(Long.toString(question.id()),json.createObjectNode().putNull("text").put("reviewed",false));
            a.put("submitted_at",Timestamp.from(now));recalculate(a,answers,adjustments,content,now);
            if(a.get("status").equals("GRADED")){a.put("grade_closed",true);a.put("grade_closed_at",Timestamp.from(now));}
        } else switch(operation) {
            case "GRADE" -> {
                if(child==null || score==null)throw denied();var question=content.question(child);
                if(!question.open() || score<0 || score>question.points() || !answers.has(child.toString()))throw denied();
                ObjectNode value=(ObjectNode)answers.get(child.toString());value.put("reviewed",true).put("points",score).put("feedback",normalize(text,2000,false)).put("reviewer",actor).put("reviewedAt",now.toString());
                recalculate(a,answers,adjustments,content,now);
            }
            case "COMPLETE","CLOSE" -> {
                if(content.questions.stream().anyMatch(x->x.open()&&!answers.path(Long.toString(x.id())).path("reviewed").asBoolean()))throw denied();
                recalculate(a,answers,adjustments,content,now);
                if(operation.equals("CLOSE")){a.put("grade_closed",true);a.put("grade_closed_at",Timestamp.from(now));a.put("grade_closed_by",actor);}
            }
            case "ADD" -> {
                if(amount==null || amount.abs().compareTo(BigDecimal.valueOf(20))>0 || adjustments.size()>=200)throw denied();
                amount=amount.setScale(2,RoundingMode.HALF_UP);if(amount.signum()==0)throw denied();
                adjustments.add(json.createObjectNode().put("id",adjustments.size()+1).put("amount",amount).put("reason",normalize(text,500,true)).put("active",true).put("actor",actor).put("at",now.toString()));
                a.put("final_score",finalScore(a,adjustments));
            }
            case "REMOVE" -> {
                boolean found=false;for(JsonNode value:adjustments)if(child!=null && value.path("id").asLong()==child){((ObjectNode)value).put("active",false);found=true;}
                if(!found)throw denied();a.put("final_score",finalScore(a,adjustments));
            }
            case "FEEDBACK" -> a.put("overall_feedback",normalize(text,1500,false));
            default -> throw denied();
        }
        jdbc.update("update "+ctl+".attempts set answers=?::jsonb,current_question_index=?,adjustments=?::jsonb,status=?,score=?,max_score=?,final_score=?,overall_feedback=?,submitted_at=?,graded_at=?,grade_closed=?,grade_closed_at=?,grade_closed_by=? where id=?",
            answers.toString(),cursor,adjustments.toString(),a.get("status"),a.get("score"),a.get("max_score"),a.get("final_score"),a.get("overall_feedback"),a.get("submitted_at"),a.get("graded_at"),a.get("grade_closed"),a.get("grade_closed_at"),a.get("grade_closed_by"),q.attemptId());
    }
    private void save(Request q,PublishedEvaluationContent content,ObjectNode answers,Answer answer,List<Long> order,int cursor,Instant at) {
        if(answer==null || answer.revisionId()==null || answer.revisionId()!=q.revisionId() || answer.questionId()==null)throw denied();
        var question=content.question(answer.questionId());
        if(content.sequential && (cursor>=order.size() || order.get(cursor).longValue()!=question.id()))throw denied();
        var value=json.createObjectNode().put("savedAt",at.toString()).put("reviewed",false);
        if(question.open()) {
            if(answer.selectedOptionId()!=null)throw denied();value.put("text",normalize(answer.answerText(),3000,false));
            if(content.sequential && question.required() && !answered(value))throw denied();
        } else {
            if(answer.answerText()!=null || answer.selectedOptionId()!=null && question.options().stream().noneMatch(o->o.id()==answer.selectedOptionId()))throw denied();
            if(answer.selectedOptionId()==null)value.putNull("option");else value.put("option",answer.selectedOptionId());
        }
        answers.set(Long.toString(question.id()),value);
    }
    private void recalculate(Map<String,Object> a,ObjectNode answers,ArrayNode adjustments,PublishedEvaluationContent content,Instant now) {
        int total=0,maximum=0;boolean pending=false;
        for(var question:content.questions) {
            maximum=Math.addExact(maximum,question.points());var value=answers.path(Long.toString(question.id()));
            if(question.open()) {
                boolean reviewed=value.path("reviewed").asBoolean();pending|=!reviewed;
                total+=EvaluationGradeMath.awarded(true,reviewed,nullableInt(value,"points"),false,question.points());
            } else if(value.isObject()) {
                boolean correct=question.options().stream().anyMatch(o->o.correct() && Objects.equals(nullableLong(value,"option"),o.id()));
                int points=EvaluationGradeMath.awarded(false,true,null,correct,question.points());((ObjectNode)value).put("correct",correct).put("points",points).put("reviewed",true);total+=points;
            }
        }
        a.put("score",total);a.put("max_score",maximum);a.put("status",pending?"PENDING_MANUAL_REVIEW":"GRADED");
        a.put("graded_at",pending?null:a.get("graded_at")==null?Timestamp.from(now):a.get("graded_at"));a.put("final_score",finalScore(a,adjustments));
    }
    private BigDecimal finalScore(Map<String,Object>a,ArrayNode adjustments) {
        BigDecimal sum=BigDecimal.ZERO;for(JsonNode value:adjustments)if(value.path("active").asBoolean())sum=sum.add(value.path("amount").decimalValue());
        return EvaluationGradeMath.finalScore((Integer)a.get("score"),(Integer)a.get("max_score"),sum);
    }
    private void validateFrozen(Map<String,Object>a,Map<String,Object>binding,PublishedEvaluationContent content) {
        if(!Objects.equals(a.get("payload_checksum"),binding.get("payload_checksum")) || !tree(a.get("configuration").toString()).equals(content.configuration))throw denied();
        List<Long> stored=order(a);var expected=content.questions.stream().map(PublishedEvaluationContent.Question::id).toList();
        if(stored.size()!=expected.size() || !new HashSet<>(stored).equals(new HashSet<>(expected)))throw denied();
    }
    private List<Long> order(Map<String,Object>a){List<Long> result=new ArrayList<>();for(JsonNode n:tree(a.get("question_order").toString()))result.add(PublishedEvaluationContent.id(n));return List.copyOf(result);}
    private boolean answersOpen(Map<String,Object>a,Instant now){Instant deadline=time(a,"deadline");return a.get("status").equals("IN_PROGRESS") && (deadline==null||now.isBefore(deadline));}
    private boolean answered(ObjectNode answers,long id){return answered(answers.path(Long.toString(id)));}
    private boolean answered(JsonNode value){return value.path("text").isTextual() && !value.path("text").asText().isBlank();}
    private String normalize(String value,int maximum,boolean required){if(value!=null && value.length()>maximum)throw denied();value=value==null?null:value.trim();if(value!=null && value.isEmpty())value=null;if(required && value==null)throw denied();return value;}
    private SystemLog event(SessionPrincipal actor,LogEventType type,String operation,long evaluation,UUID key){return SystemLog.builder().eventType(type).category(LogCategory.EVALUATION).severity(LogSeverity.INFO).actorUserId(actor.userId()).actorRole(actor.role()).targetType("ContextResolution").targetId(evaluation).action(operation+"_NEW_EXECUTION").description(operation+"_NEW_EXECUTION").outcome("SUCCESS").correlationId(key).build();}
    private LogEventType type(String op){return switch(op){case "START"->LogEventType.EVALUATION_ATTEMPT_STARTED;case "SAVE"->LogEventType.EVALUATION_OPEN_QUESTION_SAVED;case "SUBMIT"->LogEventType.EVALUATION_ATTEMPT_SUBMITTED;case "GRADE"->LogEventType.EVALUATION_ANSWER_REVIEWED;case "COMPLETE"->LogEventType.EVALUATION_REVIEW_COMPLETED;case "ADD"->LogEventType.EVALUATION_ADJUSTMENT_ADDED;case "REMOVE"->LogEventType.EVALUATION_ADJUSTMENT_REMOVED;case "FEEDBACK"->LogEventType.EVALUATION_FEEDBACK_UPDATED;case "CLOSE"->LogEventType.EVALUATION_GRADE_CLOSED;default->throw denied();};}
    private <T>T guarded(Supplier<T> work){try{return work.get();}catch(AuthenticationException ex){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión no vigente.");}catch(ResponseStatusException ex){throw ex;}catch(NoSuchElementException|IllegalArgumentException ex){throw denied();}catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Operación no disponible.");}}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Operación no autorizada.");}
    private Number number(Map<String,Object>a,String key){return (Number)a.get(key);}
    private Instant time(Map<String,Object>a,String key){return a.get(key)==null?null:((Timestamp)a.get(key)).toInstant();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private JsonNode tree(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Receipt decode(String value){try{return json.readValue(value,Receipt.class);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private ObjectNode object(Map<String,Object>a,String key){return (ObjectNode)tree(a.get(key).toString());}
    private static String nullableText(JsonNode n,String key){return n.path(key).isTextual()?n.path(key).asText():null;}
    private static Integer nullableInt(JsonNode n,String key){return n.path(key).isIntegralNumber()?n.path(key).intValue():null;}
    private static Long nullableLong(JsonNode n,String key){return n.path(key).isIntegralNumber()?n.path(key).longValue():null;}
    private static Instant instant(JsonNode n,String key){return n.path(key).isTextual()?Instant.parse(n.path(key).asText()):null;}
}

package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.dto.InstitutionalEvaluationRead.*;
import com.morales.chemicallab.security.SessionPrincipal;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.Function;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.*;

/** Fixture-only consumer of T12-A. No call to a legacy GET that can reconcile/write. */
@Service
public class InstitutionalEvaluationReadService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final EvaluationDeliveryAccessService access;
    private final Environment environment;
    public InstitutionalEvaluationReadService(JdbcTemplate jdbc, EvaluationDeliveryAccessService access, Environment environment) {
        this.jdbc = jdbc; this.access = access; this.environment = environment;
    }

    public Object delivery(SessionPrincipal principal, Contract contract, long institution, long clazz,
                           long evaluation, long delivery, long revision, Long recipient) {
        long account = recipient == null ? account(principal) : recipient;
        return read(principal, new Request(contract, Action.READ_DELIVERY, institution, clazz, evaluation,
                delivery, revision, account, null), context -> {
            // Specifying someone else is always supervision; T12-A checks its scope/recipient.
            if (context.projection() == Projection.SUPERVISION_METADATA)
                return new Metadata(scope(context), "SUPERVISION_METADATA", context.observedAt());
            if (context.projection() != Projection.DELIVERY_WITHOUT_CORRECTION) throw unavailable();
            Content content = content(context);
            JsonNode cfg = content.configuration();
            return new Delivery(scope(context), text(cfg,"title"), text(cfg,"description"), text(cfg,"instructions"),
                    text(cfg,"topic"), integer(cfg,"time_limit_minutes"), bool(cfg,"allow_chemical_calculator",false),
                    bool(cfg,"allow_periodic_table",false), bool(cfg,"track_tab_exit",false), text(cfg,"question_display_mode"),
                    bool(cfg,"randomize_questions",false), content.questions().stream().map(Item::question).toList(), context.observedAt());
        });
    }

    public Attempt attempt(SessionPrincipal principal, Contract contract, long institution, long clazz,
                           long evaluation, long delivery, long revision, long attempt) {
        return read(principal, new Request(contract, Action.READ_OWN_ATTEMPT, institution, clazz, evaluation,
                delivery, revision, account(principal), attempt), context -> {
            Content content = content(context);
            if (context.contract() == Contract.NEW) {
                return jdbc.queryForObject("select status,attempt_number,started_at,question_order::text,answers::text from t11b8_fixture_attempts where id=?",
                        (rs,n) -> new Attempt(scope(context), attempt, rs.getString("status"),rs.getInt("attempt_number"),rs.getTimestamp("started_at").toInstant(),
                                order(tree(rs.getString("question_order")),content), null,
                                academicAnswers(tree(rs.getString("answers")),content),context.observedAt()), attempt);
            }
            validateLegacyAnswers(context);
            return jdbc.queryForObject("select status,attempt_number,started_at,question_order,current_question_index from evaluation_attempts where id=?",
                    (rs,n) -> new Attempt(scope(context),attempt,rs.getString("status"),rs.getInt("attempt_number"),
                            rs.getObject("started_at",java.time.LocalDateTime.class)==null?null:rs.getObject("started_at",java.time.LocalDateTime.class).atZone(EvaluationAccessPolicy.ACADEMIC_ZONE).toInstant(),
                            legacyOrder(rs.getString("question_order"),content),rs.getInt("current_question_index"),
                            jdbc.query("select question_id,selected_option_id,answer_text from evaluation_answers where attempt_id=? order by answered_at,id",
                                    (a,i) -> new Answer(a.getLong("question_id"),(Long)a.getObject("selected_option_id"),a.getString("answer_text")),attempt),
                            context.observedAt()),attempt);
        });
    }

    public Correction correction(SessionPrincipal principal, Contract contract, long institution, long clazz,
                                 long evaluation, long delivery, long revision, long attempt) {
        return read(principal,new Request(contract,Action.READ_CORRECTION,institution,clazz,evaluation,
                delivery,revision,account(principal),attempt),context -> {
            Content content = content(context);
            if (context.contract() == Contract.NEW) {
                return jdbc.queryForObject("select grade,answers::text from t11b8_fixture_attempts where id=?",(rs,n) -> {
                    List<Answer> ownAnswers=academicAnswers(tree(rs.getString("answers")),content);
                    return new Correction(scope(context),attempt,null,null,rs.getBigDecimal("grade"),null,
                                content.questions().stream().map(item -> {
                                    Answer answer=ownAnswers.stream()
                                            .filter(a->a.questionId()==item.question().id()).findFirst().orElse(null);
                                    return new AnswerCorrection(item.question().id(),item.question().questionText(),item.question().questionType(),
                                            answer==null?null:answer.selectedOptionId(),answer==null?null:answer.answerText(),null,null,null,item.correctOption(),item.explanation());
                                }).toList(),context.observedAt());
                },attempt);
            }
            validateLegacyAnswers(context);
            var answers = jdbc.query("""
                select q.id question_id,a.selected_option_id,a.answer_text,a.correct,a.points_awarded,a.reviewed,a.teacher_feedback,
                  q.question_text,q.question_type,q.explanation,
                  (select o.option_text from evaluation_options o where o.question_id=q.id and o.active and o.correct order by o.order_index,o.id limit 1) correct_option
                from evaluation_questions q left join evaluation_answers a on a.question_id=q.id and a.attempt_id=?
                where q.evaluation_id=? and q.active order by q.order_index,q.id
                """,(rs,n) -> {
                    boolean open = "OPEN_TEXT".equals(rs.getString("question_type"));
                    boolean reviewed = rs.getBoolean("reviewed");
                    return new AnswerCorrection(rs.getLong("question_id"),rs.getString("question_text"),rs.getString("question_type"),
                            (Long)rs.getObject("selected_option_id"),open?rs.getString("answer_text"):null,
                            open?null:(Boolean)rs.getObject("correct"),open&&!reviewed?null:(Integer)rs.getObject("points_awarded"),
                            open&&reviewed?rs.getString("teacher_feedback"):null,open?null:rs.getString("correct_option"),rs.getString("explanation"));
                },attempt,evaluation);
            return jdbc.queryForObject("select score,max_score,final_score,overall_feedback from evaluation_attempts where id=?",(rs,n) ->
                    new Correction(scope(context),attempt,(Integer)rs.getObject("score"),(Integer)rs.getObject("max_score"),
                            rs.getBigDecimal("final_score"),rs.getString("overall_feedback"),answers,context.observedAt()),attempt);
        });
    }

    private <T> T read(SessionPrincipal principal, Request request, Function<Context,T> projection) {
        // All three gates are required, checked on each invocation. A flag never bypasses SQL guards.
        String schema = environment.getProperty("app.evaluations.institutional.fixture-schema","");
        if (!environment.getProperty("app.evaluations.institutional.enabled",Boolean.class,false)
                || !Arrays.equals(environment.getActiveProfiles(),new String[]{"test"})
                || !schema.matches("t03_[a-f0-9]{32}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Lectura no habilitada.");
        return access.readCoherently(principal,request,schema,projection);
    }
    private long account(SessionPrincipal principal) {
        if (principal == null || principal.userId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión requerida.");
        return principal.userId();
    }
    private Scope scope(Context c) {return new Scope(c.contract(),c.institutionId(),c.classId(),c.evaluationId(),c.deliveryId(),c.revisionId());}
    private void validateLegacyAnswers(Context c) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from evaluation_answers a join evaluation_questions q on q.id=a.question_id
              left join evaluation_options o on o.id=a.selected_option_id where a.attempt_id=?
                and (q.evaluation_id<>? or (a.selected_option_id is not null and o.question_id<>q.id)))
            """,Boolean.class,c.attemptId(),c.evaluationId()))) throw unavailable();
    }
    private record Item(Question question,String correctOption,String explanation) {}
    private record Content(JsonNode configuration,List<Item> questions) {}

    private Content content(Context c) {
        if (c.contract() == Contract.NEW) {
            // Never the DRAFT anchor, latest successor or a client-selected JSON projection.
            return academicContent(tree(jdbc.queryForObject("select payload::text from t11b8_revisions where id=? and status='PUBLISHED'",String.class,c.revisionId())));
        }
        // LEGACY content remains authoritative in the original engine; B3 is a technical snapshot.
        String cfg=jdbc.queryForObject("""
            select jsonb_build_object('title',title,'description',description,'instructions',instructions,'topic',topic,
              'time_limit_minutes',time_limit_minutes,'allow_chemical_calculator',allow_chemical_calculator,
              'allow_periodic_table',allow_periodic_table,'track_tab_exit',track_tab_exit,
              'question_display_mode',question_display_mode,'randomize_questions',randomize_questions)::text
            from evaluations where id=?
            """,String.class,c.evaluationId());
        var questions=jdbc.query("select id,question_text,question_type,points,order_index,required,explanation from evaluation_questions where evaluation_id=? and active order by order_index,id",
                (rs,n) -> {
                    long id=rs.getLong("id");
                    var options=jdbc.query("select id,option_text,order_index from evaluation_options where question_id=? and active order by order_index,id",
                            (o,i) -> new Option(o.getLong("id"),o.getString("option_text"),o.getInt("order_index")),id);
                    return new Item(new Question(id,rs.getString("question_text"),rs.getString("question_type"),rs.getInt("points"),
                            rs.getInt("order_index"),rs.getBoolean("required"),options),null,rs.getString("explanation"));
                },c.evaluationId());
        return new Content(tree(cfg),questions);
    }
    private Content academicContent(JsonNode payload) {
        if (!payload.path("resource").isObject() || !payload.path("questions").isArray() || !payload.path("options").isArray()) throw unavailable();
        var items=new ArrayList<Item>();
        for(JsonNode q:payload.get("questions")) {
            if (!bool(q,"active",true)) continue;
            long id=id(q.get("id"));
            var options=new ArrayList<Option>();String correct=null;
            for(JsonNode o:payload.get("options")) {
                if (id(o.get("question_id"))!=id || !bool(o,"active",true)) continue;
                options.add(new Option(id(o.get("id")),text(o,"option_text"),index(o)));
                if(bool(o,"correct",false)) correct=text(o,"option_text");
            }
            options.sort(Comparator.comparingInt(Option::orderIndex).thenComparingLong(Option::id));
            items.add(new Item(new Question(id,text(q,"question_text"),text(q,"question_type"),integer(q,"points"),
                    index(q),bool(q,"required",true),List.copyOf(options)),correct,text(q,"explanation")));
        }
        items.sort(Comparator.comparingInt((Item i)->i.question().orderIndex()).thenComparingLong(i->i.question().id()));
        return new Content(payload.get("resource"),List.copyOf(items));
    }
    private List<Answer> academicAnswers(JsonNode answers,Content content) {
        if(!answers.isObject()) throw unavailable();
        var result=new ArrayList<Answer>();
        for(Item item:content.questions()) {
            Question q=item.question();JsonNode value=answers.get(Long.toString(q.id()));
            if(value==null || value.isNull()) continue;
            if("OPEN_TEXT".equals(q.questionType())) {
                // B8's fixture contract uses scalar text. Explicit structured support has an allowlist.
                String answer=value.isTextual()?value.textValue():value.isObject()?text(value,"answer_text"):null;
                if(answer!=null) result.add(new Answer(q.id(),null,answer));
            } else {
                JsonNode selected=value.isObject()?value.get("selected_option_id"):value;
                if(selected==null || selected.isNull()) continue;
                long option=id(selected);
                if(q.options().stream().noneMatch(o->o.id()==option)) throw unavailable();
                result.add(new Answer(q.id(),option,null));
            }
        }
        return List.copyOf(result);
    }
    private Item item(Content c,long id) {return c.questions().stream().filter(i->i.question().id()==id).findFirst().orElseThrow(InstitutionalEvaluationReadService::unavailable);}
    private List<Long> legacyOrder(String raw,Content content) {
        // Match the legacy read fallback in memory; never repair a row or randomize on GET.
        if(raw==null || raw.isBlank()) return content.questions().stream().map(i->i.question().id()).toList();
        return legacyQuestionOrder(raw);
    }
    private List<Long> order(JsonNode raw,Content content) {
        if(!raw.isArray()) throw unavailable();
        var order=new ArrayList<Long>();
        for(JsonNode value:raw) {long id=id(value);item(content,id);if(order.contains(id)) throw unavailable();order.add(id);}
        return List.copyOf(order);
    }
    private static JsonNode tree(String raw) {
        try {return JSON.readTree(raw);}catch(Exception invalid){throw unavailable();}
    }
    private static String text(JsonNode node,String name) {
        JsonNode value=node.get(name);if(value==null||value.isNull()) return null;
        if(!value.isTextual()) throw unavailable();return value.textValue();
    }
    private static Integer integer(JsonNode node,String name) {
        JsonNode value=node.get(name);if(value==null||value.isNull())return null;
        if(!value.isIntegralNumber()||!value.canConvertToInt())throw unavailable();return value.intValue();
    }
    private static int index(JsonNode node) {Integer value=integer(node,"order_index");return value==null?0:value;}
    private static boolean bool(JsonNode node,String name,boolean fallback) {
        JsonNode value=node.get(name);if(value==null||value.isNull())return fallback;
        if(!value.isBoolean())throw unavailable();return value.booleanValue();
    }
    private static long id(JsonNode value) {
        try {
            if(value==null || !(value.isIntegralNumber()||value.isTextual()) || !value.asText().matches("[1-9][0-9]{0,18}"))throw unavailable();
            return Long.parseLong(value.asText());
        } catch(NumberFormatException invalid){throw unavailable();}
    }
    private static ResponseStatusException unavailable() {return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Proyección no disponible.");}
}

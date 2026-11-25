package com.morales.chemicallab.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.security.SessionPrincipal;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.morales.chemicallab.service.EvaluationRuntime.*;
import static com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService.*;

@Service
public class EvaluationRuntimeAuthoring {
    private static final String ASSIGN = "ASSIGN_NEW_DELIVERY";
    private static final String CURRENT_RECIPIENTS = """
        with t as (select statement_timestamp() at)
        select e.id,e.account_id
        from evaluation_runtime.enrollments e
        join evaluation_runtime.memberships m on m.id=e.membership_id
        join user_accounts u on u.id=m.account_id
        join evaluation_runtime.roles g on g.membership_id=m.id and g.role='ESTUDIANTE'
        cross join t
        where e.institution_id=? and e.class_id=? and e.id=any(?::bigint[]) and %s and %s
        """.formatted(ROLE, EPISODE);
    private record Revision(long id, Long parent, int number, long version, String status, String payload, Instant published,
                            long authorMembership, long authorAccount, long originClass, int maxAttempts) {}
    private final EvaluationRuntime runtime;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditLogWriter audit;
    private final EntityManager entities;

    public EvaluationRuntimeAuthoring(EvaluationRuntime runtime, JdbcTemplate jdbc, ObjectMapper json,
                                      AuditLogWriter audit, EntityManager entities) {
        this.runtime = runtime;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.entities = entities;
    }

    Draft read(SessionPrincipal actor, Scope scope) {
        return runtime.unit(actor, scope.institutionId(), scope.evaluationId(), false, null, () -> {
            var revision = revision(scope);
            require(revision.status().equals("DRAFT"));
            authorize(actor, scope, revision, "EDIT_DRAFT");
            ObjectNode payload = EvaluationAuthoringContent.draftView((ObjectNode) parse(revision.payload()));
            return new Draft(scope.evaluationId(), revision.id(), revision.parent(), revision.number(), revision.version(),
                json.convertValue(payload, new TypeReference<>() {}));
        });
    }

    Receipt write(SessionPrincipal actor, Scope scope, Action action, Command command) {
        String kind = action.name() + "_NEW_AUTHORING";
        return runtime.unit(actor, scope.institutionId(), scope.evaluationId(), true, command.key(), () -> {
            String request = runtime.encode(new AuthoringRequest(scope, action, command.version(), command.payload()));
            boolean create = action==Action.CREATE;
            String faculty = action==Action.PUBLISH ? "PUBLISH" : "EDIT_DRAFT";
            Rights rights = runtime.rights(actor, scope.institutionId(), scope.classId());
            require(rights.teacher());
            Revision revision = create ? null : revision(scope);
            if (!create) authorize(actor, scope, revision, faculty, rights);
            Receipt replay = runtime.replay(actor,scope.institutionId(),scope.classId(),command.key(),kind,request,Receipt.class);
            if (replay!=null) {
                if (create) require(revision(new Scope(scope.institutionId(),scope.classId(),replay.evaluationId(),
                    replay.revisionId())).authorMembership()==rights.membership());
                return replay;
            }
            runtime.begin(actor,scope.institutionId(),scope.classId(),scope.evaluationId(),command.key(),kind,request);

            long evaluation;
            long revisionId;
            if (create) {
                evaluation = jdbc.queryForObject("""
                    insert into evaluation_runtime.resources(institution_id,origin_class_id,author_account_id,author_membership_id,max_attempts)
                    values(?,?,?,?,?) returning id
                    """, Long.class,scope.institutionId(),scope.classId(),actor.userId(),rights.membership(),
                    command.payload().path("resource").path("max_attempts").intValue());
                revisionId = insertRevision(evaluation,scope.institutionId(),null,1,command.payload().toString());
            } else {
                evaluation = scope.evaluationId();
                revisionId = change(action, command, scope, revision);
            }

            var updatedScope = new Scope(scope.institutionId(),scope.classId(),evaluation,revisionId);
            var updated = revision(updatedScope);
            var type = switch (action) {
                case CREATE -> LogEventType.EVALUATION_CREATED;
                case PUBLISH -> LogEventType.EVALUATION_PUBLISHED;
                case EDIT, SUCCESSOR -> LogEventType.EVALUATION_UPDATED;
            };
            var result = new Receipt(command.key(),evaluation,revisionId,updated.parent(),updated.number(),
                updated.version(),updated.status(),updated.published());
            seal(actor,kind,type,evaluation,command.key(),result);
            authorize(actor,updatedScope,updated,faculty);
            return result;
        });
    }

    InstitutionalEvaluationAssignmentService.Receipt assign(SessionPrincipal actor, Scope scope, UUID operation, JsonNode input) {
        return runtime.unit(actor, scope.institutionId(), scope.evaluationId(), true, operation, () -> {
            var revision = revision(scope);
            authorize(actor,scope,revision,"ASSIGN");
            require(revision.status().equals("PUBLISHED"));
            String canonical = input.toString();
            var replay = runtime.replay(actor,scope.institutionId(),scope.classId(),operation,ASSIGN,canonical,
                InstitutionalEvaluationAssignmentService.Receipt.class);
            if (replay!=null) return replay;

            Instant start = Instant.parse(input.path("startsAt").asText());
            Instant due = Instant.parse(input.path("dueAt").asText());
            if (revision.version()!=input.path("version").longValue() || !runtime.now().isBefore(due)
                || rootClosed(scope.evaluationId(), null)) throw conflict();
            String ids = StreamSupport.stream(input.path("enrollmentIds").spliterator(),false)
                .map(JsonNode::asText).collect(Collectors.joining(",","{","}"));
            int expected = input.path("enrollmentIds").size();
            verifyEpisodes(scope,ids,expected);
            runtime.begin(actor,scope.institutionId(),scope.classId(),scope.evaluationId(),operation,ASSIGN,canonical);

            long delivery = jdbc.queryForObject("""
                insert into evaluation_runtime.deliveries(resource_id,revision_id,institution_id,class_id,starts_at,due_at)
                values(?,?,?,?,?,?) returning id
                """,Long.class,scope.evaluationId(),scope.revisionId(),scope.institutionId(),scope.classId(),
                Timestamp.from(start),Timestamp.from(due));
            int admitted = jdbc.update("""
                insert into evaluation_runtime.recipients(delivery_id,resource_id,revision_id,institution_id,class_id,enrollment_id,account_id)
                select ?,?,?,?,?,eligible.id,eligible.account_id from (%s) eligible
                """.formatted(CURRENT_RECIPIENTS), delivery, scope.evaluationId(),scope.revisionId(),scope.institutionId(),
                scope.classId(),scope.institutionId(),scope.classId(),ids);
            require(admitted==expected);
            var result = new InstitutionalEvaluationAssignmentService.Receipt(operation,scope.evaluationId(),scope.revisionId(),delivery,
                1,start,due,admitted);
            seal(actor,ASSIGN,LogEventType.EVALUATION_ASSIGNED,scope.evaluationId(),operation,result);
            authorize(actor,scope,revision,"ASSIGN");
            verifyEpisodes(scope,ids,expected);
            if (!runtime.now().isBefore(due) || rootClosed(scope.evaluationId(),delivery)) throw conflict();
            return result;
        });
    }

    private void verifyEpisodes(Scope scope, String ids, int expected) {
        int eligible = jdbc.queryForObject("""
            select count(distinct account_id) from (%s) eligible
            """.formatted(CURRENT_RECIPIENTS),Integer.class,scope.institutionId(),scope.classId(),ids);
        require(eligible==expected);
    }

    private boolean rootClosed(long evaluation, Long excluding) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select count(*)>0 and bool_and(due_at<=statement_timestamp())
            from evaluation_runtime.deliveries where resource_id=? and (?::bigint is null or id<>?)
            """,Boolean.class,evaluation,excluding,excluding));
    }

    private long change(Action action, Command command, Scope scope, Revision revision) {
        if (revision.version()!=command.version()) throw conflict();
        if (action==Action.SUCCESSOR) {
            if (!revision.status().equals("PUBLISHED") || Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from evaluation_runtime.revisions where resource_id=? and revision_number>?)
                """,Boolean.class,scope.evaluationId(),revision.number()))) throw conflict();
            return insertRevision(scope.evaluationId(),scope.institutionId(),revision.id(),revision.number()+1,revision.payload());
        }
        if (!revision.status().equals("DRAFT")) throw conflict();
        if (action==Action.EDIT) {
            if (command.payload().path("resource").path("max_attempts").intValue()!=revision.maxAttempts()) throw conflict();
            jdbc.update("update evaluation_runtime.revisions set payload=?::jsonb,version=version+1 where id=?",
                command.payload().toString(), revision.id());
        } else {
            new PublishedEvaluationContent(parse(revision.payload()));
            jdbc.update("""
                update evaluation_runtime.revisions set status='PUBLISHED',published_at=clock_timestamp(),version=version+1 where id=?
                """,revision.id());
        }
        return revision.id();
    }

    private long insertRevision(long evaluation,long institution,Long parent,int number,String payload) {
        return jdbc.queryForObject("""
            insert into evaluation_runtime.revisions(resource_id,institution_id,parent_id,revision_number,payload)
            values(?,?,?,?,?::jsonb) returning id
            """,Long.class,evaluation,institution,parent,number,payload);
    }

    private Revision revision(Scope scope) {
        var rows = jdbc.query("""
            select v.id,v.parent_id,v.revision_number,v.version,v.status,v.payload::text,v.published_at,
                   r.author_membership_id,r.author_account_id,r.origin_class_id,r.max_attempts
            from evaluation_runtime.revisions v join evaluation_runtime.resources r on r.id=v.resource_id
            where v.id=? and v.resource_id=? and v.institution_id=?
            """,(rs,n)->new Revision(rs.getLong(1),rs.getObject(2,Long.class),rs.getInt(3),rs.getLong(4),rs.getString(5),
                rs.getString(6),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant(),rs.getLong(8),
                rs.getLong(9),rs.getLong(10),rs.getInt(11)),scope.revisionId(),scope.evaluationId(),scope.institutionId());
        if (rows.size()!=1) throw denied();
        return rows.get(0);
    }

    private void authorize(SessionPrincipal actor,Scope scope,Revision revision,String faculty) {
        authorize(actor,scope,revision,faculty,runtime.rights(actor,scope.institutionId(),scope.classId()));
    }

    private void authorize(SessionPrincipal actor,Scope scope,Revision revision,String faculty,Rights rights) {
        require(rights.teacher());
        boolean owner = revision.authorMembership()==rights.membership() && revision.authorAccount()==actor.userId()
            && ("ASSIGN".equals(faculty) || revision.originClass()==scope.classId());
        require(owner || Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from evaluation_runtime.faculties x
             where x.resource_id=? and x.institution_id=? and x.membership_id=? and x.faculty=?
               and x.revoked_at is null and x.valid_from<=statement_timestamp() and statement_timestamp()<x.valid_until)
            """,Boolean.class,scope.evaluationId(),scope.institutionId(),rights.membership(),faculty)));
    }

    private void seal(SessionPrincipal actor,String action,LogEventType type,long evaluation,UUID id,Object result) {
        var event = SystemLog.builder().category(LogCategory.EVALUATION).eventType(type).severity(LogSeverity.INFO)
            .actorUserId(actor.userId()).actorRole(actor.role()).targetType("ContextResolution").targetId(evaluation)
            .action(action).description(action).outcome("SUCCESS").correlationId(id).build();
        audit.persist(event);
        entities.flush();
        jdbc.execute("set local synchronous_commit=on");
        runtime.finish(id,evaluation,result,event.getId());
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record AuthoringRequest(Scope scope,Action action,Long version,JsonNode payload) {}
}

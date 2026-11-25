package com.morales.chemicallab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.AcademicRevision;
import com.morales.chemicallab.repository.AcademicRevisionRepository;
import com.morales.chemicallab.security.AccountSessionService;
import com.morales.chemicallab.security.SessionPrincipal;
import jakarta.persistence.EntityManager;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
public class InstitutionalEvaluationAuthoringService {
    public enum Action { CREATE, EDIT, PUBLISH, SUCCESSOR }
    public record Scope(long institutionId, long classId, Long evaluationId, Long revisionId) {}
    public record Receipt(UUID operationId, long evaluationId, long revisionId, Long parentRevisionId, int revisionNumber,
                          long version, String status, Instant publishedAt) {}
    public record Draft(long evaluationId, long revisionId, Long parentRevisionId, int revisionNumber, long version,
                        Map<String,Object> payload) {}
    record Member(long id, boolean assigned) {}
    private record Resource(long id, long author, long authorMembership, int maxAttempts) {}
    private record Head(long id, long proposal, JsonNode resolution) {}
    private record Context(Resource resource, Head head) {}
    private record Command(UUID key, Long version, ObjectNode payload) {}

    private final JdbcTemplate jdbc;
    private final Environment env;
    private final AccountSessionService sessions;
    private final AcademicRevisionRepository revisions;
    private final AuditLogWriter audit;
    private final EntityManager entities;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public InstitutionalEvaluationAuthoringService(JdbcTemplate jdbc, Environment env, AccountSessionService sessions,
            AcademicRevisionRepository revisions, AuditLogWriter audit, EntityManager entities, ObjectMapper json,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.env = env;
        this.sessions = sessions;
        this.revisions = revisions;
        this.audit = audit;
        this.entities = entities;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    public Draft read(SessionPrincipal actor, Scope scope) {
        return unit(actor, false, ctl -> {
            Member member = member(actor, scope);
            Context context = authorize(ctl, actor, scope, member, "EDIT_DRAFT");
            AcademicRevision revision = revision(ctl, scope, context);
            require(revision.status().equals("DRAFT"));
            var payload = EvaluationAuthoringContent.draftView((ObjectNode) parse(revision.payload()));
            authorize(ctl, actor, scope, member(actor, scope), "EDIT_DRAFT");
            return new Draft(scope.evaluationId(), revision.id(), revision.parentId(), revision.number(), revision.version(),
                json.convertValue(payload, new TypeReference<Map<String,Object>>() {}));
        });
    }

    public Receipt write(SessionPrincipal actor, Scope scope, Action action, byte[] body) {
        return unit(actor, true, ctl -> {
            Command command = command(action, input(body));
            Member member = member(actor, scope);
            boolean creating = action == Action.CREATE;
            String faculty = action == Action.PUBLISH ? "PUBLISH" : "EDIT_DRAFT";
            Context context = creating ? null : authorize(ctl, actor, scope, member, faculty);
            if (creating) require(member.assigned());
            AcademicRevision selected = creating ? null : revision(ctl, scope, context);
            String canonical = request(ctl, actor, scope, action, command);
            String assignment = assignmentControl();
            String receiptSources = assignment == null ? ctl + ".receipts" :
                "(select operation_id,request,result from " + ctl + ".receipts union all "
                    + "select operation_id,request,result from " + assignment + ".receipts) receipts";
            var previous = jdbc.queryForList("select request=?::jsonb identical, result::text result from "
                + receiptSources + " where operation_id=?", canonical, command.key());
            if (!previous.isEmpty()) {
                var row = previous.get(0);
                if (previous.size() != 1 || !Boolean.TRUE.equals(row.get("identical"))) throw conflict();
                Receipt receipt = decode((String) row.get("result"));
                Scope replayScope = creating
                    ? new Scope(scope.institutionId(), scope.classId(), receipt.evaluationId(), receipt.revisionId()) : scope;
                authorize(ctl, actor, replayScope, member(actor, replayScope), faculty);
                return receipt;
            }

            boolean continueAssignment = !creating && assignment != null
                && exists("select " + assignment + ".current(?,?,false)", scope.evaluationId(), scope.institutionId());
            Resource resource;
            long evaluation;
            AcademicRevision updated;
            String before;
            Long head;
            if (creating) {
                resource = create(scope, actor, member, command);
                evaluation = jdbc.queryForObject("select evaluation_id from t11b8_resources where id=?", Long.class, resource.id());
                long initial = jdbc.queryForObject("select id from t11b8_revisions where resource_id=? and revision_number=1", Long.class, resource.id());
                updated = revisions.revision(initial);
                before = "[]";
                head = null;
            } else {
                resource = context.resource();
                evaluation = scope.evaluationId();
                before = jdbc.queryForObject("select t11p05_manifest(" + ctl + ".anchor(?))::text", String.class, evaluation);
                head = context.head() == null ? null : context.head().id();
                updated = change(action, command, resource, selected);
            }

            var receipt = new Receipt(command.key(), evaluation, updated.id(), updated.parentId(), updated.number(),
                updated.version(), updated.status(), updated.publishedAt());
            var event = event(actor, action, evaluation, command.key());
            audit.persist(event);
            entities.flush();
            jdbc.execute("set local synchronous_commit=on");
            if (continueAssignment) {
                jdbc.update("insert into " + assignment + ".receipt_commands values(?,?,?,?,?,?,?,?,null,?::jsonb,?::jsonb,?::jsonb,?)",
                    command.key(), action.name(), actor.userId(), scope.institutionId(), scope.classId(),
                    evaluation, resource.id(), updated.id(), before, canonical, encode(receipt), event.getId());
            } else {
                jdbc.queryForObject("select " + ctl + ".record_receipt(?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)",
                    Object.class, command.key(), action.name(), actor.userId(), scope.institutionId(), scope.classId(),
                    evaluation, resource.id(), updated.id(), head, before, canonical, encode(receipt), event.getId());
            }
            Scope resolved = new Scope(scope.institutionId(), scope.classId(), evaluation, updated.id());
            Member current = member(actor, resolved);
            if (creating) require(current.assigned());
            else authorize(ctl, actor, resolved, current, faculty);
            return receipt;
        });
    }

    private <T> T unit(SessionPrincipal actor, boolean writing, Function<String,T> work) {
        String schema = env.getProperty("app.evaluations.institutional.fixture-schema", "");
        if (!env.getProperty("app.evaluations.institutional.enabled", Boolean.class, false)
            || !env.getProperty("app.evaluations.institutional.new-authoring-enabled", Boolean.class, false)
            || !Arrays.equals(env.getActiveProfiles(), new String[]{"test"}) || !schema.matches("t03_[a-f0-9]{32}"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operación no habilitada.");
        try {
            return transaction.execute(status -> {
                InstitutionalFixtureUnit.lock(jdbc, schema, writing);
                String ctl = InstitutionalFixtureUnit.authoringControl(jdbc, schema);
                if (ctl == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operación no habilitada.");
                if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no vigente.");
                sessions.revalidate(actor);
                T result = work.apply(ctl);
                sessions.revalidate(actor);
                return result;
            });
        } catch (AuthenticationException failure) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no vigente.");
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operación no disponible.");
        }
    }

    Member member(SessionPrincipal actor, Scope scope) {
        Timestamp now = jdbc.queryForObject("select clock_timestamp()", Timestamp.class);
        var rows = jdbc.query("""
            select m.id, exists(
              select 1 from (
                select id, institution_id, class_id, membership_id, status, state_since, valid_from, valid_until, activated_at, ended_at
                from teaching_assignments where function='RESPONSABLE'
                union all
                select id, institution_id, class_id, membership_id, status, state_since, valid_from, valid_until, activated_at, ended_at
                from t11b6_co_assignments where function='CO_DOCENTE'
              ) a where a.institution_id=c.institution_id and a.class_id=c.id and a.membership_id=m.id
               and a.status='ACTIVE' and a.state_since<=at.ts and a.valid_from<=at.ts and a.activated_at<=at.ts
               and at.ts<a.valid_until and (a.ended_at is null or at.ts<a.ended_at)
            ) assigned
            from academic_classes c
            join academic_periods p on p.id=c.period_id and p.institution_id=c.institution_id
            join institutions i on i.id=c.institution_id
            join institution_memberships m on m.institution_id=i.id
            cross join (select ?::timestamptz ts) at
            where c.id=? and i.id=? and m.account_id=? and i.status='ACTIVE' and c.status='ACTIVE' and p.status='OPEN'
             and c.state_since<=at.ts and p.state_since<=at.ts and p.valid_from<=at.ts and at.ts<p.valid_until
             and t11b6_role_valid(m.id,i.id,'DOCENTE',at.ts,m.account_id)
            """, (rs, row) -> new Member(rs.getLong("id"), rs.getBoolean("assigned")), now, scope.classId(), scope.institutionId(), actor.userId());
        require(rows.size() == 1);
        return rows.get(0);
    }

    private Context authorize(String ctl, SessionPrincipal actor, Scope scope, Member member, String faculty) {
        var resources = jdbc.query("""
            select id,author_account_id,author_membership_id,max_attempts from t11b8_resources
            where evaluation_id=? and institution_id=? and creation='EXPLICIT_NEW' and availability='AVAILABLE'
            """, (rs, row) -> new Resource(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)),
            scope.evaluationId(), scope.institutionId());
        require(resources.size() == 1);
        Resource resource = resources.get(0);
        Long nativeClass = jdbc.query("select class_id from " + ctl + ".receipts where action='CREATE' and evaluation_id=?",
            rs -> rs.next() ? rs.getLong(1) : null, scope.evaluationId());
        Head head = head("evaluations", scope.evaluationId());
        if (head == null) {
            String assignment = assignmentControl();
            require(nativeClass != null && ((assignment != null
                && exists("select " + assignment + ".current(?,?,false)", scope.evaluationId(), scope.institutionId())) || exists("""
                select coalesce((select r.root_version is null and r.after_hash=%1$s.digest(t11p05_manifest(%1$s.anchor(r.evaluation_id)))
                from %1$s.receipts r where r.evaluation_id=? order by r.ordinal desc limit 1),false)
                """.formatted(ctl), scope.evaluationId())));
        } else {
            require(sameScope(head, scope) && fresh(ctl, head.proposal()));
            Head resourceHead = head("t11b8_resources", resource.id());
            require(resourceHead != null && resourceHead.proposal() == head.proposal()
                && sameScope(resourceHead, scope));
        }

        boolean owner = resource.author() == actor.userId() && resource.authorMembership() == member.id() && member.assigned()
            && originClass(scope, resource, nativeClass, head);
        if (!owner) require(granted(ctl, resource.id(), scope, member, faculty));
        return new Context(resource, head);
    }

    boolean granted(String ctl, long resourceId, Scope scope, Member member, String faculty) {
        return exists("""
            select exists(select 1 from %s.grants g cross join (select clock_timestamp() ts) at
              where g.resource_id=? and g.institution_id=? and g.membership_id=? and g.faculty=?
               and g.status='ACTIVE' and g.revoked_at is null and g.valid_from<=at.ts and at.ts<g.valid_until)
            """.formatted(ctl), resourceId, scope.institutionId(), member.id(), faculty);
    }

    private boolean originClass(Scope scope, Resource resource, Long nativeClass, Head head) {
        if (nativeClass != null) return nativeClass == scope.classId();
        JsonNode rootClass = head.resolution().path("classId");
        if (!rootClass.isMissingNode()) return rootClass.isIntegralNumber() && rootClass.longValue() == scope.classId();
        return exists("""
            select exists(select 1 from t11b8_deliveries d join t11b10_versions v
              on v.source_table='t11b8_deliveries' and v.source_pk=jsonb_build_object('id',d.id)
             where d.resource_id=? and d.class_id=? and d.institution_id=? and v.proposal_id=?
              and v.resolution->>'scope'='INSTITUTION'
              and v.resolution->'institutionId'=to_jsonb(d.institution_id) and v.resolution->'classId'=to_jsonb(d.class_id)
              and not exists(select 1 from t11b10_versions next where next.predecessor_id=v.id))
            """, resource.id(), scope.classId(), scope.institutionId(), head.proposal());
    }

    private boolean fresh(String ctl, long proposal) {
        String schema = jdbc.queryForObject("select current_schema()", String.class);
        if (exists("select t11p05_fresh(?) or " + InstitutionalFixtureUnit.writeControl(jdbc, schema)
            + ".fresh(?) or " + ctl + ".fresh(?)", proposal, proposal, proposal)) return true;
        String assignment = InstitutionalFixtureUnit.assignmentControl(jdbc, schema);
        return assignment != null && exists("select " + assignment + ".fresh(?)", proposal);
    }

    private String assignmentControl() {
        return InstitutionalFixtureUnit.assignmentControl(jdbc, jdbc.queryForObject("select current_schema()", String.class));
    }

    private Head head(String source, long id) {
        var rows = jdbc.query("""
            select v.id,v.proposal_id,v.resolution::text from t11b10_versions v
            where v.source_table=? and v.source_pk=jsonb_build_object('id',?::bigint)
              and not exists(select 1 from t11b10_versions next where next.predecessor_id=v.id)
            """, (rs, row) -> new Head(rs.getLong(1),rs.getLong(2),parse(rs.getString(3))), source, id);
        require(rows.size() <= 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean sameScope(Head head, Scope scope) {
        JsonNode resolution = head.resolution();
        JsonNode clazz = resolution.path("classId");
        return resolution.path("scope").asText().equals("INSTITUTION")
            && resolution.path("institutionId").isIntegralNumber()
            && resolution.path("institutionId").longValue() == scope.institutionId()
            && (clazz.isMissingNode() || (clazz.isIntegralNumber() && clazz.longValue() == scope.classId()));
    }

    private Resource create(Scope scope, SessionPrincipal actor, Member member, Command command) {
        require(exists("select exists(select 1 from teacher_profiles where user_id=?)", actor.userId()));
        int max = command.payload().path("resource").path("max_attempts").intValue();
        long resource = jdbc.queryForObject("""
            insert into t11b8_creations(operation_id,kind,institution_id,author_account_id,author_membership_id,payload)
            values(?,'EVALUATION',?,?,?,?::jsonb) returning resource_id
            """, Long.class, command.key(), scope.institutionId(), actor.userId(), member.id(), command.payload().toString());
        return new Resource(resource, actor.userId(), member.id(), max);
    }

    private AcademicRevision revision(String ctl, Scope scope, Context context) {
        require(exists("select exists(select 1 from t11b8_revisions where id=? and resource_id=? and institution_id=?)",
            scope.revisionId(), context.resource().id(), scope.institutionId()));
        if (context.head() != null) {
            Head revisionHead = head("t11b8_revisions", scope.revisionId());
            if (revisionHead != null) {
                require(revisionHead.proposal() == context.head().proposal() && sameScope(revisionHead, scope));
            } else {
                require(successor(ctl, context.resource().id(), scope.revisionId(), context.head().id()));
            }
        }
        return revisions.revision(scope.revisionId());
    }

    boolean successor(String ctl, long resource, long revision, long head) {
        if (exists("""
            select exists(select 1 from %s.receipts where action='SUCCESSOR'
              and resource_id=? and revision_id=? and root_version=?)
            """.formatted(ctl), resource, revision, head)) return true;
        String assignment = assignmentControl();
        return assignment != null && exists("""
            select exists(select 1 from %s.receipts where action='SUCCESSOR'
              and resource_id=? and revision_id=? and after_stamp->'rootVersion'=to_jsonb(?::bigint))
            """.formatted(assignment), resource, revision, head);
    }

    private AcademicRevision change(Action action, Command command, Resource resource, AcademicRevision revision) {
        if (revision.version() != command.version()) throw conflict();
        if (action == Action.SUCCESSOR) {
            if (!revision.status().equals("PUBLISHED") || exists(
                "select exists(select 1 from t11b8_revisions where resource_id=? and (revision_number>? or status='DRAFT'))",
                resource.id(), revision.number())) throw conflict();
            long next = jdbc.queryForObject("""
                insert into t11b8_revisions(resource_id,institution_id,parent_id,revision_number,payload,payload_checksum,operation_id)
                select resource_id,institution_id,id,revision_number+1,payload,payload_checksum,? from t11b8_revisions where id=?
                returning id
                """, Long.class, command.key(), revision.id());
            return revisions.revision(next);
        }

        if (!revision.status().equals("DRAFT")) throw conflict();
        int changed;
        if (action == Action.EDIT) {
            ObjectNode payload = command.payload();
            if (payload.path("resource").path("max_attempts").intValue() != resource.maxAttempts()) throw conflict();
            EvaluationAuthoringContent.preserveProvenance(payload, (ObjectNode) parse(revision.payload()));
            changed = jdbc.update("""
                update t11b8_revisions set payload=?::jsonb,payload_checksum=md5(?::jsonb::text),version=version+1
                where id=? and status='DRAFT' and version=?
                """, payload.toString(), payload.toString(), revision.id(), command.version());
        } else {
            new PublishedEvaluationContent(parse(revision.payload()));
            changed = jdbc.update("""
                update t11b8_revisions set status='PUBLISHED',version=version+1 where id=? and status='DRAFT' and version=?
                """, revision.id(), command.version());
        }
        if (changed != 1) throw conflict();
        return revisions.revision(revision.id());
    }

    private Command command(Action action, JsonNode body) {
        Set<String> fields = switch (action) {
            case CREATE -> Set.of("operationId", "payload");
            case EDIT -> Set.of("operationId", "expectedVersion", "payload");
            case PUBLISH, SUCCESSOR -> Set.of("operationId", "expectedVersion");
        };
        if (body == null || !body.isObject() || body.size() != fields.size()) throw invalid();
        for (String key : fields) if (!body.hasNonNull(key)) throw invalid();
        JsonNode id = body.path("operationId");
        if (!id.isTextual() || !id.asText().matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalid();
        JsonNode version = body.path("expectedVersion");
        if (action != Action.CREATE && (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() <= 0)) throw invalid();
        return new Command(UUID.fromString(id.asText()), action == Action.CREATE ? null : version.longValue(),
            fields.contains("payload") ? EvaluationAuthoringContent.input(body.get("payload")) : null);
    }

    private String request(String ctl, SessionPrincipal actor, Scope scope, Action action, Command command) {
        ObjectNode request = json.createObjectNode().put("actor", actor.userId()).put("action", action.name())
            .put("institution", scope.institutionId()).put("class", scope.classId()).put("evaluation", scope.evaluationId())
            .put("revision", scope.revisionId()).put("version", command.version());
        if (command.payload() != null) request.put("payloadHash", jdbc.queryForObject("select " + ctl + ".digest(?::jsonb)",
            String.class, command.payload().toString()));
        return request.toString();
    }

    private SystemLog event(SessionPrincipal actor, Action action, long evaluation, UUID key) {
        var type = switch (action) {
            case CREATE -> LogEventType.EVALUATION_CREATED;
            case EDIT, SUCCESSOR -> LogEventType.EVALUATION_UPDATED;
            case PUBLISH -> LogEventType.EVALUATION_PUBLISHED;
        };
        String operation = action.name() + "_NEW_AUTHORING";
        return SystemLog.builder().category(LogCategory.EVALUATION).eventType(type).severity(LogSeverity.INFO)
            .actorUserId(actor.userId()).actorRole(actor.role()).targetType("ContextResolution").targetId(evaluation)
            .action(operation).description(operation).outcome("SUCCESS").correlationId(key).build();
    }

    private boolean exists(String query, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(query, Boolean.class, args));
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    private JsonNode input(byte[] body) {
        try { return json.readTree(body); }
        catch (IOException invalid) { throw invalid(); }
    }

    private String encode(Receipt value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    private Receipt decode(String value) {
        try { return json.readValue(value, Receipt.class); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    private static void require(boolean condition) {
        if (!condition) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operación no autorizada.");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Versión u operación incompatible.");
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entrada de autoría no válida.");
    }
}

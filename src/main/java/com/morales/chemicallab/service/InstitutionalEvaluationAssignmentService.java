package com.morales.chemicallab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.security.AccountSessionService;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService.Scope;
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
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService.sameScope;

@Service
public class InstitutionalEvaluationAssignmentService {
    public record Receipt(UUID operationId, long evaluationId, long revisionId, long deliveryId, long version,
                          Instant startsAt, Instant dueAt, int recipientCount) {}

    private static final Set<String> FIELDS = Set.of("operationId", "expectedRevisionVersion", "startsAt", "dueAt", "enrollmentIds");
    private static final String AUDIT_ACTION = "ASSIGN_NEW_DELIVERY";
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final AccountSessionService sessions;
    private final InstitutionalEvaluationAuthoringService authoring;
    private final AuditLogWriter audit;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public InstitutionalEvaluationAssignmentService(JdbcTemplate jdbc, Environment environment, AccountSessionService sessions,
            InstitutionalEvaluationAuthoringService authoring, AuditLogWriter audit, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.sessions = sessions;
        this.authoring = authoring;
        this.audit = audit;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    public Receipt assign(SessionPrincipal actor, Scope scope, byte[] body) {
        String schema = environment.getProperty("app.evaluations.institutional.fixture-schema", "");
        if (!environment.getProperty("app.evaluations.institutional.enabled", Boolean.class, false)
            || !environment.getProperty("app.evaluations.institutional.new-assignment-enabled", Boolean.class, false)
            || !Arrays.equals(environment.getActiveProfiles(), new String[]{"test"}) || !schema.matches("t03_[a-f0-9]{32}"))
            throw disabled();
        if (actor == null) throw unauthorized();
        try {
            return transaction.execute(status -> {
                InstitutionalFixtureUnit.lock(jdbc, schema, true);
                String g = InstitutionalFixtureUnit.authoringControl(jdbc, schema);
                String h = InstitutionalFixtureUnit.assignmentControl(jdbc, schema);
                if (g == null || h == null) throw disabled();
                sessions.revalidate(actor);

                JsonNode data = input(body);
                JsonNode key = data.get("operationId");
                if (!key.isTextual() || !key.textValue().matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalid();
                UUID operation = UUID.fromString(key.textValue());
                String request = canonical(actor, scope, data).toString();
                authorize(actor, scope, g, h);

                var previous = jdbc.queryForList("""
                    select action='ASSIGN' and request=?::jsonb identical,result::text result
                     from %s.receipts where operation_id=?
                    union all select false,null::text from %s.receipts where operation_id=?
                    """.formatted(h, g), request, operation, operation);
                String result;
                if (previous.isEmpty()) {
                    var evidence = SystemLog.builder().category(LogCategory.EVALUATION).eventType(LogEventType.EVALUATION_ASSIGNED)
                        .severity(LogSeverity.INFO).actorUserId(actor.userId()).actorRole(actor.role()).targetType("ContextResolution")
                        .targetId(scope.evaluationId()).action(AUDIT_ACTION).description(AUDIT_ACTION).outcome("SUCCESS")
                        .correlationId(operation).build();
                    audit.persist(evidence);
                    jdbc.execute("set local synchronous_commit=on");
                    result = jdbc.queryForObject("select " + h + ".assign(?,?::jsonb,?)::text",
                        String.class, operation, request, evidence.getId());
                } else {
                    if (previous.size() != 1 || !Boolean.TRUE.equals(previous.get(0).get("identical"))) throw conflict();
                    result = (String) previous.get(0).get("result");
                }
                authorize(actor, scope, g, h);
                sessions.revalidate(actor);
                return receipt(result);
            });
        } catch (AuthenticationException failure) {
            throw unauthorized();
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    if ("P7803".equals(sql.getSQLState())) throw denied();
                    if ("P7809".equals(sql.getSQLState())) throw conflict();
                }
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operación no disponible.");
        }
    }

    private void authorize(SessionPrincipal actor, Scope scope, String g, String h) {
        var member = authoring.member(actor, scope);
        if (!member.assigned()) throw denied();
        var resources = jdbc.query("""
            select s.id,s.author_account_id,s.author_membership_id from t11b8_resources s
             join t11b8_revisions v on v.resource_id=s.id and v.institution_id=s.institution_id
            where s.evaluation_id=? and s.institution_id=? and s.creation='EXPLICIT_NEW'
             and s.availability='AVAILABLE' and v.id=?
            """, (rs, i) -> new Resource(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
            scope.evaluationId(), scope.institutionId(), scope.revisionId());
        if (resources.size() != 1) throw denied();
        Resource resource = resources.get(0);
        boolean owner = resource.owner() == actor.userId() && resource.membership() == member.id();
        if (!owner && !authoring.granted(g, resource.id(), scope, member, "ASSIGN")) throw denied();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select " + h + ".current(?,?,true)",
                Boolean.class, scope.evaluationId(), scope.institutionId()))) throw denied();

        var head = authoring.head("evaluations", scope.evaluationId());
        if (head != null) {
            var resourceHead = authoring.head("t11b8_resources", resource.id());
            var revisionHead = authoring.head("t11b8_revisions", scope.revisionId());
            if (!sameScope(head, scope) || resourceHead == null || resourceHead.proposal() != head.proposal()
                || !sameScope(resourceHead, scope)) throw denied();
            if (revisionHead == null) {
                if (!authoring.successor(g, resource.id(), scope.revisionId(), head.id())) throw denied();
            } else if (revisionHead.proposal() != head.proposal() || !sameScope(revisionHead, scope)) {
                throw denied();
            }
        }
    }

    private record Resource(long id, long owner, long membership) {}

    private JsonNode input(byte[] body) {
        try {
            JsonNode data = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(body);
            if (data == null || !data.isObject() || data.size() != FIELDS.size()
                || FIELDS.stream().anyMatch(key -> !data.hasNonNull(key))) throw invalid();
            return data;
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private JsonNode canonical(SessionPrincipal actor, Scope scope, JsonNode data) {
        long version = positiveLong(data.get("expectedRevisionVersion"));
        Instant starts = instant(data.get("startsAt"));
        Instant due = instant(data.get("dueAt"));
        if (!starts.isBefore(due)) throw invalid();
        JsonNode ids = data.get("enrollmentIds");
        if (!ids.isArray() || ids.size() < 1 || ids.size() > 100) throw invalid();
        var episodes = new TreeSet<Long>();
        for (JsonNode id : ids) {
            if (!episodes.add(positiveLong(id))) throw invalid();
        }
        var request = json.createObjectNode().put("actor", actor.userId()).put("institution", scope.institutionId())
            .put("class", scope.classId()).put("evaluation", scope.evaluationId()).put("revision", scope.revisionId())
            .put("version", version).put("startsAt", starts.toString()).put("dueAt", due.toString());
        var list = request.putArray("enrollmentIds");
        episodes.forEach(list::add);
        return request;
    }

    private static long positiveLong(JsonNode id) {
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) throw invalid();
        return id.longValue();
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,6})?Z")
            || node.textValue().startsWith("0000-")) throw invalid();
        try {
            return Instant.parse(node.textValue());
        } catch (DateTimeParseException failure) {
            throw invalid();
        }
    }

    private Receipt receipt(String value) {
        try {
            return json.readValue(value, Receipt.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ResponseStatusException disabled() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Operación no habilitada.");
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no vigente.");
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Asignación no autorizada.");
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entrada de asignación no válida.");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Publicación, ventana u operación incompatible.");
    }
}

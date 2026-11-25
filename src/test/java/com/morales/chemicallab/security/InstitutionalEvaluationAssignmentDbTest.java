package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.institution.Enrollment;
import com.morales.chemicallab.entity.institution.TeachingAssignment;
import com.morales.chemicallab.service.DelegationMaintenanceService;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.DOCENTE;
import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.ESTUDIANTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstitutionalEvaluationAssignmentDbTest {
    private static final String FLAG = "app.evaluations.institutional.new-assignment-enabled";
    private final NewEvaluationExecutionDbTest f = new NewEvaluationExecutionDbTest();
    private String control;
    private String g;

    @BeforeEach void setup(TestInfo info) throws Exception {
        f.start(info);
        g = "t12g_" + f.f.schema.substring(4);
        control = "t12h_" + f.f.schema.substring(4);
        f.sql().execute(f.r.script("t12g-v1"));
        f.sql().execute(f.r.script("t12h-v1"));
    }

    @AfterEach void stop() throws Exception {
        try {
            if (control != null) f.sql().execute("drop schema if exists " + control + "," + g + " cascade");
        } finally {
            f.stop();
        }
    }

    @Test void contextDeltaPreservesApprovedDeliveryAndLeavesNewRowsPending() throws Exception {
        Request original = f.q;
        long attempt = f.begun();
        long oldHeads = f.count("select count(*) from t11b10_versions");
        long oldApprovals = f.count("select count(*) from t11b10_approvals");
        long oldProposal = f.count("select proposal_id from " + control + ".root_head(?)", original.evaluationId());
        f.h.activation.put(FLAG, true);
        var teachers = f.f.app.getBean(DelegationMaintenanceService.class);
        Instant from = f.r.from.plusSeconds(12);
        var co = teachers.planCoTeacher(original.institutionId(), f.r.otherClass, f.r.authorMember, from, f.r.until, from.minusSeconds(1));
        teachers.transitionCoTeacher(co.id(), TeachingAssignment.Status.ACTIVE, from);
        String path = path(original, original.institutionId(), f.r.otherClass);
        var command = command(2, f.r.otherEpisode);
        long[] before = budget();
        JsonNode assignment = f.ok(post(path, f.h.teacher, command));
        assertThat(budget()).containsExactly(before);
        Request next = target(original, f.r.otherClass, assignment.path("deliveryId").longValue());
        assertThat(f.count("select count(*) from t11b10_versions")).isEqualTo(oldHeads);
        assertThat(f.count("select count(*) from t11b10_approvals")).isEqualTo(oldApprovals);
        assertThat(f.sql().queryForObject("select t11p05_fresh(?)", Boolean.class, oldProposal)).isFalse();
        assertThat(f.sql().queryForObject("select " + control + ".fresh(?)", Boolean.class, oldProposal)).isTrue();
        new TransactionTemplate(f.f.app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
            f.revisions.closeDelivery(next.deliveryId(), 1, f.op());
            assertThat(f.sql().queryForObject("select " + control + ".fresh(?)", Boolean.class, oldProposal)).isFalse();
            tx.setRollbackOnly();
        });
        f.denied(f.send("GET", f.h.pupil, next, "", null));
        f.denied(f.send("POST", f.h.pupil, next, "/own-attempts", f.body(f.op())));
        assertThat(f.own(original, attempt).path("attemptNumber").intValue()).isEqualTo(1);
        f.save(original, attempt, f.op(), 17, null, "Still active");
        f.submit(original, attempt, f.op(), List.of(f.answer(original, 18, 31L, null)));

        long proposal = proposal(original);
        var context = f.e.c;
        context.approve(proposal, "PLATFORM", context.platform, context.platformGrant);
        assertThatThrownBy(() -> context.service.apply(proposal, f.op())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> context.approve(proposal, "INSTITUTION", context.platform, context.foreignSchoolGrant))
            .isInstanceOf(DataAccessException.class);
        assertThat(f.count("select count(*) from t11b10_approvals where proposal_id=?", proposal)).isOne();
        assertThat(f.count("select count(*) from t11b10_versions where proposal_id=?", proposal)).isZero();
        context.approve(proposal, "INSTITUTION", context.school, context.foreignSchoolGrant);
        assertThat(context.service.apply(proposal, f.op())).isEqualTo("APPLIED");
        assertThat(f.sql().queryForObject("select " + control + ".fresh(?)", Boolean.class, oldProposal)).isFalse();
        assertThat(f.begin(next, f.op()).path("attemptNumber").intValue()).isEqualTo(2);
        assertThat(f.ok(post(path, f.h.teacher, command))).isEqualTo(assignment);
        f.revisions.nextDraft(original.revisionId(), f.op());
        status(403, post(path, f.h.teacher, command));
        f.denied(f.send("GET", f.h.pupil, original, "/own-attempts/" + attempt, null));
    }

    private Request target(Request original, long clazz, long delivery) {
        return new Request(original.contract(), original.action(), original.institutionId(), clazz, original.evaluationId(),
            delivery, original.revisionId(), original.recipientAccountId(), null);
    }

    private long proposal(Request original) throws Exception {
        var context = f.e.c;
        var mapping = f.f.json.readTree(context.mapping("evaluations", original.evaluationId(), original.institutionId(), original.classId()));
        for (JsonNode item : mapping.get("resolutions")) {
            var row = (ObjectNode) item;
            String source = row.path("source").asText();
            long id = row.path("pk").path("id").longValue();
            if (source.equals("t11b8_deliveries")) {
                row.put("classId", f.count("select class_id from t11b8_deliveries where id=?", id));
            } else if (source.equals("t11b8_recipients")) {
                row.put("classId", f.count("select d.class_id from t11b8_recipients r join t11b8_deliveries d on d.id=r.delivery_id where r.id=?", id));
                row.put("enrollmentId", f.count("select enrollment_id from t11b8_recipients where id=?", id));
                row.put("sourceTimezone", "America/Lima");
            } else {
                row.remove("classId");
            }
        }
        return context.service.propose(context.root("evaluations", original.evaluationId()), mapping.toString(), "FIXTURE-NEW-ASSIGNMENT", f.op());
    }

    @Test void bilateralIsolationAndRecipientEpisodesAreExact() throws Exception {
        Request a = publishNative(f.r.institution, f.r.classId, f.h.teacher);
        Teacher bOwner = coTeacher(f.r.foreign, f.r.foreignClass, f.r.until);
        Request b = publishNative(f.r.foreign, f.r.foreignClass, bOwner.token());
        String aPath = path(a, a.institutionId(), a.classId());
        String bPath = path(b, b.institutionId(), b.classId());
        ObjectNode aCommand = command(2, f.r.episode);
        ObjectNode bCommand = command(2, f.e.c.foreignEpisode);
        status(404, post(aPath, f.h.teacher, aCommand));
        f.h.activation.put(FLAG, true);
        var environment = (ConfigurableEnvironment) f.f.app.getEnvironment();
        environment.setActiveProfiles("prod");
        try {
            status(404, post(aPath, f.h.teacher, aCommand));
        } finally {
            environment.setActiveProfiles("test");
        }
        status(401, post(aPath, null, aCommand));
        status(403, post(aPath, f.h.pupil, aCommand));
        status(403, post(aPath, f.h.admin, aCommand));
        status(403, post(aPath, bOwner.token(), aCommand));
        status(403, post(bPath, f.h.teacher, bCommand));
        status(403, post(path(a, b.institutionId(), b.classId()), bOwner.token(), bCommand));
        status(403, post(path(b, a.institutionId(), a.classId()), f.h.teacher, aCommand));
        status(403, post(path(a, a.institutionId(), b.classId()), f.h.teacher, aCommand));
        status(403, post(path(a, a.institutionId(), a.classId()).replace("/revisions/" + a.revisionId(), "/revisions/" + b.revisionId()),
            f.h.teacher, aCommand));
        status(403, post(aPath, f.h.teacher, command(2, f.r.episode, f.e.c.foreignEpisode)));
        status(403, post(bPath, bOwner.token(), command(2, f.r.episode)));
        status(403, post(aPath, f.h.teacher, command(2, f.r.otherEpisode)));
        status(403, post(aPath, f.h.teacher, command(2, Long.MAX_VALUE)));
        status(400, post(aPath, f.h.teacher, command(2, f.r.episode, f.r.episode)));
        status(400, post(aPath, f.h.teacher, command(2)));
        status(400, post(aPath, f.h.teacher, command(2, java.util.stream.LongStream.rangeClosed(1, 101).toArray())));
        status(400, post(aPath, f.h.teacher, aCommand.deepCopy().put("accountId", f.f.student.getId())));
        status(413, http("POST", aPath, f.h.teacher, "{\"x\":\"" + "a".repeat(1_050_000) + "\"}"));
        status(400, post(aPath, f.h.teacher, aCommand.deepCopy().put("startsAt", "2026-11-22T00:00:00.1234567Z")));
        status(409, post(aPath, f.h.teacher, command(1, f.r.episode)));

        var extra = f.f.account(Role.ESTUDIANTE, f.f.teacher, "3", "A");
        long extraMember = f.r.membership(a.institutionId(), extra.getId(), ESTUDIANTE);
        Instant now = Instant.now();
        long expired = enroll(a, extraMember, now.minusSeconds(1000), now.minusSeconds(500));
        status(403, post(aPath, f.h.teacher, command(2, expired)));
        long withdrawn = enroll(a, extraMember, now.minusSeconds(400), f.r.until);
        f.r.academic.transitionEnrollment(withdrawn, Enrollment.Status.WITHDRAWN, now.minusSeconds(200));
        status(403, post(aPath, f.h.teacher, command(2, withdrawn)));
        long active = enroll(a, extraMember, now.minusSeconds(100), f.r.until);
        long[] before = budget();
        var both = command(2, f.r.episode, active);
        JsonNode assigned = f.ok(post(aPath, f.h.teacher, both));
        var reversed = both.deepCopy();
        reversed.putArray("enrollmentIds").add(active).add(f.r.episode);
        assertThat(f.ok(post(aPath, f.h.teacher, reversed))).isEqualTo(assigned);
        assertThat(assigned.path("recipientCount").intValue()).isEqualTo(2);
        assertThat(assigned.path("startsAt").asText()).isEqualTo(both.path("startsAt").asText());
        assertThat(assigned.path("dueAt").asText()).isEqualTo(both.path("dueAt").asText());
        assertThat(f.count("""
            select count(*) from t11b8_recipients r join enrollments e on e.id=r.enrollment_id
             join institution_memberships m on m.id=e.membership_id and m.institution_id=e.institution_id
            where r.delivery_id=? and r.account_id=m.account_id and r.institution_id=e.institution_id and e.class_id=?
            """, assigned.path("deliveryId").longValue(), a.classId())).isEqualTo(2);
        status(409, post(aPath, f.h.teacher, both.deepCopy().put("dueAt", Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MICROS).toString())));
        status(409, post(aPath, f.h.teacher, command(2, active)));
        var assignedB = f.ok(post(bPath, bOwner.token(), bCommand));
        assertThat(assignedB.path("recipientCount").intValue()).isOne();
        assertThat(budget()).containsExactly(before);
        assertThat(f.count("select count(*) from " + control + ".receipts where action='ASSIGN'")).isEqualTo(2);
        f.denied(f.send("GET", f.h.pupil, target(a, a.classId(), assigned.path("deliveryId").longValue()), "", null));
        var draft = f.ok(http("POST", authoring(a) + "/successor", f.h.teacher, f.body(f.op(), "expectedVersion", 2)));
        var successor = authoring(a).replace("/revisions/" + a.revisionId(), "/revisions/" + draft.path("revisionId").longValue());
        var copy = f.ok(http("GET", successor, f.h.teacher, null));
        var payload = (ObjectNode) copy.get("payload");
        ((ObjectNode) payload.get("resource")).put("title", "Revised while assignment awaits reconciliation");
        f.ok(http("PUT", successor, f.h.teacher, f.body(f.op(), "expectedVersion", 1, "payload", payload)));
        f.ok(http("POST", successor + "/publish", f.h.teacher, f.body(f.op(), "expectedVersion", 2)));
        assertThat(f.ok(post(aPath, f.h.teacher, both))).isEqualTo(assigned);
        f.resolve(a, null);
        assertThat(f.begin(target(a, a.classId(), assigned.path("deliveryId").longValue()), f.op()).path("attemptNumber").intValue())
            .isOne();
    }

    @Test void assignGrantAndCurrentTeachingAreIndependentOnWritesAndReplays() throws Exception {
        f.h.activation.put(FLAG, true);
        Request a = publishNative(f.r.institution, f.r.classId, f.h.teacher);
        String path = path(a, a.institutionId(), a.classId());
        Teacher delegate = coTeacher(a.institutionId(), a.classId(), f.r.until);
        Teacher expiredTeacher = coTeacher(a.institutionId(), a.classId(), Instant.now().minusSeconds(60));
        long resource = f.count("select resource_id from t11b8_revisions where id=?", a.revisionId());
        var request = command(2, f.r.episode);
        Instant now = Instant.now();
        long admin = f.count("select membership_id from membership_role_grants where id=?", f.e.c.foreignSchoolGrant);
        grant(resource, a.institutionId(), admin, delegate.member(), "EDIT_DRAFT", now.minusSeconds(1), now.plusSeconds(300));
        grant(resource, a.institutionId(), admin, delegate.member(), "PUBLISH", now.minusSeconds(1), now.plusSeconds(300));
        grant(resource, a.institutionId(), admin, delegate.member(), "ASSIGN", now.minusSeconds(50), now.minusSeconds(20));
        status(403, post(path, delegate.token(), request));
        grant(resource, a.institutionId(), admin, expiredTeacher.member(), "ASSIGN", now.minusSeconds(2), now.plusSeconds(400));
        status(403, post(path, expiredTeacher.token(), request));
        long assign = grant(resource, a.institutionId(), admin, delegate.member(), "ASSIGN", now.minusSeconds(1), now.plusSeconds(600));
        status(403, post(path(a, a.institutionId(), f.r.otherClass), delegate.token(), command(2, f.r.otherEpisode)));
        var saved = f.ok(post(path, delegate.token(), request));
        assertThat(f.ok(post(path, delegate.token(), request))).isEqualTo(saved);
        status(409, post(path, f.h.teacher, request));
        f.sql().update("""
            update %s.grants set status='REVOKED',revoked_at=clock_timestamp(),revoked_by=?,revoker_membership_id=?,reason='REMOVED'
            where id=?
            """.formatted(g), f.e.c.school.getId(), admin, assign);
        status(403, post(path, delegate.token(), request));
        var restored = Instant.now();
        grant(resource, a.institutionId(), admin, delegate.member(), "ASSIGN", restored, restored.plusSeconds(400));
        assertThat(f.ok(post(path, delegate.token(), request))).isEqualTo(saved);
        long role = f.count("select id from membership_role_grants where membership_id=? and role='DOCENTE'", delegate.member());
        f.r.institutions.revokeRole(role, Instant.now());
        status(403, post(path, delegate.token(), request));
        var actor = (SessionPrincipal) f.f.app.getBean(AccountSessionService.class).authenticate(delegate.token()).getPrincipal();
        f.sql().update("update account_sessions set revoked_at=clock_timestamp() where id=?", actor.sessionId());
        status(401, post(path, delegate.token(), request));
    }

    private record Teacher(String token, long member) {}

    private Teacher coTeacher(long institution, long clazz, Instant until) throws Exception {
        var account = f.f.account(Role.DOCENTE, null, "3", "A");
        long member = f.r.membership(institution, account.getId(), DOCENTE);
        var service = f.f.app.getBean(DelegationMaintenanceService.class);
        Instant from = f.r.from.plusSeconds(12);
        var assignment = service.planCoTeacher(institution, clazz, member, from, until, from.minusSeconds(1));
        service.transitionCoTeacher(assignment.id(), TeachingAssignment.Status.ACTIVE, from);
        return new Teacher(f.f.login(account), member);
    }

    private long enroll(Request target, long member, Instant from, Instant until) {
        var episode = f.r.academic.createEnrollment(target.institutionId(), target.classId(), member, from, until, from.minusSeconds(1));
        return f.r.academic.transitionEnrollment(episode.id(), Enrollment.Status.ACTIVE, from).id();
    }

    private long grant(long resource, long institution, long admin, long member, String faculty, Instant from, Instant until) {
        return f.count("""
            insert into %s.grants(resource_id,institution_id,membership_id,faculty,valid_from,valid_until,granted_at,
                granted_by,grantor_membership_id,reason)
            values(?,?,?,?,?,?,?,?,?,'DESIGNATED') returning id
            """.formatted(g), resource, institution, member, faculty, Timestamp.from(from), Timestamp.from(until),
            Timestamp.from(from.minusSeconds(1)), f.e.c.school.getId(), admin);
    }

    private Request publishNative(long institution, long clazz, String token) throws Exception {
        f.h.activation.put("app.evaluations.institutional.new-authoring-enabled", true);
        String prefix = authoring(institution, clazz);
        var created = f.ok(http("POST", prefix, token, f.body(f.op(), "payload",
            f.f.json.readTree(f.payload(3, "ALL_AT_ONCE", false, 0)))));
        Request q = new Request(f.q.contract(), f.q.action(), institution, clazz, created.path("evaluationId").longValue(),
            0L, created.path("revisionId").longValue(), f.f.student.getId(), null);
        f.ok(http("POST", authoring(q) + "/publish", token, f.body(f.op(), "expectedVersion", 1)));
        return q;
    }

    private long[] budget() {
        return new long[]{f.count("select count(*) from t11b8_budgets"), f.count("select count(*) from " + f.ctl + ".attempts"),
            f.count("select count(*) from t11b3_account_attempt_budgets")};
    }

    private static String authoring(long institution, long clazz) {
        return "/api/institutions/" + institution + "/classes/" + clazz + "/evaluation-authoring/NEW/v1/evaluations";
    }

    private static String authoring(Request q) {
        return authoring(q.institutionId(), q.classId()) + "/" + q.evaluationId() + "/revisions/" + q.revisionId();
    }

    private String path(Request q, long institution, long clazz) {
        return authoring(institution, clazz).replace("/evaluation-authoring/", "/evaluation-assignments/")
            + "/" + q.evaluationId() + "/revisions/" + q.revisionId() + "/deliveries";
    }

    private ObjectNode command(long version, long... ids) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var body = f.f.json.createObjectNode().put("operationId", f.op().toString()).put("expectedRevisionVersion", version)
            .put("startsAt", now.minusSeconds(20).toString()).put("dueAt", now.plusSeconds(800).toString());
        var list = body.putArray("enrollmentIds");
        for (long id : ids) list.add(id);
        return body;
    }

    private HttpResponse<String> post(String path, String token, ObjectNode body) throws Exception {
        return http("POST", path, token, body.toString());
    }

    private HttpRequest request(String method, String path, String token, String body) {
        var builder = HttpRequest.newBuilder(URI.create(f.f.origin + path)).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> http(String method, String path, String token, String body) throws Exception {
        return f.f.http.send(request(method, path, token, body), HttpResponse.BodyHandlers.ofString());
    }

    private static void status(int status, HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    }

    @Test void rollbackContendedDeliveryAndRestoreItsDurableReceipt() throws Exception {
        f.h.activation.put(FLAG, true);
        Request a = publishNative(f.r.institution, f.r.classId, f.h.teacher);
        String path = path(a, a.institutionId(), a.classId());
        ObjectNode command = command(2, f.r.episode);
        var baseline = state();
        f.sql().execute("""
            create function fixture_h_audit_failure() returns trigger language plpgsql as $$
            begin
                if NEW.action='ASSIGN_NEW_DELIVERY' then
                    if not exists(select 1 from %s.receipts where operation_id=NEW.correlation_id)
                        then raise exception 'fixture: assignment was not staged before deferred audit';end if;
                    raise exception 'fixture deferred audit rollback';
                end if;
                return NEW;
            end $$;
            create constraint trigger fixture_h_audit_failure after insert on system_logs deferrable initially deferred
                for each row execute function fixture_h_audit_failure()
            """.formatted(control));
        status(503, post(path, f.h.teacher, command));
        f.sql().execute("drop trigger fixture_h_audit_failure on system_logs; drop function fixture_h_audit_failure()");
        assertThat(state()).isEqualTo(baseline);

        var limit = f.sql().queryForMap("select maximum_rows,maximum_bytes from t11b9_limits where institution_id=? and category='ORIGINAL'",
            a.institutionId());
        f.e.c.b.service.limit(a.institutionId(), "ORIGINAL", 1, 1, f.op());
        status(503, post(path, f.h.teacher, command));
        assertThat(state()).isEqualTo(baseline);
        f.e.c.b.service.limit(a.institutionId(), "ORIGINAL", ((Number)limit.get("maximum_rows")).longValue(),
            ((Number)limit.get("maximum_bytes")).longValue(), f.op());

        ObjectNode competing = command(2, f.r.episode);
        var threads = Executors.newFixedThreadPool(2);
        ObjectNode winner;
        try {
            var one = threads.submit(() -> f.f.http.send(request("POST", path, f.h.teacher, command.toString()), HttpResponse.BodyHandlers.discarding()));
            var two = threads.submit(() -> f.f.http.send(request("POST", path, f.h.teacher, competing.toString()), HttpResponse.BodyHandlers.discarding()));
            var first = one.get(70, TimeUnit.SECONDS);
            var second = two.get(70, TimeUnit.SECONDS);
            assertThat(List.of(first.statusCode(), second.statusCode())).contains(200);
            winner = first.statusCode() == 200 ? command : competing;
            var loser = winner == command ? competing : command;
            status(409, post(path, f.h.teacher, loser));
        } finally {
            threads.shutdownNow();
        }
        var saved = f.ok(post(path, f.h.teacher, winner));
        assertThat(f.count("select count(*) from " + control + ".receipts where action='ASSIGN'")).isOne();
        assertThat(f.count("select count(*) from system_logs where action='ASSIGN_NEW_DELIVERY' and outcome='SUCCESS'")).isOne();
        assertThatThrownBy(() -> f.sql().update("insert into " + control + ".receipts(operation_id) values(?)", f.op()))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("generated history only");

        String version = f.sql().queryForObject("select fingerprint from " + control + ".schema_version", String.class);
        String expansion = f.r.script("t12h-v1");
        certify(f.sql(), expansion);
        assertThat(f.sql().queryForObject("select fingerprint from " + control + ".schema_version", String.class)).isEqualTo(version);
        var tx = new TransactionTemplate(f.f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s -> {
            f.sql().execute("alter table " + control + ".receipts disable trigger immutable");
            assertThatThrownBy(() -> f.sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");
            s.setRollbackOnly();
        });
        certify(f.sql(), expansion);
        restore(path, winner, saved);
    }

    private void certify(JdbcTemplate sql, String expansion) throws Exception {
        sql.execute(f.r.script("t12f-v1"));
        sql.execute(f.r.script("t12g-v1"));
        sql.execute(expansion);
    }

    private Map<String,String> state() {
        var state = new TreeMap<String,String>();
        for (String table : List.of("t11b8_deliveries", "t11b8_recipients", "t11b8_events", "t11b8_budgets",
                "t11b9_counters", "t11b9_items", control + ".receipts", f.ctl + ".attempts"))
            state.put(table, f.sql().queryForObject(f.r.signature(table), String.class));
        state.put("success", f.sql().queryForObject("""
            select md5(coalesce(jsonb_agg(to_jsonb(l) order by id)::text,'')) from system_logs l
            where action='ASSIGN_NEW_DELIVERY' and outcome='SUCCESS'
            """, String.class));
        return state;
    }

    private void restore(String path, ObjectNode body, JsonNode saved) throws Exception {
        String url = f.f.setting("spring.datasource.url");
        String port = url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        String user = f.f.setting("spring.datasource.username");
        var dir = Files.createDirectories(Path.of("target/t12h-recovery", f.f.schema)).toAbsolutePath();
        var signatures = new TreeMap<String,String>();
        String suffix = f.f.schema.substring(4);
        f.sql().update("update authentication_limits set expires_at=greatest(expires_at,floor(extract(epoch from clock_timestamp())*1000)::bigint+3600000)");
        for (String schema : List.of(f.f.schema, "t06_" + suffix, "t12c_" + suffix, "t12d_" + suffix,
                "t12e_" + suffix, f.ctl, g, control))
            for (String table : f.sql().queryForList("select tablename from pg_tables where schemaname=? order by tablename", String.class, schema))
                signatures.put(schema + "." + table, f.sql().queryForObject(f.r.signature(schema + "." + table), String.class));
        var copy = dir.resolve("copy");
        f.r.nativeTool("pg_basebackup",List.of("-h","127.0.0.1","-p",port,"-U",user),
            List.of("-D",copy.toString(),"--wal-method=stream","--checkpoint=fast"),dir);
        f.r.nativeTool("pg_verifybackup",List.of(),List.of(copy.toString()),dir);
        int copyPort = TransitionWalRecoveryFixture.port();
        Files.writeString(copy.resolve("postgresql.auto.conf"), "listen_addresses='127.0.0.1'\nport=" + copyPort
            + "\nmax_connections=20\nmax_locks_per_transaction=512\nshared_buffers='16MB'\n");
        String copyUrl = "jdbc:postgresql://127.0.0.1:" + copyPort + "/chemicallab_t01_test";
        boolean started = false;
        String origin = f.f.origin;
        ConfigurableApplicationContext app = null;
        try {
            f.r.nativeTool("pg_ctl", List.of(), List.of("-D",copy.toString(),"-l",dir.resolve("copy-postgres.log").toString(),"-w","start"),dir);
            started = true;
            try (var connection = DriverManager.getConnection(copyUrl,user,f.f.setting("spring.datasource.password"));
                    var statement = connection.createStatement()) {
                statement.execute("set search_path to " + f.f.schema + ",public");
                for (String script : List.of("t12f-v1", "t12g-v1", "t12h-v1")) statement.execute(f.r.script(script));
                var restored = new TreeMap<String,String>();
                for (String table : signatures.keySet()) {
                    try (var rows = statement.executeQuery(f.r.signature(table))) {
                        rows.next();
                        restored.put(table, rows.getString(1));
                    }
                }
                assertThat(restored).isEqualTo(signatures);
                Files.writeString(dir.resolve("signatures.json"), f.f.json.writeValueAsString(Map.of("original",signatures,"restored",restored)));
            }
            app = f.f.startApp("--spring.datasource.url=" + copyUrl + "?currentSchema=" + f.f.schema,
                "--spring.jpa.hibernate.ddl-auto=none", "--app.evaluations.institutional.enabled=true",
                "--" + FLAG + "=true", "--app.evaluations.institutional.fixture-schema=" + f.f.schema);
            f.f.origin = f.f.origin(app);
            assertThat(f.ok(post(path, f.h.teacher, body))).isEqualTo(saved);
        } finally {
            f.f.origin = origin;
            if (app != null) app.close();
            if (started) f.r.nativeTool("pg_ctl",List.of(),List.of("-D",copy.toString(),"-m","fast","-w","stop"),dir);
        }
    }

}

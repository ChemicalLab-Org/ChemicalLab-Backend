package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.institution.TeachingAssignment;
import com.morales.chemicallab.service.DelegationMaintenanceService;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.*;
import static org.assertj.core.api.Assertions.*;

class InstitutionalEvaluationAuthoringDbTest {
    private static final String FLAG = "app.evaluations.institutional.new-authoring-enabled";
    private NewEvaluationExecutionDbTest fixture;
    private String control;
    private String expansion;

    @BeforeEach void setup(TestInfo info) throws Exception {
        fixture = new NewEvaluationExecutionDbTest();
        fixture.start(info);
        control = "t12g_" + fixture.f.schema.substring(4);
        expansion = fixture.r.script("t12g-v1");
        fixture.sql().execute(expansion);
    }

    @AfterEach void stop() throws Exception {
        if (control != null) fixture.sql().execute("drop schema if exists " + control + " cascade");
        if (fixture != null) fixture.stop();
    }

    @Test void nativeCreationIsBilateralBoundedAndIsolatedFromLegacy() throws Exception {
        long a = fixture.r.institution, classA = fixture.r.classId;
        long b = fixture.r.foreign, classB = fixture.r.foreignClass;
        String teacher = fixture.h.teacher;
        String createA = prefix(a, classA);
        String createB = prefix(b, classB);
        var pa = payload(3, "Academic-only A");
        String first = createBody(pa);
        status(404, send("POST", createA, teacher, first));
        fixture.h.activation.put(FLAG, true);
        status(401, send("POST", createA, null, first));
        status(403, send("POST", createA, fixture.h.admin, first));
        status(403, send("POST", createA, fixture.h.pupil, first));
        status(403, send("POST", createB, teacher, first));
        status(403, send("POST", prefix(a, classB), teacher, first));
        String forbiddenAuthority = first.replace("\"payload\"", "\"authorAccountId\":1,\"payload\"");
        status(400, send("POST", createA, teacher, forbiddenAuthority));
        status(400, send("POST", createA, teacher, first.replace("\"max_attempts\":3", "\"max_attempts\":\"3\"")));
        status(400, send("POST", createA, teacher, first.replace("\"max_attempts\":3", "\"max_attempts\":3,\"max_attempts\":3")));
        status(413, send("POST", createA, teacher, "{\"x\":\"" + "a".repeat(1_050_000) + "\"}"));

        JsonNode madeA = ok(send("POST", createA, teacher, first));
        assertThat(ok(send("POST", createA, teacher, first))).isEqualTo(madeA);
        status(409, send("POST", createA, teacher, first.replace("Academic-only A", "Changed command")));
        String revisionA = revision(createA, madeA);
        assertThat(ok(send("GET", revisionA, teacher, null)).path("payload")).isEqualTo(pa);
        String co = addCoTeacher(b, classB, fixture.r.from.plusSeconds(12), fixture.r.until);
        var madeB = ok(send("POST", createB, co, createBody(payload(3, "Academic-only B"))));
        String revisionB = revision(createB, madeB);
        ok(send("GET", revisionB, co, null));
        status(403, send("GET", revisionB, teacher, null));
        status(403, send("GET", revisionA, co, null));
        status(403, send("GET", revision(createB, madeA), co, null));
        status(403, send("GET", revision(createA, madeB), teacher, null));
        status(403, send("GET", createA + "/" + madeA.path("evaluationId") + "/revisions/" + madeB.path("revisionId"), teacher, null));
        assertThat(ok(send("POST", revisionB + "/publish", co, command(1))).path("status").asText()).isEqualTo("PUBLISHED");
        String expired = addCoTeacher(b, classB, fixture.r.from.plusSeconds(12), Instant.now().minusSeconds(60));
        status(403, send("POST", createB, expired, createBody(payload(3, "Expired co-teaching"))));

        String legacy = "/api/evaluations/teacher";
        status(404, send("GET", legacy + "/" + madeA.path("evaluationId"), teacher, null));
        assertThat(send("GET", legacy, teacher, null).body()).doesNotContain("Academic-only A");
        String platform = fixture.f.login(fixture.e.c.platform);
        status(404, send("GET", "/api/evaluations/admin/" + madeA.path("evaluationId"), platform, null));
        assertThat(send("GET", "/api/evaluations/admin", platform, null).body()).doesNotContain("Academic-only A");
        assertThat(fixture.count("select count(*) from t11b8_budgets b join t11b8_resources r on r.id=b.resource_id where r.evaluation_id=?",
            madeA.path("evaluationId").asLong())).isZero();

        var session = (SessionPrincipal) fixture.f.app.getBean(AccountSessionService.class).authenticate(teacher).getPrincipal();
        fixture.sql().update("update account_sessions set revoked_at=clock_timestamp() where id=?", session.sessionId());
        status(401, send("GET", revisionA, teacher, null));
        teacher = fixture.f.login(fixture.f.teacher);
        fixture.r.academic.transitionAssignment(fixture.e.responsible, TeachingAssignment.Status.ENDED, Instant.now());
        status(403, send("GET", revisionA, teacher, null));
        status(403, send("POST", createA, teacher, first));
        var delegates = fixture.f.app.getBean(DelegationMaintenanceService.class);
        long coId = fixture.count("select id from t11b6_co_assignments where class_id=? and valid_until>clock_timestamp()", classB);
        delegates.transitionCoTeacher(coId, TeachingAssignment.Status.ENDED, Instant.now());
        status(403, send("POST", revisionB + "/successor", co, command(2)));
    }

    @Test void grantsSeparateFacultiesAndPublicationContinuesTheApprovedF09Root() throws Exception {
        fixture.h.activation.put(FLAG, true);
        var q = fixture.q;
        long resource = fixture.resource;
        String root = prefix(q.institutionId(), q.classId());
        String first = root + "/" + q.evaluationId() + "/revisions/" + q.revisionId();
        long attempt = fixture.begun();
        long originalHead = currentHead(q.evaluationId());
        String original = state("t11b8_deliveries", "t11b8_budgets", fixture.ctl + ".attempts");

        // The independent co-teacher can create for the class, without access to this author's revision.
        String delegate = addCoTeacher(q.institutionId(), q.classId(), fixture.r.from.plusSeconds(12), fixture.r.until);
        long member = fixture.count("select membership_id from t11b6_co_assignments where class_id=?", q.classId());
        long admin = fixture.count("select membership_id from membership_role_grants where id=?", fixture.e.c.foreignSchoolGrant);
        assertThatThrownBy(() -> grant(resource, fixture.r.foreign, member, admin, "EDIT_DRAFT", Instant.now().minusSeconds(9),
            Instant.now().plusSeconds(500))).isInstanceOf(DataAccessException.class);
        status(403, send("POST", first + "/successor", delegate, command(2)));
        grant(resource, q.institutionId(), member, admin, "ASSIGN", Instant.now().minusSeconds(8), Instant.now().plusSeconds(800));
        status(403, send("POST", first + "/successor", delegate, command(2)));
        grant(resource, q.institutionId(), member, admin, "EDIT_DRAFT", Instant.now().minusSeconds(15), Instant.now().minusSeconds(10));
        status(403, send("POST", first + "/successor", delegate, command(2)));
        long edit = grant(resource, q.institutionId(), member, admin, "EDIT_DRAFT", Instant.now().minusSeconds(1), Instant.now().plusSeconds(900));
        String successorRequest = command(2);
        var successor = ok(send("POST", first + "/successor", delegate, successorRequest));
        assertThat(ok(send("POST", first + "/successor", delegate, successorRequest))).isEqualTo(successor);
        status(409, send("POST", first + "/successor", delegate, command(2)));
        assertThat(state("t11b8_deliveries", "t11b8_budgets", fixture.ctl + ".attempts")).isEqualTo(original);
        assertThat(currentHead(q.evaluationId())).isEqualTo(originalHead);
        assertThat(fixture.own(q, attempt).path("attemptNumber").asInt()).isOne();

        String draft = revision(root, successor);
        assertThat(successor.path("parentRevisionId").asLong()).isEqualTo(q.revisionId());
        status(403, send("POST", draft + "/publish", delegate, command(1)));
        var edited = ok(send("PUT", draft, delegate, editBody(1, payload(3, "Second publication"))));
        assertThat(edited.path("version").asLong()).isEqualTo(2);
        status(409, send("PUT", draft, delegate, editBody(1, payload(3, "Stale"))));
        status(409, send("PUT", draft, delegate, editBody(2, payload(4, "No extra opportunities"))));
        revoke(edit, admin);
        status(403, send("GET", draft, delegate, null));
        status(403, send("POST", first + "/successor", delegate, successorRequest));
        grant(resource, q.institutionId(), member, admin, "PUBLISH", Instant.now().minusSeconds(1), Instant.now().plusSeconds(900));
        status(403, send("GET", draft, delegate, null));
        String publish = command(2);
        var published = ok(send("POST", draft + "/publish", delegate, publish));
        assertThat(ok(send("POST", draft + "/publish", delegate, publish))).isEqualTo(published);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        status(409, send("PUT", draft, fixture.h.teacher, editBody(3, payload(3, "Immutable"))));
        status(403, send("GET", draft, fixture.h.teacher, null));
        long role = fixture.count("select id from membership_role_grants where membership_id=? and role='DOCENTE'", member);
        fixture.r.institutions.revokeRole(role, Instant.now());
        status(403, send("POST", draft + "/publish", delegate, publish));
        assertThat(fixture.count("select author_account_id from t11b8_resources where id=?", resource))
            .isEqualTo(fixture.f.teacher.getId());
        assertThat(currentHead(q.evaluationId())).isEqualTo(originalHead);

        // F writes as well as reads must retain access under the original approved delivery.
        fixture.save(q, attempt, fixture.op(), 17, null, "Existing attempt after the successor publication");
        fixture.submit(q, attempt, fixture.op(), List.of(fixture.answer(q, 18, 31L, null)));
        assertThat(fixture.own(q, attempt).path("attemptNumber").asInt()).isOne();
        fixture.revisions.nextDraft(published.path("revisionId").asLong(), fixture.op());
        fixture.denied(fixture.send("GET", fixture.h.pupil, q, "/own-attempts/" + attempt, null));
        status(403, send("POST", draft + "/successor", fixture.h.teacher, command(3)));
        var next = fixture.deliver(resource, published.path("revisionId").asLong(), q.institutionId(), fixture.r.otherClass,
            fixture.r.otherEpisode, Instant.now().minusSeconds(1), Instant.now().plusSeconds(800));
        fixture.resolve(q, null);
        assertThat(fixture.begin(next, fixture.op()).path("attemptNumber").asInt()).isEqualTo(2);
        assertThat(fixture.count("select max_attempts from t11b8_resources where id=?", resource)).isEqualTo(3);
        fixture.resolve(q, "UNRESOLVED");
        status(403, send("POST", draft + "/successor", fixture.h.teacher, command(3)));
        fixture.denied(fixture.send("GET", fixture.h.pupil, q, "/own-attempts/" + attempt, null));
    }

    @Test void concurrentCommandsAtomicFailuresAndRecoveryKeepTheSameReceipt() throws Exception {
        fixture.h.activation.put(FLAG, true);
        String root = prefix(fixture.r.institution, fixture.r.classId);
        String actor = fixture.h.teacher;
        String create = createBody(payload(3, "Durable authoring"));
        var threads = Executors.newFixedThreadPool(2);
        JsonNode made;
        try {
            var left = threads.submit(() -> send("POST", root, actor, create));
            var right = threads.submit(() -> send("POST", root, actor, create));
            var a = left.get(70, TimeUnit.SECONDS);
            var b = right.get(70, TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(), b.statusCode())).contains(200).allMatch(code -> code == 200 || code == 503);
            made = ok(retryAfterContention(a, "POST", root, actor, create));
            assertThat(ok(retryAfterContention(b, "POST", root, actor, create))).isEqualTo(made);
            assertThat(fixture.count("select count(*) from " + control + ".receipts where evaluation_id=? and action='CREATE'",
                made.path("evaluationId").asLong())).isOne();
        } finally {
            threads.shutdownNow();
        }
        assertThatThrownBy(() -> fixture.sql().update("insert into " + control + ".receipts(operation_id) values(?)", fixture.op()))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("T12-G generated history only");
        String draft = revision(root, made);
        var versions = Executors.newFixedThreadPool(2);
        try {
            String first = editBody(1, payload(3, "Competing edit A"));
            String second = editBody(1, payload(3, "Competing edit B"));
            var left = versions.submit(() -> send("PUT", draft, actor, first));
            var right = versions.submit(() -> send("PUT", draft, actor, second));
            var a = left.get(70, TimeUnit.SECONDS);
            var b = right.get(70, TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(), b.statusCode())).contains(200);
            assertThat(List.of(retryAfterContention(a, "PUT", draft, actor, first).statusCode(),
                retryAfterContention(b, "PUT", draft, actor, second).statusCode()))
                .containsExactlyInAnyOrder(200, 409);
        } finally {
            versions.shutdownNow();
        }

        String[] aggregate = {"evaluations", "t11b8_creations", "t11b8_resources", "t11b8_revisions", "t11b8_events",
            "t11b9_counters", control + ".receipts"};
        String before = state(aggregate);
        long successes = fixture.count("select count(*) from system_logs where action like '%_NEW_AUTHORING' and outcome='SUCCESS'");
        fixture.sql().execute("""
            create function fixture_g_audit_failure() returns trigger language plpgsql as $$
            begin if NEW.action='PUBLISH_NEW_AUTHORING' then raise exception 'fixture audit'; end if; return NEW; end $$;
            create trigger fixture_g_audit_failure before insert on system_logs for each row execute function fixture_g_audit_failure()
            """);
        String publish = command(2);
        status(503, send("POST", draft + "/publish", actor, publish));
        fixture.sql().execute("drop trigger fixture_g_audit_failure on system_logs; drop function fixture_g_audit_failure()");
        assertThat(state(aggregate)).isEqualTo(before);
        assertThat(fixture.count("select count(*) from system_logs where action like '%_NEW_AUTHORING' and outcome='SUCCESS'")).isEqualTo(successes);

        long institution = fixture.r.institution;
        var old = fixture.sql().queryForMap(
            "select maximum_rows,maximum_bytes from t11b9_limits where institution_id=? and category='ORIGINAL'", institution);
        fixture.e.c.b.service.limit(institution,"ORIGINAL",1,1,fixture.op());
        String quota = createBody(payload(3, "Quota rollback"));
        status(503, send("POST", root, actor, quota));
        assertThat(state(aggregate)).isEqualTo(before);
        assertThat(fixture.count("select count(*) from system_logs where action like '%_NEW_AUTHORING' and outcome='SUCCESS'")).isEqualTo(successes);
        fixture.e.c.b.service.limit(institution, "ORIGINAL", ((Number)old.get("maximum_rows")).longValue(),
            ((Number)old.get("maximum_bytes")).longValue(), fixture.op());
        var committed = ok(send("POST", draft + "/publish", actor, publish));
        assertThat(ok(send("POST", draft + "/publish", actor, publish))).isEqualTo(committed);

        String fExpansion = fixture.r.script("t12f-v1");
        String fingerprint = fixture.sql().queryForObject("select fingerprint from " + control + ".schema_version", String.class);
        fixture.sql().execute(fExpansion);
        fixture.sql().execute(expansion);
        assertThat(fixture.sql().queryForObject("select fingerprint from " + control + ".schema_version", String.class)).isEqualTo(fingerprint);
        var tx = new TransactionTemplate(fixture.f.app.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s -> {
            fixture.sql().execute("alter table " + control + ".receipts disable trigger immutable");
            assertThatThrownBy(() -> fixture.sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");
            s.setRollbackOnly();
        });
        restore(fExpansion, draft, actor, publish, committed);
    }

    private void restore(String predecessor, String draft, String actor, String publish, JsonNode committed) throws Exception {
        String url = fixture.f.setting("spring.datasource.url");
        String port = url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        String owner = fixture.f.setting("spring.datasource.username");
        var dir = Files.createDirectories(Path.of("target/t12g-recovery",fixture.f.schema)).toAbsolutePath();
        var args = List.of("-h","127.0.0.1","-p",port,"-U",owner);
        fixture.sql().update("update authentication_limits set expires_at=greatest(expires_at,floor(extract(epoch from clock_timestamp())*1000)::bigint+3600000)");
        var signatures = new TreeMap<String,String>();
        String suffix = fixture.f.schema.substring(4);
        for (String schema : List.of(fixture.f.schema, "t06_" + suffix, "t12c_" + suffix, "t12d_" + suffix,
                "t12e_" + suffix, fixture.ctl, control))
            for (String table : fixture.sql().queryForList("select tablename from pg_tables where schemaname=? order by tablename", String.class, schema))
                signatures.put(schema + "." + table, fixture.sql().queryForObject(fixture.r.signature(schema + "." + table), String.class));
        var copy = dir.resolve("copy");
        fixture.r.nativeTool("pg_basebackup", args, List.of("-D",copy.toString(),"--wal-method=stream","--checkpoint=fast"), dir);
        fixture.r.nativeTool("pg_verifybackup", List.of(), List.of(copy.toString()), dir);
        int copyPort = TransitionWalRecoveryFixture.port();
        Files.writeString(copy.resolve("postgresql.auto.conf"), "listen_addresses='127.0.0.1'\nport=" + copyPort
            + "\nmax_connections=20\nmax_locks_per_transaction=512\nshared_buffers='16MB'\n");
        String copyUrl = "jdbc:postgresql://127.0.0.1:" + copyPort + "/chemicallab_t01_test";
        boolean started = false;
        String origin = fixture.f.origin;
        ConfigurableApplicationContext copyApp = null;
        try {
            fixture.r.nativeTool("pg_ctl", List.of(), List.of("-D",copy.toString(),"-l",dir.resolve("copy-postgres.log").toString(),"-w","start"), dir);
            started = true;
            try (var connection = DriverManager.getConnection(copyUrl,
                    owner, fixture.f.setting("spring.datasource.password")); var statement = connection.createStatement()) {
                statement.execute("set search_path to " + fixture.f.schema + ",public");
                statement.execute(predecessor);
                statement.execute(expansion);
                var restored = new TreeMap<String,String>();
                for (String table : signatures.keySet()) {
                    try (var rows = statement.executeQuery(fixture.r.signature(table))) {
                        rows.next();
                        restored.put(table, rows.getString(1));
                    }
                }
                assertThat(restored).isEqualTo(signatures);
                Files.writeString(dir.resolve("signatures.json"), fixture.f.json.writeValueAsString(Map.of("original",signatures,"restored",restored)));
            }
            copyApp = fixture.f.startApp("--spring.datasource.url=" + copyUrl + "?currentSchema=" + fixture.f.schema,
                "--spring.jpa.hibernate.ddl-auto=none", "--app.evaluations.institutional.enabled=true",
                "--" + FLAG + "=true", "--app.evaluations.institutional.fixture-schema=" + fixture.f.schema);
            fixture.f.origin = fixture.f.origin(copyApp);
            assertThat(ok(send("POST", draft + "/publish", actor, publish))).isEqualTo(committed);
        } finally {
            fixture.f.origin = origin;
            if (copyApp != null) copyApp.close();
            if (started) fixture.r.nativeTool("pg_ctl",List.of(),List.of("-D",copy.toString(),"-m","fast","-w","stop"),dir);
        }
    }

    private String addCoTeacher(long institution, long clazz, Instant from, Instant until) throws Exception {
        var account = fixture.f.account(Role.DOCENTE, null, "3", "A");
        long member = fixture.r.membership(institution, account.getId(), DOCENTE);
        var delegates = fixture.f.app.getBean(DelegationMaintenanceService.class);
        var planned = delegates.planCoTeacher(institution, clazz, member, from, until, from.minusSeconds(1));
        delegates.transitionCoTeacher(planned.id(), TeachingAssignment.Status.ACTIVE, from);
        return fixture.f.login(account);
    }

    private long grant(long resource, long institution, long member, long admin, String faculty, Instant from, Instant until) {
        return fixture.count("""
            insert into %s.grants(resource_id,institution_id,membership_id,faculty,valid_from,valid_until,granted_at,
                granted_by,grantor_membership_id,reason)
            values(?,?,?,?,?,?,?,?,?,'DESIGNATED') returning id
            """.formatted(control), resource,institution,member,faculty,Timestamp.from(from),Timestamp.from(until),
            Timestamp.from(from.minusSeconds(1)),fixture.e.c.school.getId(),admin);
    }

    private void revoke(long id, long admin) {
        fixture.sql().update("""
            update %s.grants set status='REVOKED',revoked_at=clock_timestamp(),revoked_by=?,revoker_membership_id=?,reason='REMOVED'
            where id=?
            """.formatted(control),fixture.e.c.school.getId(),admin,id);
    }

    private long currentHead(long evaluation) {
        return fixture.count("""
            select v.id from t11b10_versions v where source_table='evaluations' and source_pk=jsonb_build_object('id',?::bigint)
            and not exists(select 1 from t11b10_versions n where n.predecessor_id=v.id)
            """,evaluation);
    }

    private String state(String... tables) {
        var check = new TreeMap<String,String>();
        for (String table : tables) check.put(table,fixture.sql().queryForObject(fixture.r.signature(table),String.class));
        return check.toString();
    }

    private ObjectNode payload(int maximum, String title) throws Exception {
        var payload = (ObjectNode) fixture.f.json.readTree(fixture.payload(maximum,"ALL_AT_ONCE",false,0));
        ((ObjectNode) payload.get("resource")).put("title", title);
        return payload;
    }

    private String createBody(JsonNode payload) {
        return fixture.f.json.createObjectNode().put("operationId", UUID.randomUUID().toString()).set("payload", payload).toString();
    }

    private String editBody(long version, JsonNode payload) {
        return fixture.f.json.createObjectNode().put("operationId", UUID.randomUUID().toString()).put("expectedVersion", version)
            .set("payload", payload).toString();
    }

    private String command(long version) {
        return fixture.f.json.createObjectNode().put("operationId", UUID.randomUUID().toString()).put("expectedVersion", version).toString();
    }

    private static String prefix(long institution, long clazz) {
        return "/api/institutions/" + institution + "/classes/" + clazz + "/evaluation-authoring/NEW/v1/evaluations";
    }

    private static String revision(String root, JsonNode receipt) {
        return root + "/" + receipt.path("evaluationId") + "/revisions/" + receipt.path("revisionId");
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(fixture.f.origin + path))
            .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return fixture.f.http.send(request.method(method,
            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> retryAfterContention(HttpResponse<String> response, String method, String path,
            String token, String sameBody) throws Exception {
        return response.statusCode() == 503 ? send(method, path, token, sameBody) : response;
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        status(200,response);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return fixture.f.json.readTree(response.body());
    }

    private static void status(int expected, HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }
}

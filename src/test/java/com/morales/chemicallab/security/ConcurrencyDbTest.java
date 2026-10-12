package com.morales.chemicallab.security;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.files.FileFixtures;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** HTTP on two independent backend JVMs, independent DB transactions, observed SQL barriers. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyDbTest {
    final WhiteboardSecurityTransportDbTest f = new WhiteboardSecurityTransportDbTest();
    final boolean baseline = Boolean.getBoolean("t10.baseline");
    final List<Process> children = new ArrayList<>();
    final List<Path> stops = new ArrayList<>();
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    final List<Map<String,Object>> evidence = new ArrayList<>();
    String one, two, teacher, pupil, peer;
    long exam, question, right, wrong, concept;

    @BeforeAll void start() throws Exception {
        try { f.start(); one = server(1); two = server(2); }
        catch (Exception | AssertionError ex) { cleanup(); throw ex; }
    }
    String server(int number) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "t10-processes", f.schema));
        Path ready = directory.resolve(number + ".ready"), stop = directory.resolve(number + ".stop");
        stops.add(stop);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = List.of(javaExecutable,
                "-Xmx384m", "-Dspring.devtools.restart.enabled=false", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ConcurrencyServer.class.getName(), f.setting("spring.datasource.url"), f.setting("spring.datasource.username"),
                f.setting("spring.datasource.password"), f.schema, Base64.getEncoder().encodeToString(f.key),
                ready.toAbsolutePath().toString(), stop.toAbsolutePath().toString());
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(number + ".log").toFile()).start();
        children.add(child);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < end) Thread.sleep(50);
        assertThat(Files.exists(ready)).as("Child JVM ready, see %s", directory).isTrue();
        System.out.println("T10 backend process " + number + " pid=" + child.pid());
        evidence.add(Map.of("backendProcess", number, "pid", child.pid(), "baseline", baseline));
        return Files.readString(ready);
    }
    @AfterAll void cleanup() throws Exception {
        pool.shutdownNow();
        for (Path stop : stops) Files.writeString(stop, "stop");
        for (Process child : children) {
            if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
        try { f.cleanup(); } finally {
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target", "t10-results.json"), f.json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }
    @BeforeEach void fixture() throws Exception {
        f.fixture(); teacher = f.login(f.teacher); pupil = f.login(f.student); peer = f.login(f.peerStudent);
        var s = f.app.getBean(EvaluationService.class);
        exam = s.createEvaluation(f.teacher.getUsername(), new CreateEvaluationRequest("Synthetic T10", null, null, "Synthetic", 2, 30,
                false, false, QuestionDisplayMode.ALL_AT_ONCE, false, true)).id();
        question = s.addQuestion(f.teacher.getUsername(), exam, new CreateQuestionRequest("Synthetic question", QuestionType.MULTIPLE_CHOICE,
                4, 0, "Reserved explanation", null, true, List.of(new CreateOptionRequest("Right", true, 0), new CreateOptionRequest("Wrong", false, 1)))).id();
        var options = f.app.getBean(EvaluationOptionRepository.class).findAll().stream().filter(o -> o.getQuestion().getId().equals(question)).toList();
        right = options.stream().filter(EvaluationOption::getCorrect).findFirst().orElseThrow().getId();
        wrong = options.stream().filter(o -> !o.getCorrect()).findFirst().orElseThrow().getId();
        s.publishEvaluation(f.teacher.getUsername(), exam);
        var now = LocalDateTime.now(ZoneId.of("America/Lima"));
        s.assignEvaluationToSection(f.teacher.getUsername(), exam, new AssignEvaluationRequest("3", "A", now.minusHours(1), now.plusHours(2)));
        concept = f.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder().title("Synthetic content").category("Synthetic")
                .createdByTeacher(f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow()).build()).getId();
    }
    HttpResponse<String> request(String origin, String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        return f.http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> upload(String origin, long id, byte[] bytes, String token, String part, String suffix) throws Exception {
        String boundary = "T10SyntheticBoundary";
        var out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part + "\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var req = HttpRequest.newBuilder(URI.create(origin + suffix.replace("{id}", "" + id))).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return f.http.send(req, HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> file(String origin, byte[] bytes) throws Exception { return upload(origin, concept, bytes, teacher, "file", "/api/concepts/teacher/{id}/materials/file"); }
    byte[] differentPng() throws Exception {
        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) image.setRGB(x, y, 0xffffff);
        var out = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", out); return out.toByteArray();
    }
    HttpResponse<String> join(String origin, String token) throws Exception { return request(origin, "POST", "/api/whiteboards/student/" + f.board + "/join", token, "{}"); }
    HttpResponse<String> begin(String origin, String token) throws Exception { return request(origin, "POST", "/api/evaluations/student/" + exam + "/attempts", token, "{}"); }
    long id(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 201);
        var node = f.json.readTree(r.body()); long id = node.path(node.has("id") ? "id" : "materialId").asLong();
        assertThat(id).as(r.body()).isPositive(); return id;
    }
    String answers(long option) { return "{\"questionId\":" + question + ",\"selectedOptionId\":" + option + "}"; }
    String attemptPath(long id, String suffix) { return "/api/evaluations/student/attempts/" + id + suffix; }
    int count(String sql, Object... args) { return f.sql().queryForObject(sql, Integer.class, args); }
    long used(String resource) { return f.sql().queryForObject("select coalesce((select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource=?),0)", Long.class, f.teacher.getId(), resource); }
    void clearAttempts() {
        f.sql().update("delete from evaluation_answers where attempt_id in (select id from evaluation_attempts where evaluation_id=?)", exam);
        f.sql().update("delete from evaluation_attempt_events where attempt_id in (select id from evaluation_attempts where evaluation_id=?)", exam);
        f.sql().update("delete from evaluation_attempts where evaluation_id=?", exam);
    }
    void materialsConsistent() {
        assertThat(used("MATERIAL_COUNT")).isEqualTo(count("select count(*) from concept_materials m join concept_contents c on c.id=m.concept_content_id join teacher_profiles t on t.id=c.created_by_teacher_id where t.user_id=?", f.teacher.getId()));
        assertThat(used("MATERIAL_BYTES")).isEqualTo(f.sql().queryForObject("select coalesce(sum(octet_length(file_data)),0) from concept_materials where concept_content_id=?", Long.class, concept));
        assertThat(count("select count(*) from concept_materials where concept_content_id=? and file_size<>octet_length(file_data)", concept)).isZero();
    }
    Connection connection() throws Exception {
        var c = DriverManager.getConnection(f.setting("spring.datasource.url") + "?currentSchema=" + f.schema,
                f.setting("spring.datasource.username"), f.setting("spring.datasource.password"));
        c.setAutoCommit(false); return c;
    }
    // Barrier succeeds only when distinct PostgreSQL sessions are actually waiting for a lock.
    void waiters(int minimum) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
        int seen;
        do {
            seen = count("select count(distinct pid) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like ?", "%" + f.schema + "%");
            if (seen >= minimum) { System.out.println("T10 observed independent PostgreSQL lock waiters=" + seen); return; }
            Thread.sleep(20);
        } while (System.nanoTime() < end);
        throw new AssertionError("SQL barrier not reached: " + seen + " < " + minimum);
    }
    void lock(Connection c, String table, long id) throws Exception {
        try (var st = c.prepareStatement("select id from " + table + " where id=? for update")) { st.setLong(1, id); st.execute(); }
    }
    void gate(String table, String operations) {
        f.sql().execute("create or replace function aaa_t10_gate() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock_shared(710010); return NEW; end $$");
        f.sql().execute("create trigger aaa_t10_gate before " + operations + " on " + table + " for each row execute function aaa_t10_gate()");
    }
    void ungate(String table) { f.sql().execute("drop trigger if exists aaa_t10_gate on " + table); }
    List<HttpResponse<String>> race(String table, long resource, String operation, Callable<HttpResponse<String>> a, Callable<HttpResponse<String>> b) throws Exception {
        try (var c = connection()) {
            if (baseline && operation.equals("insert")) {
                gate(table, "insert"); try (var st = c.createStatement()) { st.execute("select pg_advisory_xact_lock(710010)"); }
            } else lock(c, table, resource);
            var go = new CountDownLatch(1);
            var first = pool.submit(() -> { go.await(5, TimeUnit.SECONDS); return a.call(); });
            var second = pool.submit(() -> { go.await(5, TimeUnit.SECONDS); return b.call(); });
            go.countDown();
            try { waiters(2); } finally { c.rollback(); }
            var result = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            evidence.add(Map.of("scenario", operation + ":" + table, "baseline", baseline, "statuses", result.stream().map(HttpResponse::statusCode).toList(), "persistence", snapshot()));
            System.out.println("T10 " + operation + ":" + table + " statuses=" + result.stream().map(HttpResponse::statusCode).toList());
            return result;
        } finally { if (baseline && operation.equals("insert")) ungate(table); }
    }
    Map<String,Object> snapshot() {
        var state = new LinkedHashMap<String,Object>();
        state.put("attempts", f.sql().queryForList("select id,student_id,attempt_number,status,score,grade_closed from evaluation_attempts where evaluation_id=? order by id", exam));
        state.put("answers", f.sql().queryForList("select a.attempt_id,a.question_id,a.selected_option_id,a.correct,a.points_awarded from evaluation_answers a join evaluation_attempts t on t.id=a.attempt_id where t.evaluation_id=? order by a.id", exam));
        state.put("lifecycle", f.sql().queryForList("select event_type,count(*) as rows from evaluation_attempt_events where attempt_id in (select id from evaluation_attempts where evaluation_id=?) group by event_type order by event_type", exam));
        state.put("materials", f.sql().queryForList("select id,concept_content_id,type,active,content_type,file_size,octet_length(file_data) as actual_bytes,md5(file_data) as content_fingerprint from concept_materials where concept_content_id=? order by id", concept));
        state.put("quotas", f.sql().queryForList("select resource,used from storage_usage where scope='ACCOUNT' and owner_id=? order by resource", f.teacher.getId()));
        state.put("board", f.sql().queryForList("select status from whiteboard_sessions where id=?", f.board));
        state.put("participants", f.sql().queryForList("select student_id,interaction_override from whiteboard_participants where session_id=? order by student_id", f.board));
        return state;
    }

    @Test void finalSubmissionAndExitCannotDuplicateFinalization() throws Exception {
        long attempt = id(begin(one, pupil));
        assertThat(request(one, "POST", attemptPath(attempt, "/answers"), pupil, answers(right)).statusCode()).isEqualTo(200);
        var r = race("evaluations", exam, "submit-exit", () -> request(one, "POST", attemptPath(attempt, "/submit"), pupil, "{}"),
                () -> request(two, "POST", attemptPath(attempt, "/exit"), pupil, "{}"));
        assertThat(r.stream().map(HttpResponse::statusCode)).contains(200);
        assertThat(r.stream().map(HttpResponse::statusCode)).allMatch(s -> s == 200 || s == 400);
        assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type in ('ATTEMPT_SUBMITTED','ATTEMPT_EXITED')", attempt)).isEqualTo(1);
        assertThat(count("select count(*) from system_logs where target_id=? and event_type='EVALUATION_ATTEMPT_SUBMITTED' and outcome='SUCCESS'", exam)).isEqualTo(1);
        assertThat(f.sql().queryForObject("select score from evaluation_attempts where id=?", Integer.class, attempt)).isEqualTo(4);
        assertThat(count("select count(*) from evaluation_answers where attempt_id=?", attempt)).isEqualTo(1);
    }

    @Test void saveAfterCompetingSubmitIsRejectedAndSequentialReplayCannotAdvanceTwice() throws Exception {
        long attempt = id(begin(one, pupil));
        try (var c = connection()) {
            lock(c, "evaluations", exam);
            var submission = pool.submit(() -> request(one, "POST", attemptPath(attempt, "/submit"), pupil, "{\"answers\":[" + answers(right) + "]}"));
            waiters(1);
            var save = pool.submit(() -> request(two, "POST", attemptPath(attempt, "/answers"), pupil, answers(wrong)));
            waiters(2); c.rollback();
            assertThat(submission.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(save.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(400);
            assertThat(f.sql().queryForObject("select selected_option_id from evaluation_answers where attempt_id=?", Long.class, attempt)).isEqualTo(right);
        }
        clearAttempts();
        f.sql().update("update evaluations set question_display_mode='ONE_BY_ONE' where id=?", exam);
        long sequential = id(begin(one, pupil));
        var r = race("evaluations", exam, "sequential-replay", () -> request(one, "POST", attemptPath(sequential, "/answers"), pupil, answers(right)),
                () -> request(two, "POST", attemptPath(sequential, "/answers"), pupil, answers(wrong)));
        assertThat(r.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 400);
        assertThat(count("select count(*) from evaluation_answers where attempt_id=?", sequential)).isEqualTo(1);
        assertThat(f.sql().queryForObject("select current_question_index from evaluation_attempts where id=?", Integer.class, sequential)).isEqualTo(1);
    }
    @Test void simultaneousStartAndLastAttemptAcrossProcesses() throws Exception {
        for (int round = 0; round < 3; round++) {
            if (round > 0) clearAttempts();
            var r = race("evaluations", exam, "start", () -> begin(one, pupil), () -> begin(two, pupil));
            assertThat(r.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 400);
            assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?", exam)).isEqualTo(1);
            long attempt = id(r.stream().filter(x -> x.statusCode() == 201).findFirst().orElseThrow());
            assertThat(request(one, "POST", attemptPath(attempt, "/submit"), pupil, "{}").statusCode()).isEqualTo(200);
            var last = race("evaluations", exam, "last-attempt", () -> begin(one, pupil), () -> begin(two, pupil));
            assertThat(last.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 400);
            assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?", exam)).isEqualTo(2);
            assertThat(count("select count(*) from evaluation_attempts where evaluation_id=? and status='IN_PROGRESS'", exam)).isEqualTo(1);
            assertThat(count("select count(distinct attempt_number) from evaluation_attempts where evaluation_id=?", exam)).isEqualTo(2);
            assertThat(begin(one, pupil).statusCode()).isEqualTo(400);
            long lastId = id(last.stream().filter(x -> x.statusCode() == 201).findFirst().orElseThrow());
            assertThat(request(two, "POST", attemptPath(lastId, "/submit"), pupil, "{}").statusCode()).isEqualTo(200);
            assertThat(begin(two, pupil).statusCode()).isEqualTo(400);
            assertThat(count("select count(*) from evaluation_attempts where evaluation_id=?", exam)).isEqualTo(2);
        }
    }
    @Test void differentStudentsMayStartOnSharedEvaluation() throws Exception {
        var r = race("evaluations", exam, "different-users", () -> begin(one, pupil), () -> begin(two, peer));
        assertThat(r.stream().map(HttpResponse::statusCode)).containsOnly(201);
        assertThat(count("select count(*) from evaluation_attempts where evaluation_id=? and status='IN_PROGRESS'", exam)).isEqualTo(2);
    }
    @Test void saveSubmitAndRepeatedSubmitHaveOnePersistedLifecycle() throws Exception {
        for (int round = 0; round < 3; round++) {
            clearAttempts();
            long attempt = id(begin(one, pupil));
            var r = race("evaluations", exam, "save-submit", () -> request(one, "POST", attemptPath(attempt, "/answers"), pupil, answers(wrong)),
                    () -> request(two, "POST", attemptPath(attempt, "/submit"), pupil, "{\"answers\":[" + answers(right) + "]}"));
            assertThat(r.get(1).statusCode()).isEqualTo(200);
            assertThat(r.get(0).statusCode()).isIn(200, 400);
            assertThat(count("select count(*) from evaluation_answers where attempt_id=?", attempt)).isEqualTo(1);
            assertThat(f.sql().queryForObject("select selected_option_id from evaluation_answers where attempt_id=?", Long.class, attempt)).isEqualTo(right);
            assertThat(f.sql().queryForObject("select score from evaluation_attempts where id=?", Integer.class, attempt)).isEqualTo(4);
            assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'", attempt)).isEqualTo(1);
            assertThat(request(one, "POST", attemptPath(attempt, "/submit"), pupil, "{}").statusCode()).isEqualTo(400);
            assertThat(f.json.readTree(r.get(1).body()).path("score").isNull()).isTrue();
        }
        clearAttempts();
        int previous = count("select count(*) from system_logs where target_id=? and event_type='EVALUATION_ATTEMPT_SUBMITTED' and outcome='SUCCESS'", exam);
        long attempt = id(begin(one, pupil));
        var r = race("evaluations", exam, "submit-submit", () -> request(one, "POST", attemptPath(attempt, "/submit"), pupil, "{}"),
                () -> request(two, "POST", attemptPath(attempt, "/submit"), pupil, "{}"));
        assertThat(r.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 400);
        assertThat(count("select count(*) from evaluation_attempt_events where attempt_id=? and event_type='ATTEMPT_SUBMITTED'", attempt)).isEqualTo(1);
        assertThat(count("select count(*) from system_logs where target_id=? and event_type='EVALUATION_ATTEMPT_SUBMITTED' and outcome='SUCCESS'", exam)).isEqualTo(previous + 1);
    }
    @Test void duplicateJoinHasOneParticipantAndControlledSuccess() throws Exception {
        for (int round = 0; round < 3; round++) {
            f.sql().update("delete from whiteboard_participants where session_id=?", f.board);
            String table = baseline ? "whiteboard_participants" : "whiteboard_sessions";
            var r = race(table, f.board, "insert", () -> join(one, pupil), () -> join(two, pupil));
            if (baseline) assertThat(r.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 500);
            else assertThat(r.stream().map(HttpResponse::statusCode)).containsOnly(200);
            assertThat(count("select count(*) from whiteboard_participants where session_id=?", f.board)).isEqualTo(1);
        }
        if (!baseline) {
            var r = race("whiteboard_sessions", f.board, "join-different-users", () -> join(one, pupil), () -> join(two, peer));
            assertThat(r.stream().map(HttpResponse::statusCode)).containsOnly(200);
            assertThat(count("select count(*) from whiteboard_participants where session_id=?", f.board)).isEqualTo(2);
        }
    }
    @Test void simultaneousFileCreationKeepsOnlyOneActiveFile() throws Exception {
        for (int round = 0; round < 3; round++) {
            f.sql().update("delete from concept_materials where concept_content_id=?", concept);
            String table = baseline ? "concept_materials" : "concept_contents";
            var r = race(table, concept, "insert", () -> file(one, FileFixtures.png()), () -> file(two, FileFixtures.png()));
            assertThat(r.stream().map(HttpResponse::statusCode)).containsOnly(201);
            assertThat(count("select count(*) from concept_materials where concept_content_id=? and type='FILE' and active", concept)).isEqualTo(baseline ? 2 : 1);
            materialsConsistent();
            if (!baseline) {
                assertThat(id(r.get(0))).isEqualTo(id(r.get(1)));
                assertThat(count("select count(*) from system_logs where target_id=? and event_type='CONCEPT_MATERIAL_ADDED' and outcome='SUCCESS'", concept)).isEqualTo(round + 1);
                assertThat(count("select count(*) from system_logs where target_id=? and event_type='CONCEPT_MATERIAL_REPLACED' and outcome='SUCCESS'", concept)).isEqualTo(round + 1);
            }
        }
    }
    @Test void closeCompetesWithFirstJoin() throws Exception {
        f.sql().update("delete from whiteboard_participants where session_id=?", f.board);
        try (var c = connection()) {
            if (baseline) {
                gate("whiteboard_participants", "insert");
                try (var st = c.createStatement()) { st.execute("select pg_advisory_xact_lock(710010)"); }
                var joining = pool.submit(() -> join(one, pupil));
                waiters(1);
                var closed = upload(two, f.board, FileFixtures.png(), teacher, "snapshot", "/api/whiteboards/teacher/{id}/close");
                assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
                c.rollback();
                var joined = joining.get(30, TimeUnit.SECONDS);
                assertThat(joined.statusCode()).isEqualTo(200);
                assertThat(count("select count(*) from whiteboard_participants where session_id=?", f.board)).isEqualTo(1);
                evidence.add(Map.of("scenario", "join-after-close", "baseline", true, "statuses", List.of(200, 200), "participants", 1));
            } else {
                lock(c, "whiteboard_sessions", f.board);
                var closing = pool.submit(() -> upload(one, f.board, FileFixtures.png(), teacher, "snapshot", "/api/whiteboards/teacher/{id}/close"));
                waiters(1);
                var joining = pool.submit(() -> join(two, pupil));
                waiters(2); c.rollback();
                assertThat(closing.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThat(joining.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(400);
                assertThat(count("select count(*) from whiteboard_participants where session_id=?", f.board)).isZero();
                evidence.add(Map.of("scenario", "join-after-close", "baseline", false, "statuses", List.of(200, 400), "participants", 0));
            }
            assertThat(f.sql().queryForObject("select status from whiteboard_sessions where id=?", String.class, f.board)).isEqualTo("CLOSED");
        } finally { if (baseline) ungate("whiteboard_participants"); }
    }
    @Test void rejoinCannotOverwriteTeacherPermission() throws Exception {
        long participant = f.sql().queryForObject("select id from whiteboard_participants where session_id=? and student_id=?", Long.class, f.board, f.studentProfile(f.student));
        try (var c = connection()) {
            lock(c, baseline ? "whiteboard_participants" : "whiteboard_sessions", baseline ? participant : f.board);
            var permission = pool.submit(() -> request(one, "PATCH", "/api/whiteboards/teacher/" + f.board + "/participants/" + f.studentProfile(f.student) + "/interaction", teacher, "{\"interactionOverride\":\"BLOCKED\"}"));
            waiters(1);
            var joining = pool.submit(() -> join(two, pupil));
            waiters(2); c.rollback();
            assertThat(permission.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(joining.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            String actual = f.sql().queryForObject("select interaction_override from whiteboard_participants where id=?", String.class, participant);
            assertThat(actual).isEqualTo(baseline ? "FOLLOW_GLOBAL" : "BLOCKED");
            evidence.add(Map.of("scenario", "permission-rejoin", "baseline", baseline, "persistedOverride", actual));
        }
    }
    @Test void presenceCannotOverwriteTeacherPermission() throws Exception {
        long participant = f.sql().queryForObject("select id from whiteboard_participants where session_id=? and student_id=?", Long.class, f.board, f.studentProfile(f.student));
        String oldOrigin = f.origin;
        f.origin = two;
        try (var c = connection()) {
            var socket = f.connect(pupil);
            lock(c, baseline ? "whiteboard_participants" : "whiteboard_sessions", baseline ? participant : f.board);
            var permission = pool.submit(() -> request(one, "PATCH", "/api/whiteboards/teacher/" + f.board + "/participants/" + f.studentProfile(f.student) + "/interaction", teacher, "{\"interactionOverride\":\"BLOCKED\"}"));
            waiters(1);
            socket.send("/app/whiteboards/" + f.board + "/presence", "");
            waiters(2); c.rollback();
            assertThat(permission.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            // A second join queues behind presence on the same board and gives an HTTP completion fence.
            assertThat(join(two, pupil).statusCode()).isEqualTo(200);
            String actual = f.sql().queryForObject("select interaction_override from whiteboard_participants where id=?", String.class, participant);
            assertThat(actual).isEqualTo(baseline ? "FOLLOW_GLOBAL" : "BLOCKED");
            evidence.add(Map.of("scenario", "permission-presence", "baseline", baseline, "persistedOverride", actual));
        } finally { f.closePeers(); f.origin = oldOrigin; }
    }
    @Test void deleteThenReplaceAndDuplicateDeleteAreControlled() throws Exception {
        long material = id(file(one, FileFixtures.png()));
        String table = baseline ? "concept_materials" : "concept_contents";
        long resource = baseline ? material : concept;
        try (var c = connection()) {
            lock(c, table, resource);
            var deletion = pool.submit(() -> request(one, "DELETE", "/api/concepts/teacher/" + concept + "/materials/" + material, teacher, null));
            waiters(1);
            var replacement = pool.submit(() -> file(two, differentPng()));
            try { waiters(2); } catch (AssertionError ex) {
                if (replacement.isDone()) System.out.println("T10 replacement ended before barrier: " + replacement.get().statusCode() + " " + replacement.get().body());
                throw ex;
            } finally { c.rollback(); }
            assertThat(deletion.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            var replaced = replacement.get(30, TimeUnit.SECONDS);
            assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(baseline ? 503 : 201);
            assertThat(count("select count(*) from concept_materials where concept_content_id=?", concept)).isEqualTo(baseline ? 0 : 1);
            evidence.add(Map.of("scenario", "delete-then-replace", "baseline", baseline, "statuses", List.of(204, replaced.statusCode())));
        }
        materialsConsistent();
        if (!baseline) {
            final long id = f.sql().queryForObject("select id from concept_materials where concept_content_id=?", Long.class, concept);
            var r = race("concept_contents", concept, "delete-delete", () -> request(one, "DELETE", "/api/concepts/teacher/" + concept + "/materials/" + id, teacher, null),
                    () -> request(two, "DELETE", "/api/concepts/teacher/" + concept + "/materials/" + id, teacher, null));
            assertThat(r.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(204, 404);
            materialsConsistent();
        }
    }
    @Test void competingReplacementsKeepWholeValidatedFileAndExactQuota() throws Exception {
        long material = id(file(one, FileFixtures.png()));
        byte[] black = f.app.getBean(com.morales.chemicallab.security.files.FileValidationService.class)
                .material(new org.springframework.mock.web.MockMultipartFile("file", "fixture.png", "image/png", FileFixtures.png())).data();
        byte[] white = f.app.getBean(com.morales.chemicallab.security.files.FileValidationService.class)
                .material(new org.springframework.mock.web.MockMultipartFile("file", "fixture.png", "image/png", differentPng())).data();
        for (int round = 0; round < 3; round++) {
            var r = race(baseline ? "concept_materials" : "concept_contents", baseline ? material : concept, "replace-replace",
                    () -> file(one, FileFixtures.png()), () -> file(two, differentPng()));
            // In baseline one upload can be a clean no-op and never wait; that is not a barrier.
            assertThat(r.stream().map(HttpResponse::statusCode)).containsOnly(201);
            assertThat(id(r.get(0))).isEqualTo(material); assertThat(id(r.get(1))).isEqualTo(material);
            byte[] actual = f.sql().queryForObject("select file_data from concept_materials where id=?", byte[].class, material);
            assertThat(Arrays.equals(actual, black) || Arrays.equals(actual, white)).isTrue();
            assertThat(count("select count(*) from concept_materials where concept_content_id=?", concept)).isEqualTo(1);
            materialsConsistent();
        }
    }
    @Test void invalidConcurrentUploadCannotRemoveValidMaterial() throws Exception {
        long material = id(file(one, FileFixtures.png()));
        byte[] original = f.sql().queryForObject("select file_data from concept_materials where id=?", byte[].class, material);
        int before = count("select count(*) from system_logs where target_id=? and event_type like 'CONCEPT_MATERIAL_%' and outcome='SUCCESS'", concept);
        try (var c = connection()) {
            lock(c, "concept_contents", concept);
            var accepted = pool.submit(() -> file(one, differentPng()));
            waiters(1);
            assertThat(file(two, "Synthetic rejected bytes".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
            assertThat(f.sql().queryForObject("select file_data from concept_materials where id=?", byte[].class, material)).isEqualTo(original);
            c.rollback(); assertThat(accepted.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }
        assertThat(count("select count(*) from system_logs where target_id=? and event_type like 'CONCEPT_MATERIAL_%' and outcome='SUCCESS'", concept)).isEqualTo(before + 1);
        materialsConsistent();
    }
    @Test void failedAuditRollsBackMaterialDeletionReplacementAndJoinPermission() throws Exception {
        long material = id(file(one, FileFixtures.png()));
        byte[] original = f.sql().queryForObject("select file_data from concept_materials where id=?", byte[].class, material);
        int before = count("select count(*) from system_logs where outcome='SUCCESS'");
        f.sql().execute("create function aaa_t10_fail_audit() returns trigger language plpgsql as $$ begin if NEW.event_type in ('CONCEPT_MATERIAL_REPLACED','CONCEPT_MATERIAL_REMOVED','WHITEBOARD_PARTICIPANT_INTERACTION_UPDATED') then raise exception 'Synthetic audit unavailable'; end if; return NEW; end $$");
        f.sql().execute("create trigger aaa_t10_fail_audit before insert on system_logs for each row execute function aaa_t10_fail_audit()");
        try {
            assertThat(file(one, differentPng()).statusCode()).isEqualTo(503);
            assertThat(request(two, "DELETE", "/api/concepts/teacher/" + concept + "/materials/" + material, teacher, null).statusCode()).isEqualTo(503);
            assertThat(request(one, "PATCH", "/api/whiteboards/teacher/" + f.board + "/participants/" + f.studentProfile(f.student) + "/interaction", teacher, "{\"interactionOverride\":\"BLOCKED\"}").statusCode()).isEqualTo(503);
            assertThat(f.sql().queryForObject("select interaction_override from whiteboard_participants where session_id=? and student_id=?", String.class, f.board, f.studentProfile(f.student))).isEqualTo("FOLLOW_GLOBAL");
            assertThat(f.sql().queryForObject("select file_data from concept_materials where id=?", byte[].class, material)).isEqualTo(original);
            assertThat(count("select count(*) from system_logs where outcome='SUCCESS'")).isEqualTo(before);
            materialsConsistent();
        } finally { f.sql().execute("drop trigger aaa_t10_fail_audit on system_logs"); }
        assertThat(file(two, differentPng()).statusCode()).isEqualTo(201);
        assertThat(request(one, "GET", "/api/auth/me", teacher, null).statusCode()).isEqualTo(200);
    }
    @Test void competingMaterialsOnDifferentContentsShareAtomicQuota() throws Exception {
        long another = f.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder().title("Second synthetic content").category("Synthetic")
                .createdByTeacher(f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow()).build()).getId();
        long limit = f.sql().queryForObject("select maximum from storage_limits where resource='MATERIAL_COUNT'", Long.class);
        f.sql().update("update storage_limits set maximum=1 where resource='MATERIAL_COUNT'");
        // Reserve the shared ACCOUNT counter first, then observe both independent writers waiting on it.
        f.sql().update("insert into storage_usage(scope,owner_id,resource,used) values('ACCOUNT',?,'MATERIAL_COUNT',0) on conflict do nothing", f.teacher.getId());
        try (var c = connection()) {
            try (var st = c.prepareStatement("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='MATERIAL_COUNT' for update")) { st.setLong(1, f.teacher.getId()); st.execute(); }
            var a = pool.submit(() -> file(one, FileFixtures.png()));
            var b = pool.submit(() -> upload(two, another, FileFixtures.png(), teacher, "file", "/api/concepts/teacher/{id}/materials/file"));
            waiters(2); c.rollback();
            var r = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(r.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
            assertThat(r.stream().filter(x -> x.statusCode() == 409).findFirst().orElseThrow().body()).contains("STORAGE_QUOTA_EXCEEDED");
            assertThat(used("MATERIAL_COUNT")).isEqualTo(1);
            assertThat(count("select count(*) from concept_materials where concept_content_id in (?,?)", concept, another)).isEqualTo(1);
            assertThat(used("MATERIAL_BYTES")).isEqualTo(f.sql().queryForObject("select sum(octet_length(file_data)) from concept_materials where concept_content_id in (?,?)", Long.class, concept, another));
            assertThat(count("select count(*) from system_logs where target_id in (?,?) and event_type='CONCEPT_MATERIAL_ADDED' and outcome='SUCCESS'", concept, another)).isEqualTo(1);
            assertThat(request(two, "GET", "/api/auth/me", teacher, null).statusCode()).isEqualTo(200);
            evidence.add(Map.of("scenario", "shared-material-quota", "statuses", r.stream().map(HttpResponse::statusCode).toList(), "persistedCount", 1));
        } finally { f.sql().update("update storage_limits set maximum=? where resource='MATERIAL_COUNT'", limit); }
    }
    @Test void joinRollbackDoesNotPublishMetric() throws Exception {
        f.sql().update("delete from whiteboard_participants where session_id=?", f.board);
        int before = count("select count(*) from usage_events where resource_id=? and event_type='WHITEBOARD_SESSION_JOINED'", "" + f.board);
        long quotaBefore = f.sql().queryForObject("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='USAGE_COUNT'", Long.class, f.student.getId());
        f.sql().execute("create function aaa_t10_fail_join() returns trigger language plpgsql as $$ begin raise exception 'Synthetic deferred join failure'; end $$");
        f.sql().execute("create constraint trigger aaa_t10_fail_join after insert on whiteboard_participants deferrable initially deferred for each row execute function aaa_t10_fail_join()");
        try {
            var r = join(one, pupil);
            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(count("select count(*) from whiteboard_participants where session_id=?", f.board)).isZero();
            int actual = count("select count(*) from usage_events where resource_id=? and event_type='WHITEBOARD_SESSION_JOINED'", "" + f.board);
            boolean metricBaseline = Boolean.getBoolean("t10.join-rollback-baseline");
            assertThat(actual).isEqualTo(before + (metricBaseline ? 1 : 0));
            assertThat(f.sql().queryForObject("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='USAGE_COUNT'", Long.class, f.student.getId())).isEqualTo(quotaBefore + (metricBaseline ? 1 : 0));
            evidence.add(Map.of("scenario", "join-rollback-metric", "baseline", metricBaseline, "status", 500, "metricDelta", actual - before, "participants", 0));
        } finally { f.sql().execute("drop trigger aaa_t10_fail_join on whiteboard_participants"); }
        assertThat(join(two, pupil).statusCode()).isEqualTo(200);
        assertThat(request(one, "GET", "/api/auth/me", pupil, null).statusCode()).isEqualTo(200);
    }
    @Test void historicalDuplicateFilesArePreservedAndReported() throws Exception {
        long material = id(file(one, FileFixtures.png()));
        f.sql().update("insert into concept_materials(concept_content_id,type,active,file_data,file_size,content_type,original_file_name) select concept_content_id,type,active,file_data,file_size,content_type,original_file_name from concept_materials where id=?", material);
        long bytes = used("MATERIAL_BYTES");
        int audit = count("select count(*) from system_logs where target_id=? and event_type='CONCEPT_MATERIAL_REPLACED' and outcome='SUCCESS'", concept);
        var response = file(two, differentPng());
        assertThat(response.statusCode()).isEqualTo(409); assertThat(response.body()).contains("MATERIAL_STATE_CONFLICT");
        assertThat(count("select count(*) from concept_materials where concept_content_id=?", concept)).isEqualTo(2);
        assertThat(used("MATERIAL_BYTES")).isEqualTo(bytes); materialsConsistent();
        assertThat(count("select count(*) from system_logs where target_id=? and event_type='CONCEPT_MATERIAL_REPLACED' and outcome='SUCCESS'", concept)).isEqualTo(audit);
        assertThat(request(one, "GET", "/api/auth/me", teacher, null).statusCode()).isEqualTo(200);
    }
}

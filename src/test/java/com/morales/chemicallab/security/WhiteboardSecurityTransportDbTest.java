package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.ChemicalLabBackendApplication;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.WhiteboardSessionService;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.morales.chemicallab.service.WhiteboardDrawEventService;
import com.morales.chemicallab.dto.WhiteboardDrawEventRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.net.URI;
import java.net.http.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.*;

/** Full application, disposable PostgreSQL and real STOMP over SockJS WebSocket framing. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhiteboardSecurityTransportDbTest {
    static final String PASSWORD = "FictitiousPass123";
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final byte[] key = new byte[32];
    final List<Peer> peers = new ArrayList<>();
    ConfigurableApplicationContext app;
    String schema, origin;
    Connection control;
    UserAccount teacher, otherTeacher, student, peerStudent, outsider;
    long board, otherBoard;
    int sequence;

    @BeforeAll void start() throws Exception {
        String url = setting("spring.datasource.url");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test"))
            throw new IllegalStateException("Disposable loopback PostgreSQL only");
        new SecureRandom().nextBytes(key);
        schema = "t03_" + UUID.randomUUID().toString().replace("-", "");
        control = DriverManager.getConnection(url, setting("spring.datasource.username"), setting("spring.datasource.password"));
        try (var sql = control.createStatement()) { sql.execute("CREATE SCHEMA " + schema); }
        app = startApp();
        origin = origin(app);
    }

    ConfigurableApplicationContext startApp(String... extra) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var application = new SpringApplication(ChemicalLabBackendApplication.class);
        application.setEnvironment(env);
        var args = new ArrayList<>(List.of("--spring.profiles.active=test", "--spring.config.location=classpath:/",
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + setting("spring.datasource.url") + "?currentSchema=" + schema,
                "--spring.datasource.username=" + setting("spring.datasource.username"),
                "--spring.datasource.password=" + setting("spring.datasource.password"),
                "--spring.jpa.properties.hibernate.default_schema=" + schema, "--spring.jpa.hibernate.ddl-auto=update",
                "--app.jwt.secret=" + Base64.getEncoder().encodeToString(key),
                "--app.demo.enabled=false", "--app.bootstrap.admin.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR"));
        for (String override : extra) {
            String prefix = override.substring(0, override.indexOf('=') + 1);
            args.removeIf(value -> value.startsWith(prefix));
            args.add(override);
        }
        return application.run(args.toArray(String[]::new));
    }

    @BeforeEach void fixture() {
        teacher = account(Role.DOCENTE, null, "3", "A");
        otherTeacher = account(Role.DOCENTE, null, "4", "B");
        student = account(Role.ESTUDIANTE, teacher, "3", "A");
        peerStudent = account(Role.ESTUDIANTE, teacher, "3", "A");
        outsider = account(Role.ESTUDIANTE, otherTeacher, "4", "B");
        board = board(teacher, "3", "A");
        otherBoard = board(otherTeacher, "4", "B");
        for (var user : List.of(student, peerStudent))
            app.getBean(WhiteboardSessionService.class).joinSession(user.getUsername(), board);
    }

    @AfterEach void closePeers() { peers.forEach(Peer::close); peers.clear(); }
    @AfterAll void cleanup() throws Exception {
        closePeers();
        if (app != null) app.close();
        if (control != null) {
            try (var sql = control.createStatement()) { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
            control.close();
        }
    }

    @Test void sec06ForeignSubscriptionAndDirectBrokerSendAreRejected() throws Exception {
        Peer receiver = connect(teacher); receiver.subscribe("board", "/topic/whiteboards/" + board);
        Peer attacker = connect(outsider);
        attacker.subscribe("foreign", "/topic/whiteboards/" + board);
        assertThat(attacker.closed.await(6, TimeUnit.SECONDS)).isTrue();
        Peer sender = connect(outsider);
        sender.send("/topic/whiteboards/" + board, "{\"marker\":\"SEC-06\"}");
        assertThat(sender.closed.await(6, TimeUnit.SECONDS)).isTrue();
        assertThat(receiver.await(s -> s.contains("SEC-06"), 400)).isNull();
    }

    @Test void sec15StudentCannotDeleteTeacherText() throws Exception {
        Peer owner = connect(teacher); owner.subscribe("board", "/topic/whiteboards/" + board);
        owner.subscribe("errors", "/user/queue/whiteboard-errors");
        Peer pupil = connect(student); pupil.subscribe("errors", "/user/queue/whiteboard-errors");
        owner.send(drawDestination(), text("teacher-text", "created"));
        assertThat(owner.await(s -> s.startsWith("MESSAGE"), 5000)).contains("created");
        pupil.send(drawDestination(), "{\"eventType\":\"TEXT_DELETE\",\"tool\":\"TEXT\",\"textId\":\"teacher-text\",\"clientEventId\":\"SEC-15\"}");
        assertThat(owner.await(s -> s.contains("SEC-15"), 1200))
                .as("SEC-15 forbidden deletion must not be broadcast").isNull();
        assertThat(pupil.await(s -> s.startsWith("MESSAGE") && s.contains("error"), 3000)).isNotNull();
    }

    @Test void destinationAndCommandAllowlistRequiresConnect() throws Exception {
        for (String destination : List.of("/queue/whiteboard-errors", "/user/other/queue/whiteboard-errors",
                "/queue/whiteboard-errors-usersomeone", "/topic/whiteboards/*", "/topic/**", "/unknown",
                "/topic/whiteboards/01", "/app/whiteboards/" + board + "/draw")) {
            Peer peer = connect(student); peer.subscribe("invalid", destination);
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).as(destination).isTrue();
        }
        for (String destination : List.of("/queue/x", "/user/queue/whiteboard-errors", "/topic/whiteboards/" + board,
                "/app/whiteboards/" + board + "/unknown")) {
            Peer peer = connect(student); peer.send(destination, "{}");
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).as(destination).isTrue();
        }
        for (String frame : List.of("SUBSCRIBE\nid:noauth\ndestination:/topic/whiteboards/" + board,
                "SEND\ndestination:/topic/whiteboards/" + board)) {
            Peer peer = rawPeer(); peer.frame(frame + "\n\n\0");
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
        }
        Peer peer = connect(student); peer.frame("BEGIN\ntransaction:unsupported\n\n\0");
        assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
    }

    @Test void onlyOwnerAndJoinedCurrentGroupCanObserve() throws Exception {
        var unjoined = account(Role.ESTUDIANTE, teacher, "3", "A");
        var admin = account(Role.ADMINISTRADOR, null, "3", "A");
        for (var user : List.of(otherTeacher, outsider, unjoined, admin)) {
            Peer peer = connect(user); peer.subscribe("board", "/topic/whiteboards/" + board);
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
        }
        Peer teacherPeer = connect(teacher), pupil = connect(student);
        pupil.subscribe("board", "/topic/whiteboards/" + board);
        teacherPeer.send("/app/whiteboards/" + board + "/presence", "");
        pupil.send("/app/whiteboards/" + board + "/presence", "");
        assertThat(pupil.closed.getCount()).isEqualTo(1);
        assertThat(teacherPeer.closed.getCount()).isEqualTo(1);
        teacherPeer.send(drawDestination(), text("legal", "legal-observation"));
        assertThat(pupil.await(s -> s.contains("legal-observation"), 5000)).isNotNull();
    }

    @Test void privateErrorsAndObservationSurviveDrawingPermissionLossAndPause() throws Exception {
        String token = login(student);
        Peer pupil = connect(token), sibling = connect(login(student)), owner = connect(teacher);
        pupil.subscribe("errors", "/user/queue/whiteboard-errors");
        sibling.subscribe("errors", "/user/queue/whiteboard-errors");
        pupil.subscribe("board", "/topic/whiteboards/" + board);
        sql().update("update whiteboard_sessions set interaction_enabled=false where id=?", board);
        String before = state();
        pupil.send(drawDestination(), text("denied", "permission-denied"));
        assertThat(pupil.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
        assertThat(sibling.await(s -> s.contains("DRAW_REJECTED"), 300)).isNull();
        assertThat(state()).isEqualTo(before);
        owner.send(drawDestination(), text("allowed", "observe-without-drawing"));
        assertThat(pupil.await(s -> s.contains("observe-without-drawing"), 5000)).isNotNull();
        sql().update("update whiteboard_participants set interaction_override='ALLOWED' where session_id=? and student_id=?", board, studentProfile(student));
        pupil.send(drawDestination(), text("mine", "override-allowed"));
        assertThat(pupil.await(s -> s.contains("override-allowed"), 5000)).isNotNull();
        sql().update("update whiteboard_participants set interaction_override='BLOCKED' where session_id=? and student_id=?", board, studentProfile(student));
        pupil.send(drawDestination(), text("mine", "override-blocked"));
        assertThat(pupil.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
        sql().update("update whiteboard_sessions set status='PAUSED' where id=?", board);
        owner.subscribe("errors", "/user/queue/whiteboard-errors");
        owner.send(drawDestination(), text("paused", "paused"));
        assertThat(owner.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
        assertThat(pupil.closed.getCount()).isEqualTo(1);
    }

    @Test void objectOwnershipForEveryTypeSurvivesDeleteRestoreAndCollision() throws Exception {
        Peer owner = connect(teacher), a = connect(student), b = connect(peerStudent);
        owner.subscribe("board", "/topic/whiteboards/" + board);
        b.subscribe("errors", "/user/queue/whiteboard-errors");
        for (String kind : List.of("TEXT", "SHAPE", "STROKE")) {
            for (var creator : List.of(a, owner)) {
                String id = kind + (creator == a ? "-pupil" : "-teacher");
                creator.send(drawDestination(), object(kind, id, "create-" + id));
                assertThat(owner.await(s -> s.contains("create-" + id), 5000)).isNotNull();
                String before = state();
                for (String payload : List.of(object(kind, id, "collision-" + id), deletion(kind, id, "delete-" + id))) {
                    b.send(drawDestination(), payload);
                    assertThat(b.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
                    assertThat(state()).isEqualTo(before);
                    assertThat(owner.await(s -> s.contains("collision-" + id) || s.contains("delete-" + id), 180)).isNull();
                }
                creator.send(drawDestination(), object(kind, id, "update-" + id));
                assertThat(owner.await(s -> s.contains("update-" + id), 5000)).isNotNull();
                creator.send(drawDestination(), deletion(kind, id, "own-delete-" + id));
                assertThat(owner.await(s -> s.contains("own-delete-" + id), 5000)).isNotNull();
                String deleted = state();
                b.send(drawDestination(), object(kind, id, "steal-tombstone-" + id));
                assertThat(b.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
                assertThat(state()).isEqualTo(deleted);
                creator.send(drawDestination(), object(kind, id, "restore-" + id));
                assertThat(owner.await(s -> s.contains("restore-" + id), 5000)).isNotNull();
                owner.send(drawDestination(), object(kind, id, "teacher-manage-" + id));
                assertThat(owner.await(s -> s.contains("teacher-manage-" + id), 5000)).isNotNull();
                assertThat(sql().queryForObject("select owner_user_id from whiteboard_object_ownership where board_id=? and object_kind=? and object_id=?",
                        Long.class, board, kind, id)).isEqualTo(creator == a ? student.getId() : teacher.getId());
            }
        }
        b.send(drawDestination(), "{\"eventType\":\"ERASE\",\"tool\":\"ERASER\",\"strokeId\":\"free-eraser\",\"eraserSize\":20,\"points\":[{\"x\":10,\"y\":20}],\"clientEventId\":\"free-eraser\"}");
        assertThat(owner.await(s -> s.contains("free-eraser"), 5000)).isNotNull();
        b.send(drawDestination(), "{\"eventType\":\"CLEAR\",\"tool\":\"PEN\"}");
        assertThat(b.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
        owner.send(drawDestination(), "{\"eventType\":\"CLEAR\",\"tool\":\"PEN\",\"clientEventId\":\"teacher-clear\"}");
        assertThat(owner.await(s -> s.contains("teacher-clear"), 5000)).isNotNull();
        assertThat(json.readTree(state()).path("texts")).isEmpty();
        assertThat(sql().queryForObject("select count(*) from whiteboard_object_ownership where board_id=?", Long.class, board)).isEqualTo(7);
    }

    @Test void legacyObjectsAndSnapshotPutCannotForgeOwnership() throws Exception {
        String legacy = "{\"v\":1,\"strokes\":[],\"shapes\":[],\"texts\":[{\"id\":\"legacy\",\"wx\":1,\"wy\":1,\"color\":\"#123456\",\"size\":24,\"runs\":[],\"ownerUserId\":" + student.getId() + "}]}";
        sql().update("update whiteboard_sessions set current_state_json=? where id=?", legacy, board);
        var visible = request("GET", "/api/whiteboards/student/" + board + "/state", login(student), null);
        assertThat(json.readTree(json.readTree(visible.body()).path("stateJson").asText()).path("texts").get(0).path("ownerUserId").isNull()).isTrue();
        Peer owner = connect(teacher), pupil = connect(student);
        pupil.subscribe("errors", "/user/queue/whiteboard-errors"); owner.subscribe("board", "/topic/whiteboards/" + board);
        pupil.send(drawDestination(), text("legacy", "forged-owner"));
        assertThat(pupil.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
        assertThat(state()).isEqualTo(legacy); // even lazy import rolls back on rejection
        owner.send(drawDestination(), text("legacy", "teacher-legacy"));
        assertThat(owner.await(s -> s.contains("teacher-legacy"), 5000)).isNotNull();
        assertThat(json.readTree(state()).path("texts").get(0).path("ownerUserId").isNull()).isTrue();
        String before = state();
        var response = request("PUT", "/api/whiteboards/teacher/" + board + "/state", login(teacher), json.writeValueAsString(Map.of("stateJson", legacy)));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(state()).isEqualTo(before);
    }

    @Test void individualAndGlobalLogoutCutPassiveSocketsButNotOtherSessions() throws Exception {
        String first = login(student), second = login(student);
        Peer a = connect(first), duplicate = connect(first), b = connect(second), owner = connect(teacher);
        for (Peer peer : List.of(a, duplicate, b)) peer.subscribe("board", "/topic/whiteboards/" + board);
        assertThat(request("POST", "/api/auth/logout", first, "{}").statusCode()).isEqualTo(204);
        owner.send(drawDestination(), text("after-logout", "after-individual-logout"));
        assertThat(b.await(s -> s.contains("after-individual-logout"), 5000)).isNotNull();
        for (Peer peer : List.of(a, duplicate)) {
            assertThat(peer.await(s -> s.contains("after-individual-logout"), 200)).isNull();
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
            assertThat(peer.closeCode).isEqualTo(4001);
        }
        assertThat(b.closed.getCount()).isEqualTo(1);
        assertThat(request("POST", "/api/auth/logout-all", second, "{}").statusCode()).isEqualTo(204);
        assertThat(b.closed.await(6, TimeUnit.SECONDS)).isTrue();
    }

    @Test void passwordChangeResetDeactivationAndReactivationNeverRecoverOldSockets() throws Exception {
        for (String action : List.of("change", "reset", "deactivate")) {
            UserAccount user = account(Role.ESTUDIANTE, teacher, "3", "A");
            app.getBean(WhiteboardSessionService.class).joinSession(user.getUsername(), board);
            String token = login(user); Peer a = connect(token), b = connect(login(user));
            a.subscribe("board", "/topic/whiteboards/" + board); b.subscribe("board", "/topic/whiteboards/" + board);
            if (action.equals("change")) {
                assertThat(request("PATCH", "/api/auth/change-temporary-password", token,
                        "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewFictitious123\",\"confirmPassword\":\"NewFictitious123\"}").statusCode()).isEqualTo(200);
            } else mutate(user, u -> {
                if (action.equals("reset")) { u.setPassword(app.getBean(PasswordEncoder.class).encode("ResetFictitious123")); u.setTemporaryPassword(true); }
                else u.setActive(false);
            });
            marker("revoked-" + action);
            for (Peer peer : List.of(a, b)) {
                assertThat(peer.await(s -> s.contains("revoked-" + action), 200)).isNull();
                assertThat(peer.closed.await(6, TimeUnit.SECONDS)).as(action).isTrue();
            }
            if (action.equals("deactivate")) mutate(user, u -> u.setActive(true));
            assertThat(request("GET", "/api/auth/me", token, null).statusCode()).isEqualTo(401);
        }
    }

    @Test void realExpirationClosesIdleSocketWithinDeadline() throws Exception {
        var expires = Instant.now().plusSeconds(4).truncatedTo(ChronoUnit.SECONDS);
        var session = app.getBean(AccountSessionRepository.class).saveAndFlush(AccountSession.builder()
                .id(UUID.randomUUID()).user(student).credentialsVersion(student.getCredentialsVersion())
                .createdAt(Instant.now().truncatedTo(ChronoUnit.SECONDS)).expiresAt(expires).build());
        Peer peer = connect(app.getBean(JwtService.class).generateToken(student, session));
        peer.subscribe("board", "/topic/whiteboards/" + board);
        assertThat(peer.closed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(Instant.now()).isBefore(expires.plusMillis(5250));
        assertThat(peer.closeCode).isEqualTo(4001);
        marker("expired-marker");
        assertThat(peer.await(s -> s.contains("expired-marker"), 250)).isNull();
    }

    @Test void scopeParticipationAndClosedBoardChangesCutExistingObservers() throws Exception {
        for (String change : List.of("group", "participation", "closed")) {
            Peer peer = connect(student); peer.subscribe("board", "/topic/whiteboards/" + board);
            if (change.equals("group")) sql().update("update student_profiles set section='B' where user_id=?", student.getId());
            if (change.equals("participation")) sql().update("delete from whiteboard_participants where session_id=? and student_id=?", board, studentProfile(student));
            if (change.equals("closed")) sql().update("update whiteboard_sessions set status='CLOSED' where id=?", board);
            marker("scope-" + change);
            assertThat(peer.await(s -> s.contains("scope-" + change), 250)).isNull();
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
            assertThat(peer.closeCode).isEqualTo(4003);
            if (change.equals("group")) sql().update("update student_profiles set section='A' where user_id=?", student.getId());
            if (change.equals("participation")) app.getBean(WhiteboardSessionService.class).joinSession(student.getUsername(), board);
        }
    }

    @Test void anotherApplicationInstanceRevokesPassiveConnectionThroughPostgres() throws Exception {
        String token = login(student); Peer peer = connect(token); peer.subscribe("board", "/topic/whiteboards/" + board);
        try (var other = startApp()) {
            String firstOrigin = origin;
            try { origin = origin(other); assertThat(request("POST", "/api/auth/logout", token, "{}").statusCode()).isEqualTo(204); }
            finally { origin = firstOrigin; }
            marker("other-instance");
            assertThat(peer.await(s -> s.contains("other-instance"), 200)).isNull();
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void stateAndOwnershipSurviveApplicationRestart() throws Exception {
        String teacherToken = login(teacher), studentToken = login(student);
        Peer owner = connect(teacherToken); owner.subscribe("board", "/topic/whiteboards/" + board);
        owner.send(drawDestination(), text("persisted", "before-restart"));
        assertThat(owner.await(s -> s.contains("before-restart"), 5000)).isNotNull();
        String before = state();
        closePeers(); app.close(); app = startApp(); origin = origin(app);
        assertThat(state()).isEqualTo(before);
        Peer pupil = connect(studentToken); pupil.subscribe("errors", "/user/queue/whiteboard-errors");
        pupil.send(drawDestination(), deletion("TEXT", "persisted", "steal-after-restart"));
        assertThat(pupil.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
        assertThat(state()).isEqualTo(before);
        assertThat(request("GET", "/api/whiteboards/student/" + board + "/state", studentToken, null).body()).contains("persisted");
    }

    @Test void concurrentCreatorsAndModifiersCannotStealIdentityOrLoseCommittedObjects() throws Exception {
        Peer a = connect(student), b = connect(peerStudent), observer = connect(teacher);
        for (Peer peer : List.of(a, b)) peer.subscribe("errors", "/user/queue/whiteboard-errors");
        observer.subscribe("board", "/topic/whiteboards/" + board);
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (String kind : List.of("TEXT", "SHAPE", "STROKE")) {
                String id = "race-" + kind;
                var gate = new CountDownLatch(1);
                var f1 = pool.submit(() -> { gate.await(); a.send(drawDestination(), object(kind, id, "race-a")); return null; });
                var f2 = pool.submit(() -> { gate.await(); b.send(drawDestination(), object(kind, id, "race-b")); return null; });
                gate.countDown(); f1.get(); f2.get();
                assertThat(observer.await(s -> s.contains("race-a") || s.contains("race-b"), 5000)).isNotNull();
                long winner = sql().queryForObject("select owner_user_id from whiteboard_object_ownership where board_id=? and object_kind=? and object_id=?", Long.class, board, kind, id);
                Peer loser = winner == student.getId() ? b : a;
                assertThat(loser.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
                loser.send(drawDestination(), object(kind, id, "race-modification"));
                assertThat(loser.await(s -> s.contains("DRAW_REJECTED"), 5000)).isNotNull();
                assertThat(sql().queryForObject("select count(*) from whiteboard_object_ownership where board_id=? and object_kind=? and object_id=?", Long.class, board, kind, id)).isEqualTo(1);
                // Two authorized updates to different objects must both survive the board JSON rewrite.
                a.send(drawDestination(), object(kind, "own-a-" + kind, "seed-a-" + kind));
                b.send(drawDestination(), object(kind, "own-b-" + kind, "seed-b-" + kind));
                assertBothMarkers(observer, "seed-a-" + kind, "seed-b-" + kind);
                var updateGate = new CountDownLatch(1);
                var updateA = pool.submit(() -> { updateGate.await(); a.send(drawDestination(), object(kind, "own-a-" + kind, "update-a-" + kind).replace("\"x\":10", "\"x\":111")); return null; });
                var updateB = pool.submit(() -> { updateGate.await(); b.send(drawDestination(), object(kind, "own-b-" + kind, "update-b-" + kind).replace("\"x\":10", "\"x\":222")); return null; });
                updateGate.countDown(); updateA.get(); updateB.get();
                assertBothMarkers(observer, "update-a-" + kind, "update-b-" + kind);
                String array = kind.equals("TEXT") ? "texts" : kind.equals("SHAPE") ? "shapes" : "strokes";
                int updatedObjects = 0;
                for (JsonNode item : json.readTree(state()).path(array)) {
                    if (!item.path("id").asText().startsWith("own-")) continue;
                    updatedObjects++;
                    int x = kind.equals("TEXT") ? item.path("wx").asInt() : kind.equals("SHAPE") ? item.path("x1").asInt() : item.path("points").get(0).path("x").asInt();
                    assertThat(x).isEqualTo(item.path("id").asText().startsWith("own-a-") ? 111 : 222);
                }
                assertThat(updatedObjects).isEqualTo(2);
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void rollbackNeverAnnouncesOrPersistsOperation() throws Exception {
        Peer observer = connect(student); observer.subscribe("board", "/topic/whiteboards/" + board);
        String before = state();
        var request = json.readValue(text("rollback", "rollback-marker"), WhiteboardDrawEventRequest.class);
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            app.getBean(WhiteboardDrawEventService.class).processDrawEvent(teacher.getUsername(), board, request);
            status.setRollbackOnly();
        });
        assertThat(state()).isEqualTo(before);
        assertThat(observer.await(s -> s.contains("rollback-marker"), 400)).isNull();
        assertThat(sql().queryForObject("select count(*) from whiteboard_object_ownership where board_id=?", Long.class, board)).isZero();
    }

    @Test void connectRejectsMissingMalformedTemporaryAndRevokedSessions() throws Exception {
        String valid = login(student);
        assertThat(request("POST", "/api/auth/logout", valid, "{}").statusCode()).isEqualTo(204);
        mutate(peerStudent, user -> user.setTemporaryPassword(true));
        for (String header : List.of("", "Authorization:Bearer invalid\n", "Authorization:Bearer " + valid + "\n",
                "Authorization:Bearer " + login(peerStudent) + "\n")) {
            Peer peer = rawPeer();
            peer.frame("CONNECT\naccept-version:1.2\nheart-beat:0,0\n" + header + "\n\0");
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
            assertThat(peer.await(s -> s.startsWith("CONNECTED"), 100)).isNull();
        }
    }

    @Test void declaredActorsNeverOverridePrincipalAndIdentityIsScopedByBoardAndType() throws Exception {
        Peer pupil = connect(student); pupil.subscribe("board", "/topic/whiteboards/" + board);
        for (String kind : List.of("TEXT", "SHAPE", "STROKE")) {
            String payload = object(kind, "shared-id", "actor-" + kind);
            payload = payload.substring(0, payload.length() - 1) + ",\"ownerUserId\":" + teacher.getId()
                    + ",\"actorRole\":\"DOCENTE\",\"actorDisplayName\":\"Forged\",\"userId\":" + teacher.getId() + "}";
            pupil.send(drawDestination(), payload);
            String frame = pupil.await(s -> s.contains("actor-" + kind), 5000);
            assertThat(frame).contains("\"actorRole\":\"ESTUDIANTE\"").doesNotContain("Forged");
            assertThat(sql().queryForObject("select owner_user_id from whiteboard_object_ownership where board_id=? and object_kind=? and object_id='shared-id'", Long.class, board, kind)).isEqualTo(student.getId());
        }
        Peer owner2 = connect(otherTeacher); owner2.subscribe("board", "/topic/whiteboards/" + otherBoard);
        owner2.send("/app/whiteboards/" + otherBoard + "/draw", text("shared-id", "different-board"));
        assertThat(owner2.await(s -> s.contains("different-board"), 5000)).isNotNull();
    }

    @Test void validationUnavailableClosesIdleConnectionAndReleasesRegistry() throws Exception {
        Peer peer = connect(student); peer.subscribe("board", "/topic/whiteboards/" + board);
        long started = System.nanoTime();
        control.setAutoCommit(false);
        try (var statement = control.createStatement()) {
            statement.execute("LOCK TABLE " + schema + ".account_sessions IN ACCESS EXCLUSIVE MODE");
            marker("database-blocked");
            assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5500);
            assertThat(peer.await(s -> s.contains("database-blocked"), 200)).isNull();
        } finally { control.rollback(); control.setAutoCommit(true); }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (app.getBean(WhiteboardConnections.class).size() > 0 && System.nanoTime() < end) Thread.sleep(30);
        assertThat(app.getBean(WhiteboardConnections.class).size()).isZero();
    }

    String object(String kind, String id, String marker) {
        if (kind.equals("TEXT")) return text(id, marker);
        return "{\"eventType\":\"" + (kind.equals("SHAPE") ? "SHAPE" : "DRAW") + "\",\"tool\":\"" + (kind.equals("SHAPE") ? "RECTANGLE" : "PEN")
                + "\",\"" + (kind.equals("SHAPE") ? "shapeId" : "strokeId") + "\":\"" + id + "\",\"color\":\"#123456\",\"strokeWidth\":4,"
                + "\"points\":[{\"x\":10,\"y\":20},{\"x\":30,\"y\":40}],\"clientEventId\":\"" + marker + "\"}";
    }
    void assertBothMarkers(Peer peer, String first, String second) throws Exception {
        String arrived = peer.await(s -> s.contains(first) || s.contains(second), 5000);
        assertThat(arrived).isNotNull();
        String remaining = arrived.contains(first) ? second : first;
        assertThat(peer.await(s -> s.contains(remaining), 5000)).isNotNull();
    }
    String deletion(String kind, String id, String marker) {
        return "{\"eventType\":\"" + kind + "_DELETE\",\"tool\":\"" + (kind.equals("TEXT") ? "TEXT" : kind.equals("SHAPE") ? "RECTANGLE" : "PEN")
                + "\",\"" + kind.toLowerCase() + "Id\":\"" + id + "\",\"clientEventId\":\"" + marker + "\"}";
    }
    JdbcTemplate sql() { return app.getBean(JdbcTemplate.class); }
    String state() { return sql().queryForObject("select current_state_json from whiteboard_sessions where id=?", String.class, board); }
    long studentProfile(UserAccount user) { return app.getBean(StudentProfileRepository.class).findByUser_Id(user.getId()).orElseThrow().getId(); }
    void marker(String marker) { app.getBean(SimpMessagingTemplate.class).convertAndSend("/topic/whiteboards/" + board, (Object) Map.of("marker", marker)); }
    void mutate(UserAccount user, java.util.function.Consumer<UserAccount> mutation) {
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            var managed = app.getBean(UserAccountRepository.class).findById(user.getId()).orElseThrow();
            app.getBean(AccountSessionService.class).lock(managed); mutation.accept(managed);
        });
    }

    String text(String id, String marker) {
        return "{\"eventType\":\"TEXT\",\"tool\":\"TEXT\",\"textId\":\"" + id + "\",\"color\":\"#123456\","
                + "\"fontSize\":24,\"points\":[{\"x\":10,\"y\":20}],\"runs\":[{\"text\":\"Fictitious\",\"bold\":false,\"italic\":false,\"underline\":false}],\"clientEventId\":\"" + marker + "\"}";
    }
    String drawDestination() { return "/app/whiteboards/" + board + "/draw"; }
    Peer connect(UserAccount user) throws Exception { return connect(login(user)); }
    Peer connect(String token) throws Exception {
        Peer peer = rawPeer();
        peer.frame("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n\n\0");
        assertThat(peer.await(s -> s.startsWith("CONNECTED"), 5000)).isNotNull();
        return peer;
    }
    Peer rawPeer() throws Exception {
        Peer peer = new Peer(); peers.add(peer);
        peer.socket = http.newWebSocketBuilder().header("Origin", "http://127.0.0.1:4200")
                .buildAsync(URI.create(origin.replace("http:", "ws:") + "/ws/000/" + UUID.randomUUID() + "/websocket"), peer).get(10, TimeUnit.SECONDS);
        assertThat(peer.open.await(5, TimeUnit.SECONDS)).isTrue();
        return peer;
    }
    UserAccount account(Role role, UserAccount teacherUser, String grade, String section) {
        UserAccount user = app.getBean(UserAccountRepository.class).saveAndFlush(UserAccount.builder()
                .username("t03fixture" + ++sequence).password(app.getBean(PasswordEncoder.class).encode(PASSWORD))
                .role(role).temporaryPassword(false).active(true).build());
        if (role == Role.DOCENTE) app.getBean(TeacherProfileRepository.class).saveAndFlush(TeacherProfile.builder()
                .user(user).names("Fictitious").lastNames("Teacher").build());
        if (role == Role.ESTUDIANTE) app.getBean(StudentProfileRepository.class).saveAndFlush(StudentProfile.builder()
                .user(user).studentCode(user.getUsername()).names("Fictitious").lastNames("Student")
                .teacher(app.getBean(TeacherProfileRepository.class).findByUser(teacherUser).orElseThrow())
                .grade(grade).section(section).build());
        return user;
    }
    long board(UserAccount owner, String grade, String section) {
        return app.getBean(WhiteboardSessionRepository.class).saveAndFlush(WhiteboardSession.builder()
                .teacher(app.getBean(TeacherProfileRepository.class).findByUser(owner).orElseThrow())
                .name("Fictitious T03").grade(grade).section(section).interactionEnabled(true).build()).getId();
    }
    String login(UserAccount user) throws Exception {
        var response = request("POST", "/api/auth/login", null,
                "{\"usernameOrEmail\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("token").asText();
    }
    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    String setting(String name) { return Objects.requireNonNull(System.getProperty(name), "Required disposable DB setting: " + name); }
    String origin(ConfigurableApplicationContext context) { return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"); }

    class Peer implements WebSocket.Listener, AutoCloseable {
        WebSocket socket;
        final CountDownLatch open = new CountDownLatch(1), closed = new CountDownLatch(1);
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final StringBuilder fragments = new StringBuilder();
        volatile int closeCode;
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                String packet = fragments.toString(); fragments.setLength(0);
                try {
                    if (packet.equals("o")) open.countDown();
                    else if (packet.startsWith("a")) for (JsonNode frame : json.readTree(packet.substring(1))) frames.add(frame.asText());
                    else if (packet.startsWith("c")) { closeCode = json.readTree(packet.substring(1)).get(0).asInt(); closed.countDown(); }
                } catch (Exception ex) { frames.add("PARSE_ERROR"); }
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { if (closeCode == 0) closeCode = code; closed.countDown(); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { closed.countDown(); }
        void frame(String frame) throws Exception { socket.sendText(json.writeValueAsString(List.of(frame)), true).get(5, TimeUnit.SECONDS); }
        void subscribe(String id, String destination) throws Exception { frame("SUBSCRIBE\nid:" + id + "\ndestination:" + destination + "\n\n\0"); Thread.sleep(120); }
        void send(String destination, String payload) throws Exception { frame("SEND\ndestination:" + destination + "\ncontent-type:application/json\n\n" + payload + "\0"); }
        String await(Predicate<String> match, long milliseconds) throws Exception {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
            while (System.nanoTime() < end) {
                String frame = frames.poll(Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())), TimeUnit.MILLISECONDS);
                if (frame != null && match.test(frame)) return frame;
            }
            return null;
        }
        @Override public void close() { if (socket != null) socket.abort(); }
    }
}

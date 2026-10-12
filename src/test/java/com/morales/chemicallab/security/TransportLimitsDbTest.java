package com.morales.chemicallab.security;

import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import com.morales.chemicallab.security.limits.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP, SockJS/STOMP and disposable PostgreSQL. Budgets reduced to deterministic edges. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransportLimitsDbTest {
    final WhiteboardSecurityTransportDbTest f = new WhiteboardSecurityTransportDbTest() {
        @Override ConfigurableApplicationContext startApp(String... extra) { return super.startApp(
                "--app.transport.draw-burst=4", "--app.transport.draw-per-second=1",
                "--app.transport.board-objects=4", "--app.transport.telemetry-burst=4", "--app.transport.telemetry-per-second=1"); }
    };
    @BeforeAll void start() throws Exception { f.start(); }
    @BeforeEach void fixture() { f.fixture(); }
    @AfterEach void peers() { f.closePeers(); }
    @AfterAll void stop() throws Exception { f.cleanup(); }

    @Test void noisyAccountTwoSessionsDoesNotBlockPeerOrTeacherAndCanRecover() throws Exception {
        long started = System.nanoTime();
        String token = f.login(f.student);
        var a = f.connect(token); var sibling = f.connect(token); var b = f.connect(f.peerStudent); var teacher = f.connect(f.teacher);
        for (var peer : List.of(a, sibling, b, teacher)) { peer.subscribe("errors", "/user/queue/whiteboard-errors"); peer.subscribe("board", "/topic/whiteboards/" + f.board); }
        for (int i = 0; i < 4; i++) {
            final int index = i;
            var sender = i % 2 == 0 ? a : sibling;
            sender.send(f.drawDestination(), f.text("noisy", "accepted-" + i));
            assertThat(sender.await(s -> s.contains("accepted-" + index), 5000)).isNotNull();
        }
        String before = f.state();
        sibling.frame("SEND\ndestination:" + f.drawDestination() + "\ncontent-type:application/json\nclient-event-id:rate-rejected\n\n" + f.text("noisy", "rate-rejected") + "\0");
        assertThat(sibling.await(s -> s.contains("TRAFFIC_RATE_LIMITED"), 5000)).contains("rate-rejected", "\"accepted\":false");
        assertThat(f.state()).isEqualTo(before);
        assertThat(b.await(s -> s.contains("rate-rejected"), 200)).isNull();
        b.send(f.drawDestination(), f.text("peer", "peer-continues"));
        assertThat(b.await(s -> s.contains("peer-continues"), 5000)).isNotNull();
        teacher.send(f.drawDestination(), f.text("teacher", "teacher-continues"));
        assertThat(teacher.await(s -> s.contains("teacher-continues"), 5000)).isNotNull();
        Thread.sleep(1100);
        a.send(f.drawDestination(), f.text("noisy", "recovered"));
        assertThat(a.await(s -> s.contains("recovered"), 5000)).isNotNull();
        System.out.println("T07A local bounded trial: 4 clients, 8 SEND, 7 accepted/1 rejected; durationMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @Test void objectBoundaryRollsBackStateOwnershipRevisionAndBroadcast() throws Exception {
        var owner = f.connect(f.teacher); var observer = f.connect(f.peerStudent);
        owner.subscribe("board", "/topic/whiteboards/" + f.board); owner.subscribe("errors", "/user/queue/whiteboard-errors"); observer.subscribe("board", "/topic/whiteboards/" + f.board);
        for (int i = 0; i < 4; i++) { final int index = i; owner.send(f.drawDestination(), f.text("o" + i, "object-" + i)); assertThat(owner.await(s -> s.contains("object-" + index), 5000)).isNotNull(); }
        String before = f.state();
        long revision = f.sql().queryForObject("select state_revision from whiteboard_sessions where id=?", Long.class, f.board);
        Thread.sleep(1100);
        owner.send(f.drawDestination(), f.text("excess", "excess-object"));
        assertThat(owner.await(s -> s.contains("INPUT_LIMIT_EXCEEDED"), 5000)).isNotNull();
        assertThat(f.state()).isEqualTo(before);
        assertThat(f.sql().queryForObject("select state_revision from whiteboard_sessions where id=?", Long.class, f.board)).isEqualTo(revision);
        assertThat(f.sql().queryForObject("select count(*) from whiteboard_object_ownership where board_id=?", Long.class, f.board)).isEqualTo(4);
        assertThat(observer.await(s -> s.contains("excess-object"), 200)).isNull();
        String token = f.login(f.teacher);
        var loaded = f.request("GET", "/api/whiteboards/teacher/" + f.board + "/state", token, null);
        assertThat(loaded.statusCode()).isEqualTo(200);
        assertThat(f.request("PUT", "/api/whiteboards/teacher/" + f.board + "/state", token, f.json.writeValueAsString(Map.of("stateJson", before))).statusCode()).isEqualTo(200);
        assertThat(f.state()).isEqualTo(before);
    }

    @Test void connectionSubscriptionAndRegistryCleanupEdges() throws Exception {
        String token = f.login(f.student);
        var one = f.connect(token); var two = f.connect(token); var three = f.connect(token);
        var fourth = f.rawPeer(); fourth.frame("CONNECT\naccept-version:1.2\nAuthorization:Bearer " + token + "\n\n\0");
        assertThat(fourth.closed.await(6, TimeUnit.SECONDS)).isTrue(); assertThat(fourth.closeCode).isEqualTo(4429);
        one.subscribe("first", "/topic/whiteboards/" + f.board); one.subscribe("repeat", "/topic/whiteboards/" + f.board);
        assertThat(one.closed.await(6, TimeUnit.SECONDS)).isTrue(); assertThat(one.closeCode).isEqualTo(4429);
        var replacement = f.connect(token); replacement.subscribe("errors", "/user/queue/whiteboard-errors");
        assertThat(f.request("POST", "/api/auth/logout", token, "{}").statusCode()).isEqualTo(204);
        for (var peer : List.of(two, three, replacement)) assertThat(peer.closed.await(6, TimeUnit.SECONDS)).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (f.app.getBean(WhiteboardConnections.class).size() > 0 && System.nanoTime() < end) Thread.sleep(20);
        assertThat(f.app.getBean(WhiteboardConnections.class).size()).isZero();
        assertThat(f.app.getBean(AccountTrafficLimits.class).size()).isGreaterThan(0); // budget survives reconnect; expires separately
    }

    @Test void invalidHistoricalDataCannotBeReturnedPersistedOrBroadcastAndRemainsIntact() throws Exception {
        String raw = "{\"v\":1,\"texts\":[],\"strokes\":[{\"points\":[{\"x\":1e999,\"y\":0}]}]}";
        f.sql().update("update whiteboard_sessions set current_state_json=? where id=?", raw, f.board);
        String token = f.login(f.teacher);
        var response = f.request("GET", "/api/whiteboards/teacher/" + f.board + "/state", token, null);
        assertThat(response.statusCode()).isEqualTo(409); assertThat(response.body()).contains("HISTORICAL_STATE_INVALID");
        assertThat(f.request("PUT", "/api/whiteboards/teacher/" + f.board + "/state", token, f.json.writeValueAsString(Map.of("stateJson", raw))).statusCode()).isEqualTo(400);
        var owner = f.connect(token); var observer = f.connect(f.peerStudent);
        owner.subscribe("errors", "/user/queue/whiteboard-errors"); observer.subscribe("board", "/topic/whiteboards/" + f.board);
        owner.send(f.drawDestination(), f.text("valid-new", "historical-edit"));
        assertThat(owner.await(s -> s.contains("INPUT_INVALID"), 5000)).isNotNull();
        assertThat(f.state()).isEqualTo(raw); assertThat(observer.await(s -> s.contains("historical-edit"), 200)).isNull();
    }

    @Test void httpActualBytesMetadataAndStatusContracts() throws Exception {
        String path = "/api/usage-metrics/events", token = f.login(f.student);
        long before = f.sql().queryForObject("select count(*) from usage_events where user_id=?", Long.class, f.student.getId());
        var metadata = new LinkedHashMap<String, String>(); for (int i = 0; i < 32; i++) metadata.put("key" + i, "v");
        var body = new LinkedHashMap<String, Object>(Map.of("module", "PERIODIC_TABLE", "eventType", "PERIODIC_ELEMENT_VIEWED", "metadata", metadata));
        assertThat(f.request("POST", path, token, f.json.writeValueAsString(body)).statusCode()).isEqualTo(201);
        metadata.put("extra", "v");
        assertThat(f.request("POST", path, token, f.json.writeValueAsString(body)).statusCode()).isEqualTo(413);
        assertThat(f.request("POST", path, token, " ".repeat(32769)).statusCode()).isEqualTo(413);
        assertThat(f.request("POST", path, null, " ".repeat(32769)).statusCode()).isEqualTo(401);
        assertThat(f.request("POST", "/api/whiteboards/teacher/" + f.board + "/state", token, " ".repeat(4194305)).statusCode()).isEqualTo(403);
        assertThat(f.sql().queryForObject("select count(*) from usage_events where user_id=?", Long.class, f.student.getId())).isEqualTo(before + 1);
    }
}

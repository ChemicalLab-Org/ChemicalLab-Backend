package com.morales.chemicallab.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

/** Opt-in browser fixture, not matched by the normal *Test suite. Disposable DB guard is reused. */
class WhiteboardBrowserFixture {
    @Test void serveDisposableBrowserFixture() throws Exception {
        Path info = Path.of(System.getProperty("t03.fixture-file"));
        Path stop = Path.of(System.getProperty("t03.stop-file"));
        var fixture = new WhiteboardSecurityTransportDbTest() {
            @Override ConfigurableApplicationContext startApp(String... extra) { return super.startApp("--server.port=18087"); }
        };
        try {
            fixture.start(); fixture.fixture();
            Files.writeString(info, fixture.json.writeValueAsString(Map.of(
                    "origin", fixture.origin, "boardId", fixture.board,
                    "teacher", fixture.teacher.getUsername(), "student", fixture.student.getUsername(),
                    "peerStudent", fixture.peerStudent.getUsername(), "password", WhiteboardSecurityTransportDbTest.PASSWORD)));
            Instant deadline = Instant.now().plus(Duration.ofMinutes(30));
            while (!Files.exists(stop) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        } finally { fixture.cleanup(); }
    }
}

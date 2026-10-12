package com.morales.chemicallab.security;

import org.junit.jupiter.api.Test;
import com.morales.chemicallab.security.files.FileFixtures;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

/** Opt-in fixture for the real teacher/student browser flow. Not part of the default suite. */
class FileBrowserFixture {
    @Test void serveDisposableBrowserFixture() throws Exception {
        Path info=Path.of(System.getProperty("t06.fixture-file"));
        Path stop=Path.of(System.getProperty("t06.stop-file"));
        var fixture=new FileDeliveryHttpDbTest();
        try {
            fixture.start(); fixture.fixture();
            Files.write(info.resolveSibling("t06-valid.pdf"),FileFixtures.pdf());
            Files.write(info.resolveSibling("t06-valid.png"),FileFixtures.png());
            Files.writeString(info.resolveSibling("t06-false.pdf"),"Texto ficticio inocuo SEC-10");
            Files.writeString(info,fixture.host.json.writeValueAsString(Map.of(
                    "origin",fixture.host.origin,"conceptId",fixture.concept,"boardId",fixture.host.board,
                    "teacher",fixture.host.teacher.getUsername(),"student",fixture.host.student.getUsername(),
                    "password",WhiteboardSecurityTransportDbTest.PASSWORD)));
            Instant deadline=Instant.now().plus(Duration.ofMinutes(30));
            while(!Files.exists(stop)&&Instant.now().isBefore(deadline)) Thread.sleep(250);
        } finally { fixture.cleanup(); }
    }
}

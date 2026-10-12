package com.morales.chemicallab.security;

import com.morales.chemicallab.service.EvaluationService;
import com.morales.chemicallab.dto.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Opt-in loopback fixture. Files control only the test JVM; no clock endpoint ships in the API. */
class EvaluationBrowserFixture {
    @Test void serveDisposableFixture() throws Exception {
        var fixture = new EvaluationWindowHttpDbTest();
        Path info = Path.of(System.getProperty("t05.fixture-file"));
        Path stop = Path.of(System.getProperty("t05.stop-file"));
        Path command = Path.of(System.getProperty("t05.command-file"));
        try {
            fixture.startHost(); fixture.fixtures();
            var host = fixture.host;
            Files.writeString(info, host.json.writeValueAsString(Map.of("origin", host.origin,
                    "teacher", host.teacher.getUsername(), "student", host.student.getUsername(),
                    "password", WhiteboardSecurityTransportDbTest.PASSWORD, "evaluationId", fixture.exam)));
            Instant deadline = Instant.now().plus(Duration.ofMinutes(30));
            String last = "";
            while (!Files.exists(stop) && Instant.now().isBefore(deadline)) {
                if (Files.exists(command)) {
                    String input = Files.readString(command);
                    if (!input.equals(last)) {
                        var json = host.json.readTree(input);
                        fixture.clock.at(LocalDateTime.parse(json.path("time").asText()));
                        if (json.path("grade").asBoolean()) {
                            var service = host.app.getBean(EvaluationService.class);
                            var attempts = host.sql().queryForList("select id from evaluation_attempts where evaluation_id=? and status <> 'IN_PROGRESS'", Long.class, fixture.exam);
                            for (long attempt : attempts) {
                                var answers = host.sql().queryForList("select a.id from evaluation_answers a join evaluation_questions q on q.id=a.question_id where a.attempt_id=? and q.question_type='OPEN_TEXT'", Long.class, attempt);
                                for (long answer : answers) service.manualGradeAnswer(host.teacher.getUsername(), attempt, answer, new ManualGradeRequest(3, "Retroalimentación ficticia"));
                                service.completeReview(host.teacher.getUsername(), attempt);
                                service.closeGrade(host.teacher.getUsername(), attempt);
                            }
                        }
                        last = input;
                    }
                }
                Thread.sleep(200);
            }
        } finally { fixture.closeHost(); }
    }
}

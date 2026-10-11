package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Full HTTP/filter/session/JPA stack, guarded disposable PostgreSQL, controlled academic clock. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvaluationWindowHttpDbTest {
    static final MutableClock CLOCK = new MutableClock();
    final MutableClock clock = CLOCK;
    final WhiteboardSecurityTransportDbTest host = new WhiteboardSecurityTransportDbTest() {
        @Override ConfigurableApplicationContext startApp(String... extra) {
            return System.getProperty("t05.fixture-file") == null ? super.startApp(extra) : super.startApp("--server.port=18088");
        }
        @Override Class<?>[] applicationSources() {
            return new Class<?>[] { com.morales.chemicallab.ChemicalLabBackendApplication.class, ControlledClockConfig.class };
        }
    };
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ControlledClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        EvaluationAccessPolicy controlledEvaluationPolicy() { return new EvaluationAccessPolicy(CLOCK); }
    }
    final LocalDateTime start = LocalDateTime.of(2030, 6, 3, 10, 0);
    long exam, a, b, objective, open, right, wrong;
    String teacherToken, pupilToken, peerToken, outsiderToken;

    static class MutableClock extends Clock {
        volatile Instant value = Instant.parse("2030-06-03T14:59:59Z");
        @Override public ZoneId getZone() { return ZoneId.of("America/Lima"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
        void at(LocalDateTime time) { value = time.atZone(getZone()).toInstant(); }
    }

    @BeforeAll void startHost() throws Exception { host.start(); }
    @AfterAll void closeHost() throws Exception { host.cleanup(); }
    @BeforeEach void fixtures() throws Exception {
        clock.at(start.minusSeconds(1));
        host.teacher = host.account(Role.DOCENTE, null, "3", "A");
        host.student = host.account(Role.ESTUDIANTE, host.teacher, "3", "A");
        host.peerStudent = host.account(Role.ESTUDIANTE, host.teacher, "3", "B");
        host.outsider = host.account(Role.ESTUDIANTE, host.teacher, "4", "C");
        teacherToken = host.login(host.teacher); pupilToken = host.login(host.student);
        peerToken = host.login(host.peerStudent); outsiderToken = host.login(host.outsider);
        var service = host.app.getBean(EvaluationService.class);
        exam = service.createEvaluation(host.teacher.getUsername(), new CreateEvaluationRequest(
                "T05 fictitious", "Programación ficticia", "Responde", "Óxidos", 2, 10, false, false,
                QuestionDisplayMode.ALL_AT_ONCE, false, true)).id();
        objective = service.addQuestion(host.teacher.getUsername(), exam, new CreateQuestionRequest(
                "Pregunta objetiva ficticia", QuestionType.MULTIPLE_CHOICE, 4, 0, "Explicación reservada", null, true,
                List.of(new CreateOptionRequest("CORRECTA-RESERVADA", true, 0), new CreateOptionRequest("Otra", false, 1)))).id();
        open = service.addQuestion(host.teacher.getUsername(), exam, new CreateQuestionRequest(
                "Pregunta abierta ficticia", QuestionType.OPEN_TEXT, 6, 1, "Explicación abierta", "CRITERIO-RESERVADO", true, null)).id();
        var options = host.app.getBean(EvaluationOptionRepository.class).findAll().stream()
                .filter(o -> o.getQuestion().getId().equals(objective)).toList();
        right = options.stream().filter(EvaluationOption::getCorrect).findFirst().orElseThrow().getId();
        wrong = options.stream().filter(o -> !o.getCorrect()).findFirst().orElseThrow().getId();
        service.publishEvaluation(host.teacher.getUsername(), exam);
        a = service.assignEvaluationToSection(host.teacher.getUsername(), exam,
                new AssignEvaluationRequest("3", "A", start, start.plusMinutes(5))).id();
        b = service.assignEvaluationToSection(host.teacher.getUsername(), exam,
                new AssignEvaluationRequest("3", "B", start.plusMinutes(2), start.plusMinutes(15))).id();
    }

    JsonNode ok(String method, String suffix, String token, String body) throws Exception {
        var response = host.request(method, "/api/evaluations/" + suffix, token, body);
        assertThat(response.statusCode()).as(method + " " + suffix + " " + response.body()).isBetween(200, 201);
        return host.json.readTree(response.body());
    }
    void rejected(String method, String suffix, String token, String body) throws Exception {
        var response = host.request(method, "/api/evaluations/" + suffix, token, body);
        assertThat(response.statusCode()).as(response.body()).isBetween(400, 404);
    }
    long begin(String token) throws Exception { return ok("POST", "student/" + exam + "/attempts", token, "{}").path("id").asLong(); }
    String answer(long question, Long option, String text) throws Exception {
        var data = new LinkedHashMap<String,Object>(); data.put("questionId", question);
        if (option != null) data.put("selectedOptionId", option);
        if (text != null) data.put("answerText", text);
        return host.json.writeValueAsString(data);
    }
    void saved(long attempt, long question, Long option, String text) throws Exception {
        hidden(ok("POST", "student/attempts/" + attempt + "/answers", pupilToken, answer(question, option, text)));
    }
    void hidden(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(e -> {
            if (Set.of("score","percentage","finalScore","correct","pointsAwarded","correctOptionText",
                    "explanation","expectedAnswer","teacherFeedback","overallFeedback").contains(e.getKey()))
                assertThat(e.getValue().isNull()).as(e.getKey() + " must be null: " + node).isTrue();
            hidden(e.getValue());
        });
        else if (node.isArray()) node.forEach(this::hidden);
    }
    void allRepresentationsHidden(long attempt) throws Exception {
        hidden(ok("GET", "student/attempts/" + attempt, pupilToken, null));
        hidden(ok("GET", "student/attempts/" + attempt + "/result", pupilToken, null));
        hidden(ok("GET", "student/results", pupilToken, null));
        hidden(ok("GET", "student", pupilToken, null));
    }

    @Test void exactWindowBoundariesAndRejectedRequestsDoNotMutate() throws Exception {
        var detail = ok("GET", "student/" + exam, pupilToken, null);
        assertThat(detail.path("questions").size()).isZero();
        assertThat(detail.path("accessState").asText()).isEqualTo("SCHEDULED");
        rejected("POST", "student/" + exam + "/attempts", pupilToken, "{}");
        assertThat(host.sql().queryForObject("select count(*) from evaluation_attempts where evaluation_id=?", Integer.class, exam)).isZero();
        rejected("GET", "student/" + exam, outsiderToken, null);
        clock.at(start);
        assertThat(ok("GET", "student/" + exam, pupilToken, null).path("questions").size()).isEqualTo(2);
        long attempt = begin(pupilToken);
        assertThat(ok("GET", "student/attempts/" + attempt, pupilToken, null).path("startedAt").asText()).isEqualTo("2030-06-03T10:00:00");
        rejected("GET", "student/attempts/" + attempt, peerToken, null);
        rejected("POST", "student/attempts/" + attempt + "/answers", peerToken, answer(objective, right, null));
        saved(attempt, objective, wrong, null);
        clock.at(start.plusMinutes(5));
        rejected("POST", "student/" + exam + "/attempts", pupilToken, "{}");
        rejected("POST", "student/attempts/" + attempt + "/answers", pupilToken, answer(objective, right, null));
        assertThat(host.sql().queryForObject("select selected_option_id from evaluation_answers where attempt_id=? and question_id=?", Long.class, attempt, objective)).isEqualTo(wrong);
        // Missing required open answer cannot prevent finalization once the window closed.
        var submitted = ok("POST", "student/attempts/" + attempt + "/submit", pupilToken,
                "{\"answers\":[" + answer(objective, right, null) + "]}");
        hidden(submitted);
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(host.sql().queryForObject("select selected_option_id from evaluation_answers where attempt_id=? and question_id=?", Long.class, attempt, objective)).isEqualTo(wrong);
        allRepresentationsHidden(attempt);
        clock.at(start.plusMinutes(5).plusNanos(1));
        assertThat(ok("GET", "student/" + exam, pupilToken, null).path("questions").size()).isZero();
    }

    @Test void partialOpenGradeAdjustmentsAndTwoSectionsUseSameExposurePolicy() throws Exception {
        clock.at(start);
        long attempt = begin(pupilToken);
        saved(attempt, objective, right, null); saved(attempt, open, null, "Mi respuesta propia");
        hidden(ok("POST", "student/attempts/" + attempt + "/submit", pupilToken, "{}"));
        allRepresentationsHidden(attempt);
        var service = host.app.getBean(EvaluationService.class);
        long answerId = host.sql().queryForObject("select id from evaluation_answers where attempt_id=? and question_id=?", Long.class, attempt, open);
        service.manualGradeAnswer(host.teacher.getUsername(), attempt, answerId, new ManualGradeRequest(3, "FEEDBACK-RESERVADO"));
        service.completeReview(host.teacher.getUsername(), attempt);
        service.addAdjustment(host.teacher.getUsername(), attempt, new CreateAdjustmentRequest(new java.math.BigDecimal("1.5"), "Ajuste ficticio"));
        service.updateOverallFeedback(host.teacher.getUsername(), attempt, new UpdateAttemptFeedbackRequest("GENERAL-RESERVADO"));
        allRepresentationsHidden(attempt); // graded but not closed
        clock.at(start.plusMinutes(5));
        service.closeGrade(host.teacher.getUsername(), attempt);
        allRepresentationsHidden(attempt); // A closed, B may still respond/retry
        clock.at(start.plusMinutes(14));
        long peerAttempt = begin(peerToken); // IN_PROGRESS crosses B's exclusive deadline
        clock.at(start.plusMinutes(15));
        rejected("POST", "student/attempts/" + peerAttempt + "/answers", peerToken, answer(objective, right, null));
        var visible = ok("GET", "student/attempts/" + attempt, pupilToken, null);
        assertThat(visible.path("score").asInt()).isEqualTo(7);
        assertThat(visible.path("answers").get(0).path("correct").asBoolean()).isTrue();
        var result = ok("GET", "student/attempts/" + attempt + "/result", pupilToken, null);
        assertThat(result.path("finalScore").asDouble()).isEqualTo(15.5);
        assertThat(result.path("answers").get(1).path("pointsAwarded").asInt()).isEqualTo(3);
        assertThat(result.path("overallFeedback").asText()).isEqualTo("GENERAL-RESERVADO");
        assertThat(ok("GET", "student/results", pupilToken, null).get(0).path("finalScore").asDouble()).isEqualTo(15.5);
        assertThat(ok("GET", "student/attempts/" + peerAttempt, peerToken, null).path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
    }

    @Test void absentDatesInvalidIntervalsAndMultipleAttempts() throws Exception {
        rejected("POST", "teacher/" + exam + "/assignments", teacherToken,
                "{\"grade\":\"4\",\"section\":\"C\",\"startAt\":\"2030-06-03T10:00:00\",\"dueAt\":\"2030-06-03T10:00:00\"}");
        assertThat(host.sql().queryForObject("select count(*) from evaluation_assignments where evaluation_id=?", Integer.class, exam)).isEqualTo(2);
        host.sql().update("update evaluation_assignments set start_at=null,due_at=null where id=?", a);
        long first = begin(pupilToken);
        saved(first, objective, right, null); saved(first, open, null, "Texto");
        hidden(ok("POST", "student/attempts/" + first + "/submit", pupilToken, "{}"));
        long second = begin(pupilToken);
        hidden(ok("POST", "student/attempts/" + second + "/exit", pupilToken, "{}"));
        rejected("POST", "student/" + exam + "/attempts", pupilToken, "{}");
        allRepresentationsHidden(first); // B still scheduled and retries pending
        assertThat(host.sql().queryForObject("select count(*) from evaluation_attempts where evaluation_id=?", Integer.class, exam)).isEqualTo(2);
    }

    @Test void durationHasNoAnswerGraceAndRecoveryFinalizesSavedAnswers() throws Exception {
        host.sql().update("update evaluation_assignments set due_at=null where id=?", a);
        clock.at(start);
        long attempt = begin(pupilToken);
        clock.at(start.plusMinutes(10).minusNanos(1));
        saved(attempt, objective, wrong, null);
        clock.at(start.plusMinutes(10));
        rejected("POST", "student/attempts/" + attempt + "/answers", pupilToken, answer(objective, right, null));
        assertThat(ok("GET", "student/attempts/" + attempt, pupilToken, null).path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(host.sql().queryForObject("select selected_option_id from evaluation_answers where attempt_id=? and question_id=?", Long.class, attempt, objective)).isEqualTo(wrong);
    }

    @Test void archiveAndDeactivationRejectWritesButPermitSubmitAndExit() throws Exception {
        clock.at(start);
        long attempt = begin(pupilToken);
        saved(attempt, objective, wrong, null);
        ok("PATCH", "teacher/" + exam + "/assignments/" + a + "/deactivate", teacherToken, "{}");
        rejected("POST", "student/attempts/" + attempt + "/answers", pupilToken, answer(objective, right, null));
        hidden(ok("POST", "student/attempts/" + attempt + "/submit", pupilToken, "{}"));
        clock.at(start.plusMinutes(2));
        long peer = begin(peerToken);
        ok("PATCH", "teacher/" + exam + "/archive", teacherToken, "{}");
        rejected("POST", "student/attempts/" + peer + "/answers", peerToken, answer(objective, right, null));
        assertThat(ok("POST", "student/attempts/" + peer + "/exit", peerToken, "{}").path("status").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
        rejected("POST", "student/" + exam + "/attempts", peerToken, "{}");
    }

    @Test void groupClosedStillHidesPartialGradeAndSequentialSaveCannotCrossDueAt() throws Exception {
        host.sql().update("update evaluations set question_display_mode='ONE_BY_ONE' where id=?", exam);
        clock.at(start); long attempt = begin(pupilToken);
        rejected("POST", "student/attempts/" + attempt + "/answers", pupilToken, answer(open, null, "Fuera de orden"));
        assertThat(host.sql().queryForObject("select current_question_index from evaluation_attempts where id=?", Integer.class, attempt)).isZero();
        saved(attempt, objective, right, null);
        clock.at(start.plusMinutes(5));
        rejected("POST", "student/attempts/" + attempt + "/answers", pupilToken, answer(open, null, "Tardía"));
        assertThat(host.sql().queryForObject("select current_question_index from evaluation_attempts where id=?", Integer.class, attempt)).isEqualTo(1);
        hidden(ok("POST", "student/attempts/" + attempt + "/submit", pupilToken, "{}"));
        clock.at(start.plusMinutes(15));
        long answerId = host.sql().queryForObject("select id from evaluation_answers where attempt_id=? and question_id=?", Long.class, attempt, open);
        var service = host.app.getBean(EvaluationService.class);
        service.manualGradeAnswer(host.teacher.getUsername(), attempt, answerId, new ManualGradeRequest(2, "Reservado"));
        service.completeReview(host.teacher.getUsername(), attempt);
        allRepresentationsHidden(attempt);
        assertThat(ok("GET", "student/attempts/" + attempt + "/result", pupilToken, null).path("canViewDetailedFeedback").asBoolean()).isFalse();
        service.closeGrade(host.teacher.getUsername(), attempt);
        assertThat(ok("GET", "student/attempts/" + attempt, pupilToken, null).path("score").asInt()).isEqualTo(6);
        // Once outcomes exist, neither a new section nor more retries/duration may reopen opportunities.
        rejected("POST", "teacher/" + exam + "/assignments", teacherToken, "{\"grade\":\"4\",\"section\":\"C\"}");
        rejected("PUT", "teacher/" + exam, teacherToken, "{\"title\":\"Reabrir\",\"maxAttempts\":3,\"timeLimitMinutes\":20,\"questionDisplayMode\":\"ONE_BY_ONE\"}");
        assertThat(host.sql().queryForObject("select max_attempts from evaluations where id=?", Integer.class, exam)).isEqualTo(2);
    }

    @Test void concurrentSaveSubmitAndArchivePreserveOneFinalAnswer() throws Exception {
        clock.at(start);
        long attempt = begin(pupilToken);
        saved(attempt, objective, wrong, null);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var gate = new CountDownLatch(1);
            List<Callable<HttpResponse<String>>> actions = List.of(
                    () -> { gate.await(); return host.request("POST", "/api/evaluations/student/attempts/" + attempt + "/answers", pupilToken, answer(objective, right, null)); },
                    () -> { gate.await(); return host.request("POST", "/api/evaluations/student/attempts/" + attempt + "/submit", pupilToken, "{}"); },
                    () -> { gate.await(); return host.request("PATCH", "/api/evaluations/teacher/" + exam + "/archive", teacherToken, "{}"); });
            var futures = actions.stream().map(pool::submit).toList(); gate.countDown();
            for (var f : futures) assertThat(f.get(20, TimeUnit.SECONDS).statusCode()).isIn(200, 400);
            ok("GET", "student/attempts/" + attempt, pupilToken, null); // finalize if normal submit was rejected on required open
            var snapshot = host.sql().queryForMap("select score,status from evaluation_attempts where id=?", attempt);
            long selected = host.sql().queryForObject("select selected_option_id from evaluation_answers where attempt_id=? and question_id=?", Long.class, attempt, objective);
            assertThat(snapshot.get("status")).isEqualTo("PENDING_MANUAL_REVIEW");
            assertThat(snapshot.get("score")).isEqualTo(selected == right ? 4 : 0);
            rejected("POST", "student/attempts/" + attempt + "/answers", pupilToken, answer(objective, selected == right ? wrong : right, null));
        } finally { pool.shutdownNow(); }
    }
}

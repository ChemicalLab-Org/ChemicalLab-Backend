package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.AttemptEventType;
import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationAttemptEvent;
import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.EvaluationAttemptEventRepository;
import com.morales.chemicallab.repository.EvaluationAttemptRepository;
import com.morales.chemicallab.repository.EvaluationRepository;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pruebas de integración de la persistencia de eventos de intento contra la base de datos
 * real.
 *
 * <p>Reproducen y blindan la regresión por la que iniciar un intento devolvía HTTP 500: la
 * restricción CHECK heredada {@code evaluation_attempt_events_event_type_check}, generada
 * cuando {@code AttemptEventType} tenía solo cuatro valores de foco, rechazaba cualquier
 * tipo nuevo (p. ej. {@code ATTEMPT_STARTED} al iniciar el intento). Como
 * {@code ddl-auto=update} no actualiza ese CHECK al crecer el enum,
 * {@code EvaluationSchemaMigration} lo elimina al arrancar.</p>
 */
@SpringBootTest
@Transactional
class EvaluationAttemptEventPersistenceDbTest {

    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private TeacherProfileRepository teacherProfileRepository;
    @Autowired
    private StudentProfileRepository studentProfileRepository;
    @Autowired
    private EvaluationRepository evaluationRepository;
    @Autowired
    private EvaluationAttemptRepository attemptRepository;
    @Autowired
    private EvaluationAttemptEventRepository attemptEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noExisteElCheckHeredadoDeEventType() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint "
                        + "WHERE conname = 'evaluation_attempt_events_event_type_check'",
                Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void persisteEventosDeLosTiposNuevos() {
        EvaluationAttempt attempt = persistAttempt();

        // ATTEMPT_STARTED es el tipo que provocaba el 500 al iniciar el intento. Con saveAndFlush
        // el INSERT es inmediato: si el CHECK heredado siguiera vivo, fallaría aquí.
        assertThatCode(() -> attemptEventRepository.saveAndFlush(
                EvaluationAttemptEvent.builder()
                        .attempt(attempt)
                        .eventType(AttemptEventType.ATTEMPT_STARTED)
                        .description("El estudiante inició el intento.")
                        .build()))
                .doesNotThrowAnyException();

        // Uso de herramienta con metadata segura y un intento de salida.
        assertThatCode(() -> attemptEventRepository.saveAndFlush(
                EvaluationAttemptEvent.builder()
                        .attempt(attempt)
                        .eventType(AttemptEventType.TOOL_OPENED)
                        .metadata("tool=PERIODIC_TABLE")
                        .build()))
                .doesNotThrowAnyException();

        assertThatCode(() -> attemptEventRepository.saveAndFlush(
                EvaluationAttemptEvent.builder()
                        .attempt(attempt)
                        .eventType(AttemptEventType.EXIT_ATTEMPTED)
                        .metadata("source=BUTTON_EXIT")
                        .build()))
                .doesNotThrowAnyException();

        assertThat(attemptEventRepository.countByAttempt(attempt)).isEqualTo(3L);
    }

    private EvaluationAttempt persistAttempt() {
        UserAccount teacherUser = userAccountRepository.saveAndFlush(UserAccount.builder()
                .username("docente-trace-" + System.nanoTime())
                .password("x").role(Role.DOCENTE).active(true).temporaryPassword(false).build());
        TeacherProfile teacher = teacherProfileRepository.saveAndFlush(TeacherProfile.builder()
                .user(teacherUser).names("Docente").lastNames("De prueba").build());

        UserAccount studentUser = userAccountRepository.saveAndFlush(UserAccount.builder()
                .username("est-trace-" + System.nanoTime())
                .password("x").role(Role.ESTUDIANTE).active(true).temporaryPassword(false).build());
        StudentProfile student = studentProfileRepository.saveAndFlush(StudentProfile.builder()
                .user(studentUser).teacher(teacher).studentCode(studentUser.getUsername())
                .names("Luis").lastNames("Torres").grade("3").section("A").build());

        Evaluation evaluation = evaluationRepository.saveAndFlush(Evaluation.builder()
                .title("Evaluación de trazabilidad")
                .createdByTeacher(teacher)
                .status(EvaluationStatus.PUBLISHED)
                .maxAttempts(1)
                .active(true)
                .build());

        return attemptRepository.saveAndFlush(EvaluationAttempt.builder()
                .evaluation(evaluation).student(student).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build());
    }
}

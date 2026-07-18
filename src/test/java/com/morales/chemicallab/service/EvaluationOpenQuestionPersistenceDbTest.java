package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationQuestion;
import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.QuestionType;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.EvaluationAttemptRepository;
import com.morales.chemicallab.repository.EvaluationQuestionRepository;
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
 * Pruebas de integración de la persistencia de preguntas abiertas y calificación manual
 * contra la base de datos real.
 *
 * <p>Reproducen y blindan la regresión por la que crear una pregunta de respuesta abierta
 * devolvía HTTP 500: la restricción CHECK heredada
 * {@code evaluation_questions_question_type_check}, generada cuando {@code QuestionType}
 * solo tenía {@code MULTIPLE_CHOICE}, rechazaba el valor nuevo {@code OPEN_TEXT}. Como
 * {@code ddl-auto=update} no actualiza ese CHECK al crecer el enum,
 * {@link com.morales.chemicallab.config.EvaluationSchemaMigration} lo elimina al arrancar,
 * junto con los CHECK equivalentes de {@code evaluation_attempts.status} (que ahora admite
 * {@code PENDING_MANUAL_REVIEW}) y {@code system_logs.event_type}.</p>
 */
@SpringBootTest
@Transactional
class EvaluationOpenQuestionPersistenceDbTest {

    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private TeacherProfileRepository teacherProfileRepository;
    @Autowired
    private StudentProfileRepository studentProfileRepository;
    @Autowired
    private EvaluationRepository evaluationRepository;
    @Autowired
    private EvaluationQuestionRepository questionRepository;
    @Autowired
    private EvaluationAttemptRepository attemptRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noExistenLosCheckHeredadosDeEnumsAmpliados() {
        assertThat(countConstraint("evaluation_questions_question_type_check")).isZero();
        assertThat(countConstraint("evaluation_attempts_status_check")).isZero();
        assertThat(countConstraint("system_logs_event_type_check")).isZero();
    }

    @Test
    void persisteUnaPreguntaAbiertaConCriterio() {
        Evaluation evaluation = persistEvaluation();

        // OPEN_TEXT es el valor que provocaba el 500 al crear la pregunta. Con saveAndFlush
        // el INSERT es inmediato: si el CHECK heredado siguiera vivo, fallaría aquí.
        assertThatCode(() -> questionRepository.saveAndFlush(
                EvaluationQuestion.builder()
                        .evaluation(evaluation)
                        .questionText("Explica la formación del óxido de calcio.")
                        .questionType(QuestionType.OPEN_TEXT)
                        .points(5)
                        .orderIndex(0)
                        .expectedAnswer("Debe mencionar la reacción del metal con oxígeno.")
                        .required(true)
                        .active(true)
                        .build()))
                .doesNotThrowAnyException();
    }

    @Test
    void persisteUnaPreguntaAbiertaSinCriterio() {
        Evaluation evaluation = persistEvaluation();

        // Pregunta abierta mínima: enunciado + puntaje, sin criterio ni alternativas.
        assertThatCode(() -> questionRepository.saveAndFlush(
                EvaluationQuestion.builder()
                        .evaluation(evaluation)
                        .questionText("Responde")
                        .questionType(QuestionType.OPEN_TEXT)
                        .points(5)
                        .orderIndex(0)
                        .required(true)
                        .active(true)
                        .build()))
                .doesNotThrowAnyException();
    }

    @Test
    void persisteUnaPreguntaDeOpcionMultiple() {
        Evaluation evaluation = persistEvaluation();

        // Las preguntas de alternativa única deben seguir funcionando sin cambios.
        assertThatCode(() -> questionRepository.saveAndFlush(
                EvaluationQuestion.builder()
                        .evaluation(evaluation)
                        .questionText("¿Fórmula del óxido de calcio?")
                        .questionType(QuestionType.MULTIPLE_CHOICE)
                        .points(1)
                        .orderIndex(0)
                        .required(true)
                        .active(true)
                        .build()))
                .doesNotThrowAnyException();
    }

    @Test
    void persisteUnIntentoPendienteDeRevisionManual() {
        Evaluation evaluation = persistEvaluation();
        StudentProfile student = persistStudent(evaluation.getCreatedByTeacher());

        // Al enviar un intento con preguntas abiertas el estado pasa a PENDING_MANUAL_REVIEW.
        // Ese valor no existía en el CHECK heredado de status y habría provocado otro 500.
        assertThatCode(() -> attemptRepository.saveAndFlush(
                EvaluationAttempt.builder()
                        .evaluation(evaluation)
                        .student(student)
                        .attemptNumber(1)
                        .status(AttemptStatus.PENDING_MANUAL_REVIEW)
                        .active(true)
                        .build()))
                .doesNotThrowAnyException();
    }

    private Integer countConstraint(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class, name);
    }

    private TeacherProfile persistTeacher() {
        UserAccount teacherUser = userAccountRepository.saveAndFlush(UserAccount.builder()
                .username("docente-open-" + System.nanoTime())
                .password("x").role(Role.DOCENTE).active(true).temporaryPassword(false).build());
        return teacherProfileRepository.saveAndFlush(TeacherProfile.builder()
                .user(teacherUser).names("Docente").lastNames("De prueba").build());
    }

    private StudentProfile persistStudent(TeacherProfile teacher) {
        UserAccount studentUser = userAccountRepository.saveAndFlush(UserAccount.builder()
                .username("est-open-" + System.nanoTime())
                .password("x").role(Role.ESTUDIANTE).active(true).temporaryPassword(false).build());
        return studentProfileRepository.saveAndFlush(StudentProfile.builder()
                .user(studentUser).teacher(teacher).studentCode(studentUser.getUsername())
                .names("Luis").lastNames("Torres").grade("3").section("A").build());
    }

    private Evaluation persistEvaluation() {
        TeacherProfile teacher = persistTeacher();
        return evaluationRepository.saveAndFlush(Evaluation.builder()
                .title("Evaluación con preguntas abiertas")
                .createdByTeacher(teacher)
                .status(EvaluationStatus.DRAFT)
                .maxAttempts(1)
                .active(true)
                .build());
    }
}

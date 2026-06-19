package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pruebas unitarias del servicio de evaluaciones. Se mockean los repositorios para
 * validar la lógica de negocio: creación y publicación con sus reglas, asignación a
 * secciones, visibilidad por grado/sección del estudiante, omisión del campo
 * "correct" en la vista de estudiante y el ciclo de intentos (inicio, guardado y
 * envío) con sus restricciones.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock
    private EvaluationRepository evaluationRepository;
    @Mock
    private EvaluationQuestionRepository questionRepository;
    @Mock
    private EvaluationOptionRepository optionRepository;
    @Mock
    private EvaluationAssignmentRepository assignmentRepository;
    @Mock
    private EvaluationAttemptRepository attemptRepository;
    @Mock
    private EvaluationAnswerRepository answerRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TeacherProfileRepository teacherProfileRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;

    @InjectMocks
    private EvaluationService service;

    // =========================================================================
    // Datos de apoyo
    // =========================================================================

    private TeacherProfile teacher(Long id, String username) {
        UserAccount user = UserAccount.builder()
                .id(id).username(username).role(Role.DOCENTE).active(true).build();
        return TeacherProfile.builder()
                .id(id).user(user).names("Ana").lastNames("Quispe").build();
    }

    private StudentProfile student(Long id, String code, String grade, String section) {
        UserAccount user = UserAccount.builder()
                .id(id).username(code).role(Role.ESTUDIANTE).active(true).build();
        return StudentProfile.builder()
                .id(id).user(user).studentCode(code).names("Luis").lastNames("Torres")
                .grade(grade).section(section).build();
    }

    private Evaluation evaluation(Long id, TeacherProfile owner, EvaluationStatus status, int maxAttempts) {
        return Evaluation.builder()
                .id(id)
                .title("Óxidos y nomenclatura")
                .description("Evaluación de óxidos.")
                .maxAttempts(maxAttempts)
                .createdByTeacher(owner)
                .status(status)
                .active(true)
                .build();
    }

    private EvaluationQuestion question(Long id, Evaluation evaluation, int points) {
        return EvaluationQuestion.builder()
                .id(id).evaluation(evaluation).questionText("¿Cuál es la fórmula del óxido de calcio?")
                .questionType(QuestionType.MULTIPLE_CHOICE).points(points).orderIndex(0).active(true).build();
    }

    private EvaluationOption option(Long id, EvaluationQuestion question, boolean correct) {
        return EvaluationOption.builder()
                .id(id).question(question).optionText("CaO").correct(correct).orderIndex(0).active(true).build();
    }

    private EvaluationAssignment assignment(Long id, Evaluation evaluation, TeacherProfile teacher,
                                            String grade, String section) {
        return EvaluationAssignment.builder()
                .id(id).evaluation(evaluation).teacher(teacher).grade(grade).section(section).active(true).build();
    }

    private void stubTeacher(TeacherProfile teacher) {
        when(userAccountRepository.findByUsername(teacher.getUser().getUsername()))
                .thenReturn(Optional.of(teacher.getUser()));
        when(teacherProfileRepository.findByUser(teacher.getUser()))
                .thenReturn(Optional.of(teacher));
    }

    private void stubStudent(StudentProfile student) {
        when(userAccountRepository.findByUsername(student.getUser().getUsername()))
                .thenReturn(Optional.of(student.getUser()));
        when(studentProfileRepository.findByStudentCode(student.getStudentCode()))
                .thenReturn(Optional.of(student));
    }

    // =========================================================================
    // 1. Docente crea evaluación
    // =========================================================================

    @Test
    void docenteCreaEvaluacionEnBorrador() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(evaluationRepository.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.countByEvaluationAndActiveTrue(any())).thenReturn(0L);
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(any())).thenReturn(List.of());

        var request = new CreateEvaluationRequest("  Óxidos y nomenclatura  ", "Desc", "Instrucciones", "Óxidos", 2, 30);
        EvaluationResponse response = service.createEvaluation("docente1", request);

        assertThat(response.title()).isEqualTo("Óxidos y nomenclatura");
        assertThat(response.status()).isEqualTo(EvaluationStatus.DRAFT);
        assertThat(response.maxAttempts()).isEqualTo(2);
        verify(evaluationRepository).save(any(Evaluation.class));
    }

    // =========================================================================
    // 2. Docente agrega pregunta con alternativas
    // =========================================================================

    @Test
    void docenteAgregaPreguntaConAlternativas() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(questionRepository.save(any(EvaluationQuestion.class))).thenAnswer(inv -> inv.getArgument(0));
        EvaluationQuestion q = question(20L, eval, 1);
        // Primera consulta (replaceOptions): sin alternativas previas. Segunda (mapeo): las creadas.
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(any()))
                .thenReturn(List.of(), List.of(option(31L, q, false), option(32L, q, true)));

        var request = new CreateQuestionRequest(
                "¿Cuál es la fórmula del óxido de calcio?", QuestionType.MULTIPLE_CHOICE, 1, 0, null,
                List.of(new CreateOptionRequest("Ca2O", false, 0), new CreateOptionRequest("CaO", true, 1)));

        QuestionResponse response = service.addQuestion("docente1", 10L, request);

        assertThat(response.options()).hasSize(2);
        verify(questionRepository).save(any(EvaluationQuestion.class));
    }

    // =========================================================================
    // 3. No se publica una evaluación sin preguntas
    // =========================================================================

    @Test
    void noPublicaEvaluacionSinPreguntas() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of());

        assertThatThrownBy(() -> service.publishEvaluation("docente1", 10L))
                .hasMessageContaining("sin preguntas");
        verify(evaluationRepository, never()).save(any(Evaluation.class));
    }

    // =========================================================================
    // 4. No se publica una pregunta sin alternativa correcta
    // =========================================================================

    @Test
    void noPublicaPreguntaSinAlternativaCorrecta() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        EvaluationQuestion q = question(20L, eval, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(option(31L, q, false), option(32L, q, false)));

        assertThatThrownBy(() -> service.publishEvaluation("docente1", 10L))
                .hasMessageContaining("exactamente una alternativa correcta");
    }

    // =========================================================================
    // 5. No se publica una pregunta con más de una alternativa correcta
    // =========================================================================

    @Test
    void noPublicaPreguntaConMasDeUnaCorrecta() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        EvaluationQuestion q = question(20L, eval, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(option(31L, q, true), option(32L, q, true)));

        assertThatThrownBy(() -> service.publishEvaluation("docente1", 10L))
                .hasMessageContaining("exactamente una alternativa correcta");
    }

    // =========================================================================
    // 6. Docente publica una evaluación válida
    // =========================================================================

    @Test
    void docentePublicaEvaluacionValida() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        EvaluationQuestion q = question(20L, eval, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(option(31L, q, false), option(32L, q, true)));
        when(evaluationRepository.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(eval)).thenReturn(List.of());

        EvaluationDetailResponse response = service.publishEvaluation("docente1", 10L);

        assertThat(response.status()).isEqualTo(EvaluationStatus.PUBLISHED);
        assertThat(response.questions()).hasSize(1);
    }

    // =========================================================================
    // 7. Docente asigna evaluación a sección
    // =========================================================================

    @Test
    void docenteAsignaEvaluacionASeccion() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(assignmentRepository.existsByEvaluationAndGradeAndSectionAndActiveTrue(eval, "3", "A"))
                .thenReturn(false);
        when(assignmentRepository.save(any(EvaluationAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        EvaluationAssignmentResponse response = service.assignEvaluationToSection(
                "docente1", 10L, new AssignEvaluationRequest("3", "A", null, null));

        assertThat(response.grade()).isEqualTo("3");
        assertThat(response.section()).isEqualTo("A");
        assertThat(response.active()).isTrue();
    }

    // =========================================================================
    // 8. No se duplica una asignación activa
    // =========================================================================

    @Test
    void noSeDuplicaAsignacionActiva() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(assignmentRepository.existsByEvaluationAndGradeAndSectionAndActiveTrue(eval, "3", "A"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.assignEvaluationToSection(
                "docente1", 10L, new AssignEvaluationRequest("3", "A", null, null)))
                .hasMessageContaining("Ya existe una asignación activa");
        verify(assignmentRepository, never()).save(any(EvaluationAssignment.class));
    }

    // =========================================================================
    // 9. Estudiante de la sección ve la evaluación publicada
    // =========================================================================

    @Test
    void estudianteDeLaSeccionVeEvaluacion() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        when(assignmentRepository.findActiveForSection("3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(List.of(asig));
        when(questionRepository.countByEvaluationAndActiveTrue(eval)).thenReturn(2L);
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(0L);
        when(attemptRepository.findFirstByEvaluationAndStudentOrderByAttemptNumberDesc(eval, alumno))
                .thenReturn(Optional.empty());

        List<StudentEvaluationResponse> result = service.listAvailableEvaluationsForStudent("EST0001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Óxidos y nomenclatura");
        assertThat(result.get(0).assignmentId()).isEqualTo(40L);
    }

    // =========================================================================
    // 10. Estudiante de otra sección no ve la evaluación
    // =========================================================================

    @Test
    void estudianteDeOtraSeccionNoVeEvaluacion() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "B", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStudentEvaluationDetail("EST0002", 10L))
                .hasMessageContaining("no está asignada a tu sección");
    }

    // =========================================================================
    // 11. El estudiante no recibe el campo "correct" en las alternativas
    // =========================================================================

    @Test
    void estudianteNoRecibeCampoCorrect() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        EvaluationQuestion q = question(20L, eval, 1);
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(option(31L, q, false), option(32L, q, true)));

        StudentEvaluationDetailResponse response = service.getStudentEvaluationDetail("EST0001", 10L);

        assertThat(response.questions()).hasSize(1);
        // StudentOptionResponse no expone "correct": el tipo lo garantiza en compilación.
        StudentOptionResponse opt = response.questions().get(0).options().get(0);
        assertThat(opt.optionText()).isNotBlank();
    }

    // =========================================================================
    // 12. Estudiante inicia un intento
    // =========================================================================

    @Test
    void estudianteIniciaIntento() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(attemptRepository.findByEvaluationAndStudentAndStatus(eval, alumno, AttemptStatus.IN_PROGRESS))
                .thenReturn(Optional.empty());
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(0L);
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(any())).thenReturn(List.of());

        AttemptResponse response = service.startAttempt("EST0001", 10L, new StartEvaluationAttemptRequest(null));

        assertThat(response.status()).isEqualTo(AttemptStatus.IN_PROGRESS);
        assertThat(response.attemptNumber()).isEqualTo(1);
    }

    // =========================================================================
    // 13. El estudiante no inicia más de un intento en progreso
    // =========================================================================

    @Test
    void estudianteNoIniciaMasDeUnIntentoEnProgreso() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        EvaluationAttempt enCurso = EvaluationAttempt.builder()
                .id(99L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(attemptRepository.findByEvaluationAndStudentAndStatus(eval, alumno, AttemptStatus.IN_PROGRESS))
                .thenReturn(Optional.of(enCurso));

        assertThatThrownBy(() -> service.startAttempt("EST0001", 10L, new StartEvaluationAttemptRequest(null)))
                .hasMessageContaining("intento en progreso");
        verify(attemptRepository, never()).save(any(EvaluationAttempt.class));
    }

    // =========================================================================
    // 14. Estudiante guarda una respuesta válida
    // =========================================================================

    @Test
    void estudianteGuardaRespuestaValida() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion q = question(20L, eval, 1);
        EvaluationOption opt = option(32L, q, true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findById(20L)).thenReturn(Optional.of(q));
        when(optionRepository.findById(32L)).thenReturn(Optional.of(opt));
        when(answerRepository.findByAttemptAndQuestion(attempt, q)).thenReturn(Optional.empty());
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of());

        AttemptResponse response = service.saveAnswer(
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L));

        assertThat(response.id()).isEqualTo(50L);
        verify(answerRepository).save(any(EvaluationAnswer.class));
    }

    // =========================================================================
    // 15. Rechaza una opción que no pertenece a la pregunta
    // =========================================================================

    @Test
    void rechazaOpcionQueNoPerteneceALaPregunta() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion q = question(20L, eval, 1);
        EvaluationQuestion otra = question(21L, eval, 1);
        EvaluationOption optDeOtra = option(99L, otra, true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findById(20L)).thenReturn(Optional.of(q));
        when(optionRepository.findById(99L)).thenReturn(Optional.of(optDeOtra));

        assertThatThrownBy(() -> service.saveAnswer(
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 99L)))
                .hasMessageContaining("no pertenece a la pregunta");
    }

    // =========================================================================
    // 16. Estudiante envía el intento (se calcula el puntaje básico)
    // =========================================================================

    @Test
    void estudianteEnviaIntento() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion q = question(20L, eval, 2);
        EvaluationOption correcta = option(32L, q, true);
        EvaluationAnswer answer = EvaluationAnswer.builder()
                .id(60L).attempt(null).question(q).selectedOption(correcta).build();
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(answerRepository.findByAttemptAndQuestion(attempt, q)).thenReturn(Optional.of(answer));
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of(answer));

        AttemptResponse response = service.submitAttempt(
                "EST0001", 50L, new SubmitEvaluationAttemptRequest(null));

        assertThat(response.status()).isEqualTo(AttemptStatus.SUBMITTED);
        assertThat(response.score()).isEqualTo(2);
        assertThat(response.maxScore()).isEqualTo(2);
    }

    // =========================================================================
    // 17. No permite enviar un intento dos veces
    // =========================================================================

    @Test
    void noPermiteEnviarIntentoDosVeces() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.SUBMITTED).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.submitAttempt(
                "EST0001", 50L, new SubmitEvaluationAttemptRequest(null)))
                .hasMessageContaining("ya fue enviado");
    }

    // =========================================================================
    // 18. No permite superar el número máximo de intentos
    // =========================================================================

    @Test
    void noPermiteSuperarMaxAttempts() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(attemptRepository.findByEvaluationAndStudentAndStatus(eval, alumno, AttemptStatus.IN_PROGRESS))
                .thenReturn(Optional.empty());
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(1L);

        assertThatThrownBy(() -> service.startAttempt("EST0001", 10L, new StartEvaluationAttemptRequest(null)))
                .hasMessageContaining("máximo de intentos");
        verify(attemptRepository, never()).save(any(EvaluationAttempt.class));
    }

    // =========================================================================
    // 19. Un docente no puede editar la evaluación de otro docente
    // =========================================================================

    @Test
    void docenteNoEditaEvaluacionDeOtroDocente() {
        TeacherProfile docente = teacher(1L, "docente1");
        TeacherProfile otro = teacher(2L, "docente2");
        stubTeacher(docente);
        when(evaluationRepository.findById(10L))
                .thenReturn(Optional.of(evaluation(10L, otro, EvaluationStatus.DRAFT, 1)));

        var request = new UpdateEvaluationRequest("Nuevo título", null, null, null, 1, null);

        assertThatThrownBy(() -> service.updateEvaluation("docente1", 10L, request))
                .hasMessageContaining("No tienes permiso");
        verify(evaluationRepository, never()).save(any(Evaluation.class));
    }
}

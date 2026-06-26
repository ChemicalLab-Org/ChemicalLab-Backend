package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private EvaluationAttemptEventRepository attemptEventRepository;
    @Mock
    private EvaluationAnswerRepository answerRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TeacherProfileRepository teacherProfileRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;
    @Mock
    private AuditLogService auditLogService;

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

    private EvaluationAttempt gradedAttempt(Long id, Evaluation evaluation, StudentProfile student,
                                            int attemptNumber, Integer score, Integer maxScore) {
        return EvaluationAttempt.builder()
                .id(id).evaluation(evaluation).student(student).attemptNumber(attemptNumber)
                .status(AttemptStatus.GRADED).score(score).maxScore(maxScore)
                .startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now()).gradedAt(LocalDateTime.now())
                .active(true).build();
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

        var request = new CreateEvaluationRequest("  Óxidos y nomenclatura  ", "Desc", "Instrucciones", "Óxidos",
                2, 30, false, false, QuestionDisplayMode.ALL_AT_ONCE, false);
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
                .thenReturn(List.of())
                .thenReturn(List.of(option(31L, q, false), option(32L, q, true)));

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
    // 11b. El administrador supervisa el detalle sin recibir la alternativa correcta
    // =========================================================================

    @Test
    void administradorNoRecibeAlternativaCorrectaEnDetalle() {
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion q = question(20L, eval, 2);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(option(31L, q, false), option(32L, q, true)));
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(eval)).thenReturn(List.of());

        AdminEvaluationDetailResponse response = service.getAdminEvaluationDetail(10L);

        // Información general de supervisión disponible.
        assertThat(response.title()).isEqualTo("Óxidos y nomenclatura");
        assertThat(response.createdByTeacher()).isEqualTo("Ana Quispe");
        assertThat(response.questionCount()).isEqualTo(1);
        assertThat(response.questions()).hasSize(1);
        assertThat(response.questions().get(0).options()).hasSize(2);
        // AdminEvaluationOptionResponse no expone "correct": el tipo lo garantiza en compilación,
        // de modo que la clave de respuestas nunca viaja en la vista del administrador.
        AdminEvaluationOptionResponse opt = response.questions().get(0).options().get(0);
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
    // 16. Estudiante envía el intento (se califica y queda GRADED)
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

        assertThat(response.status()).isEqualTo(AttemptStatus.GRADED);
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

        var request = new UpdateEvaluationRequest("Nuevo título", null, null, null, 1, null,
                false, false, QuestionDisplayMode.ALL_AT_ONCE, false);

        assertThatThrownBy(() -> service.updateEvaluation("docente1", 10L, request))
                .hasMessageContaining("No tienes permiso");
        verify(evaluationRepository, never()).save(any(Evaluation.class));
    }

    // =========================================================================
    // 20. Una respuesta incorrecta otorga 0 puntos al enviar
    // =========================================================================

    @Test
    void respuestaIncorrectaOtorgaCero() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion q = question(20L, eval, 2);
        EvaluationOption incorrecta = option(31L, q, false);
        EvaluationAnswer answer = EvaluationAnswer.builder()
                .id(60L).attempt(null).question(q).selectedOption(incorrecta).build();
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

        assertThat(response.status()).isEqualTo(AttemptStatus.GRADED);
        assertThat(response.score()).isZero();
        assertThat(response.maxScore()).isEqualTo(2);
        assertThat(answer.getCorrect()).isFalse();
        assertThat(answer.getPointsAwarded()).isZero();
    }

    // =========================================================================
    // 21. Docente lista los resultados de su evaluación (agregados y porcentaje)
    // =========================================================================

    @Test
    void docenteListaResultadosDeSuEvaluacion() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationQuestion q = question(20L, eval, 2);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        EvaluationAttempt at = gradedAttempt(50L, eval, alumno, 1, 2, 2);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(attemptRepository.findByEvaluationAndStatusInOrderBySubmittedAtDesc(eq(eval), any()))
                .thenReturn(List.of(at));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));

        TeacherEvaluationResultsResponse res = service.getTeacherEvaluationResults("docente1", 10L);

        assertThat(res.totalAttempts()).isEqualTo(1);
        assertThat(res.maxScore()).isEqualTo(2);
        assertThat(res.results()).hasSize(1);
        assertThat(res.results().get(0).studentCode()).isEqualTo("EST0001");
        assertThat(res.results().get(0).percentage()).isEqualTo(100.0);
        assertThat(res.approvedCount()).isEqualTo(1);
        assertThat(res.failedCount()).isZero();
    }

    // =========================================================================
    // 22. Un docente no ve los resultados de la evaluación de otro docente
    // =========================================================================

    @Test
    void docenteNoVeResultadosDeOtroDocente() {
        TeacherProfile docente = teacher(1L, "docente1");
        TeacherProfile otro = teacher(2L, "docente2");
        stubTeacher(docente);
        when(evaluationRepository.findById(10L))
                .thenReturn(Optional.of(evaluation(10L, otro, EvaluationStatus.PUBLISHED, 1)));

        assertThatThrownBy(() -> service.getTeacherEvaluationResults("docente1", 10L))
                .hasMessageContaining("No tienes permiso");
    }

    // =========================================================================
    // 23. Estudiante ve la lista de sus resultados
    // =========================================================================

    @Test
    void estudianteVeSusResultados() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt at = gradedAttempt(50L, eval, alumno, 1, 2, 2);
        when(attemptRepository.findByStudentAndStatusInOrderBySubmittedAtDesc(eq(alumno), any()))
                .thenReturn(List.of(at));
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(1L);

        List<StudentResultSummaryResponse> res = service.listStudentResults("EST0001");

        assertThat(res).hasSize(1);
        assertThat(res.get(0).score()).isEqualTo(2);
        assertThat(res.get(0).percentage()).isEqualTo(100.0);
        // maxAttempts 1 y attemptsUsed 1 → ya no quedan intentos: puede ver el detalle.
        assertThat(res.get(0).canViewDetailedFeedback()).isTrue();
    }

    // =========================================================================
    // 24. Estudiante no ve el resultado de un intento de otro estudiante
    // =========================================================================

    @Test
    void estudianteNoVeResultadoDeOtroEstudiante() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        StudentProfile otro = student(5L, "EST0001", "3", "A");
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt at = gradedAttempt(50L, eval, otro, 1, 2, 2);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));

        assertThatThrownBy(() -> service.getStudentAttemptResult("EST0002", 50L))
                .hasMessageContaining("No tienes permiso");
    }

    // =========================================================================
    // 25. Estudiante con intentos restantes no recibe la alternativa correcta
    // =========================================================================

    @Test
    void estudianteConIntentosRestantesNoRecibeAlternativaCorrecta() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion q = question(20L, eval, 2);
        EvaluationOption incorrecta = option(31L, q, false);
        EvaluationOption correcta = option(32L, q, true);
        EvaluationAttempt at = gradedAttempt(50L, eval, alumno, 1, 2, 2);
        EvaluationAnswer ans = EvaluationAnswer.builder()
                .id(60L).attempt(at).question(q).selectedOption(correcta).correct(true).pointsAwarded(2).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(1L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(incorrecta, correcta));
        when(answerRepository.findByAttemptAndQuestion(at, q)).thenReturn(Optional.of(ans));

        StudentAttemptResultDetailResponse res = service.getStudentAttemptResult("EST0001", 50L);

        assertThat(res.canViewDetailedFeedback()).isFalse();
        assertThat(res.answers()).hasSize(1);
        assertThat(res.answers().get(0).correct()).isTrue();
        assertThat(res.answers().get(0).correctOptionText()).isNull();
    }

    // =========================================================================
    // 26. Estudiante sin intentos restantes sí recibe la alternativa correcta
    // =========================================================================

    @Test
    void estudianteSinIntentosRestantesRecibeAlternativaCorrecta() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationQuestion q = question(20L, eval, 2);
        EvaluationOption incorrecta = option(31L, q, false);
        EvaluationOption correcta = option(32L, q, true);
        EvaluationAttempt at = gradedAttempt(50L, eval, alumno, 1, 2, 2);
        EvaluationAnswer ans = EvaluationAnswer.builder()
                .id(60L).attempt(at).question(q).selectedOption(correcta).correct(true).pointsAwarded(2).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(1L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(incorrecta, correcta));
        when(answerRepository.findByAttemptAndQuestion(at, q)).thenReturn(Optional.of(ans));

        StudentAttemptResultDetailResponse res = service.getStudentAttemptResult("EST0001", 50L);

        assertThat(res.canViewDetailedFeedback()).isTrue();
        assertThat(res.answers().get(0).correctOptionText()).isEqualTo("CaO");
    }

    // =========================================================================
    // 27. El docente sí ve la alternativa correcta en el detalle del intento
    // =========================================================================

    @Test
    void docenteVeAlternativaCorrectaEnDetalle() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion q = question(20L, eval, 2);
        EvaluationOption incorrecta = option(31L, q, false);
        EvaluationOption correcta = option(32L, q, true);
        EvaluationAttempt at = gradedAttempt(50L, eval, alumno, 1, 2, 2);
        EvaluationAnswer ans = EvaluationAnswer.builder()
                .id(60L).attempt(at).question(q).selectedOption(correcta).correct(true).pointsAwarded(2).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(incorrecta, correcta));
        when(answerRepository.findByAttemptAndQuestion(at, q)).thenReturn(Optional.of(ans));

        TeacherAttemptResultDetailResponse res = service.getTeacherAttemptResult("docente1", 50L);

        assertThat(res.studentName()).isEqualTo("Luis Torres");
        assertThat(res.percentage()).isEqualTo(100.0);
        assertThat(res.answers().get(0).correctOptionId()).isEqualTo(32L);
        assertThat(res.answers().get(0).correctOptionText()).isEqualTo("CaO");
    }

    // =========================================================================
    // 28. No se consulta como resultado un intento en progreso
    // =========================================================================

    @Test
    void noSeConsultaResultadoDeIntentoEnProgreso() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt at = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));

        assertThatThrownBy(() -> service.getStudentAttemptResult("EST0001", 50L))
                .hasMessageContaining("en progreso");
    }

    // =========================================================================
    // 29. Docente crea evaluación con configuración avanzada
    // =========================================================================

    @Test
    void docenteCreaEvaluacionConConfiguracionAvanzada() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(evaluationRepository.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.countByEvaluationAndActiveTrue(any())).thenReturn(0L);
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(any())).thenReturn(List.of());

        var request = new CreateEvaluationRequest("Ácidos", "Desc", "Instrucciones", "Ácidos",
                3, 45, true, true, QuestionDisplayMode.ONE_BY_ONE, false);
        EvaluationResponse response = service.createEvaluation("docente1", request);

        assertThat(response.allowChemicalCalculator()).isTrue();
        assertThat(response.trackTabExit()).isTrue();
        assertThat(response.questionDisplayMode()).isEqualTo(QuestionDisplayMode.ONE_BY_ONE);
        assertThat(response.maxAttempts()).isEqualTo(3);
        assertThat(response.timeLimitMinutes()).isEqualTo(45);
    }

    // =========================================================================
    // 30. Crear sin configuración avanzada aplica los valores por defecto seguros
    // =========================================================================

    @Test
    void crearSinConfiguracionAplicaValoresPorDefecto() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        when(evaluationRepository.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.countByEvaluationAndActiveTrue(any())).thenReturn(0L);
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(any())).thenReturn(List.of());

        var request = new CreateEvaluationRequest("Sales", null, null, null, 1, null, null, null, null, null);
        EvaluationResponse response = service.createEvaluation("docente1", request);

        assertThat(response.allowChemicalCalculator()).isFalse();
        assertThat(response.trackTabExit()).isFalse();
        assertThat(response.questionDisplayMode()).isEqualTo(QuestionDisplayMode.ALL_AT_ONCE);
    }

    // =========================================================================
    // 31. Editar configuración registra el log de configuración actualizada
    // =========================================================================

    @Test
    void editarConfiguracionRegistraLog() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        eval.setTrackTabExit(false);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(evaluationRepository.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.countByEvaluationAndActiveTrue(any())).thenReturn(0L);
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(any())).thenReturn(List.of());

        var request = new UpdateEvaluationRequest("Óxidos y nomenclatura", null, null, null,
                2, 60, true, true, QuestionDisplayMode.ONE_BY_ONE, false);
        EvaluationResponse response = service.updateEvaluation("docente1", 10L, request);

        assertThat(response.trackTabExit()).isTrue();
        // Se registra al menos el log de configuración actualizada (y el de activación).
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_CONFIG_UPDATED), any(), any(),
                any(), eq("Actualizar configuración de evaluación"), any(), any());
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_CONFIG_UPDATED), any(), any(),
                any(), eq("Activar detección de salida de pestaña"), any(), any());
    }

    // =========================================================================
    // 32. Estudiante registra una salida de pestaña cuando la detección está activa
    // =========================================================================

    @Test
    void estudianteRegistraSalidaDePestania() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTrackTabExit(true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(attemptEventRepository.findFirstByAttemptOrderByOccurredAtDesc(attempt)).thenReturn(Optional.empty());
        when(attemptEventRepository.save(any(EvaluationAttemptEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptEventRepository.countByAttempt(attempt)).thenReturn(1L);
        when(attemptEventRepository.countByAttemptAndEventTypeIn(eq(attempt), any())).thenReturn(1L);

        AttemptEventSummaryResponse res = service.registerAttemptEvent(
                "EST0001", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null));

        assertThat(res.recorded()).isTrue();
        assertThat(res.tabExitCount()).isEqualTo(1L);
        verify(attemptEventRepository).save(any(EvaluationAttemptEvent.class));
    }

    // =========================================================================
    // 33. No se registra salida de pestaña si la detección está inactiva
    // =========================================================================

    @Test
    void noRegistraSalidaSiDeteccionInactiva() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTrackTabExit(false);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.registerAttemptEvent(
                "EST0001", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null)))
                .hasMessageContaining("no está activada");
        verify(attemptEventRepository, never()).save(any(EvaluationAttemptEvent.class));
    }

    // =========================================================================
    // 34. Un estudiante no registra eventos del intento de otro estudiante
    // =========================================================================

    @Test
    void estudianteNoRegistraEventoDeIntentoAjeno() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        StudentProfile otro = student(5L, "EST0001", "3", "A");
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTrackTabExit(true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(otro).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.registerAttemptEvent(
                "EST0002", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null)))
                .hasMessageContaining("No tienes permiso");
        verify(attemptEventRepository, never()).save(any(EvaluationAttemptEvent.class));
    }

    // =========================================================================
    // 35. Un evento idéntico reciente se descarta (control de duplicados)
    // =========================================================================

    @Test
    void descartaEventoDuplicadoReciente() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTrackTabExit(true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        EvaluationAttemptEvent ultimo = EvaluationAttemptEvent.builder()
                .id(70L).attempt(attempt).eventType(AttemptEventType.TAB_HIDDEN)
                .occurredAt(LocalDateTime.now()).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(attemptEventRepository.findFirstByAttemptOrderByOccurredAtDesc(attempt))
                .thenReturn(Optional.of(ultimo));
        when(attemptEventRepository.countByAttempt(attempt)).thenReturn(1L);
        when(attemptEventRepository.countByAttemptAndEventTypeIn(eq(attempt), any())).thenReturn(1L);

        AttemptEventSummaryResponse res = service.registerAttemptEvent(
                "EST0001", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null));

        assertThat(res.recorded()).isFalse();
        verify(attemptEventRepository, never()).save(any(EvaluationAttemptEvent.class));
    }

    // =========================================================================
    // 36. No se aceptan respuestas nuevas fuera de tiempo al guardar
    // =========================================================================

    @Test
    void noGuardaRespuestaFueraDeTiempo() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTimeLimitMinutes(30);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now().minusMinutes(90)).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.saveAnswer(
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L)))
                .hasMessageContaining("tiempo de la evaluación ya finalizó");
        verify(answerRepository, never()).save(any(EvaluationAnswer.class));
    }

    // =========================================================================
    // 37. El envío fuera de tiempo ignora las respuestas tardías y cierra el intento
    // =========================================================================

    @Test
    void envioFueraDeTiempoIgnoraRespuestasTardias() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTimeLimitMinutes(30);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now().minusMinutes(90)).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of());
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of());

        AttemptResponse response = service.submitAttempt(
                "EST0001", 50L, new SubmitEvaluationAttemptRequest(
                        List.of(new SubmitEvaluationAnswerRequest(20L, 32L))));

        // El intento se cierra (GRADED) pero la respuesta tardía no se persiste.
        assertThat(response.status()).isEqualTo(AttemptStatus.GRADED);
        verify(answerRepository, never()).save(any(EvaluationAnswer.class));
    }

    // =========================================================================
    // 38. Iniciar intento guarda un orden de preguntas (todos los IDs)
    // =========================================================================

    @Test
    void iniciarIntentoGuardaOrdenDePreguntas() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        eval.setRandomizeQuestions(true);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        EvaluationQuestion q1 = question(20L, eval, 1);
        EvaluationQuestion q2 = question(21L, eval, 1);
        EvaluationQuestion q3 = question(22L, eval, 1);
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(attemptRepository.findByEvaluationAndStudentAndStatus(eval, alumno, AttemptStatus.IN_PROGRESS))
                .thenReturn(Optional.empty());
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(0L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(q1, q2, q3));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(any())).thenReturn(List.of());

        AttemptResponse response = service.startAttempt("EST0001", 10L, new StartEvaluationAttemptRequest(null));

        // El orden guardado contiene exactamente los IDs de las preguntas activas.
        assertThat(response.questionOrder()).containsExactlyInAnyOrder(20L, 21L, 22L);
        assertThat(response.currentQuestionIndex()).isZero();
    }

    // =========================================================================
    // 39. Consultar el intento varias veces no cambia el orden guardado
    // =========================================================================

    @Test
    void consultarIntentoNoCambiaElOrden() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).questionOrder("22,20,21").currentQuestionIndex(0)
                .startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of());

        AttemptResponse first = service.getAttempt("EST0001", 50L);
        AttemptResponse second = service.getAttempt("EST0001", 50L);

        assertThat(first.questionOrder()).containsExactly(22L, 20L, 21L);
        assertThat(second.questionOrder()).containsExactly(22L, 20L, 21L);
        // No se regenera el orden: nunca se vuelve a guardar el intento por este motivo.
        verify(attemptRepository, never()).save(any(EvaluationAttempt.class));
    }

    // =========================================================================
    // 40. ONE_BY_ONE: guardar la pregunta actual avanza y la bloquea
    // =========================================================================

    @Test
    void unaPorUnaGuardaYAvanza() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setQuestionDisplayMode(QuestionDisplayMode.ONE_BY_ONE);
        EvaluationQuestion q = question(20L, eval, 1);
        EvaluationOption opt = option(32L, q, true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).questionOrder("20,21").currentQuestionIndex(0)
                .startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findById(20L)).thenReturn(Optional.of(q));
        when(optionRepository.findById(32L)).thenReturn(Optional.of(opt));
        when(answerRepository.findByAttemptAndQuestion(attempt, q)).thenReturn(Optional.empty());
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of());

        AttemptResponse response = service.saveAnswer(
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L));

        assertThat(response.currentQuestionIndex()).isEqualTo(1);
        verify(answerRepository).save(any(EvaluationAnswer.class));
    }

    // =========================================================================
    // 41. ONE_BY_ONE: no se puede volver a una pregunta anterior bloqueada
    // =========================================================================

    @Test
    void unaPorUnaNoPermiteRetroceder() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setQuestionDisplayMode(QuestionDisplayMode.ONE_BY_ONE);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).questionOrder("20,21").currentQuestionIndex(1)
                .startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.saveAnswer(
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L)))
                .hasMessageContaining("volver a una pregunta anterior");
        verify(answerRepository, never()).save(any(EvaluationAnswer.class));
    }

    // =========================================================================
    // 42. ONE_BY_ONE: no se puede saltar a una pregunta futura
    // =========================================================================

    @Test
    void unaPorUnaNoPermiteSaltarAdelante() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setQuestionDisplayMode(QuestionDisplayMode.ONE_BY_ONE);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).questionOrder("20,21,22").currentQuestionIndex(0)
                .startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.saveAnswer(
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(22L, 99L)))
                .hasMessageContaining("en orden");
        verify(answerRepository, never()).save(any(EvaluationAnswer.class));
    }

    // =========================================================================
    // 43. ONE_BY_ONE: al recargar, el intento continúa desde la pregunta pendiente
    // =========================================================================

    @Test
    void unaPorUnaContinuaDesdePreguntaPendiente() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setQuestionDisplayMode(QuestionDisplayMode.ONE_BY_ONE);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).questionOrder("20,21,22").currentQuestionIndex(2)
                .startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of());

        AttemptResponse response = service.getAttempt("EST0001", 50L);

        assertThat(response.currentQuestionIndex()).isEqualTo(2);
        assertThat(response.questionOrder()).containsExactly(20L, 21L, 22L);
    }

    // =========================================================================
    // 44. ALL_AT_ONCE: se puede modificar una respuesta antes de enviar
    // =========================================================================

    @Test
    void todasJuntasPermiteModificarRespuesta() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        // questionDisplayMode por defecto ALL_AT_ONCE.
        EvaluationQuestion q = question(20L, eval, 1);
        EvaluationOption nueva = option(31L, q, false);
        EvaluationAnswer previa = EvaluationAnswer.builder()
                .id(60L).attempt(null).question(q).selectedOption(option(32L, q, true)).build();
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).questionOrder("20").currentQuestionIndex(0)
                .startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findById(20L)).thenReturn(Optional.of(q));
        when(optionRepository.findById(31L)).thenReturn(Optional.of(nueva));
        when(answerRepository.findByAttemptAndQuestion(attempt, q)).thenReturn(Optional.of(previa));
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of(previa));

        service.saveAnswer("EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 31L));

        // La respuesta se actualiza a la nueva alternativa y no se avanza ningún índice.
        assertThat(previa.getSelectedOption().getId()).isEqualTo(31L);
        assertThat(attempt.getCurrentQuestionIndex()).isZero();
    }

    // =========================================================================
    // 45. Un estudiante no puede guardar respuestas en un intento ajeno
    // =========================================================================

    @Test
    void estudianteNoGuardaEnIntentoAjeno() {
        StudentProfile alumno = student(6L, "EST0002", "3", "B");
        stubStudent(alumno);
        StudentProfile otro = student(5L, "EST0001", "3", "A");
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(otro).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).questionOrder("20").currentQuestionIndex(0)
                .startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.saveAnswer(
                "EST0002", 50L, new SubmitEvaluationAnswerRequest(20L, 32L)))
                .hasMessageContaining("No tienes permiso");
        verify(answerRepository, never()).save(any(EvaluationAnswer.class));
    }
}

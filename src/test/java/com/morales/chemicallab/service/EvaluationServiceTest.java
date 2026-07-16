package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
    private EvaluationAttemptAdjustmentRepository adjustmentRepository;
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

    private EvaluationAssignment assignment(Long id, Evaluation evaluation, TeacherProfile teacher,
                                            String grade, String section, LocalDateTime dueAt) {
        return EvaluationAssignment.builder()
                .id(id).evaluation(evaluation).teacher(teacher).grade(grade).section(section)
                .dueAt(dueAt).active(true).build();
    }

    // Deja la evaluación con una asignación activa para (grade, section) y su lista de
    // estudiantes con acceso, de modo que computeReviewAvailability pueda resolver el grupo.
    private void stubGroup(Evaluation evaluation, EvaluationAssignment assignment,
                           List<StudentProfile> sectionStudents) {
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(evaluation))
                .thenReturn(List.of(assignment));
        when(studentProfileRepository.findByGradeAndSection(assignment.getGrade(), assignment.getSection()))
                .thenReturn(sectionStudents);
    }

    // Estado individual del intento de una estudiante para la regla de finalización grupal:
    // sin intento en progreso y con la cuenta de intentos usados indicada.
    private void stubAttemptsUsed(Evaluation evaluation, StudentProfile student, long used) {
        when(attemptRepository.findByEvaluationAndStudentAndStatus(
                evaluation, student, AttemptStatus.IN_PROGRESS)).thenReturn(Optional.empty());
        when(attemptRepository.countByEvaluationAndStudent(evaluation, student)).thenReturn(used);
    }

    private EvaluationAttempt gradedAttempt(Long id, Evaluation evaluation, StudentProfile student,
                                            int attemptNumber, Integer score, Integer maxScore) {
        // Resultado de alternativa única: se califica y se cierra automáticamente al enviarse,
        // por lo que la calificación queda cerrada (gradeClosed = true) y visible al estudiante.
        return EvaluationAttempt.builder()
                .id(id).evaluation(evaluation).student(student).attemptNumber(attemptNumber)
                .status(AttemptStatus.GRADED).score(score).maxScore(maxScore)
                .gradeClosed(true).gradeClosedAt(LocalDateTime.now())
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
                2, 30, false, false, QuestionDisplayMode.ALL_AT_ONCE, false, false);
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
                "¿Cuál es la fórmula del óxido de calcio?", QuestionType.MULTIPLE_CHOICE, 1, 0, null, null, true,
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
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L, null));

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
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 99L, null)))
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
                false, false, QuestionDisplayMode.ALL_AT_ONCE, false, false);

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
        // El grupo es la sola alumna asignada y ya usó su único intento: la revisión abre y
        // por tanto se expone su nota.
        stubGroup(eval, assignment(40L, eval, docente, "3", "A"), List.of(alumno));
        stubAttemptsUsed(eval, alumno, 1L);

        List<StudentResultSummaryResponse> res = service.listStudentResults("EST0001");

        assertThat(res).hasSize(1);
        assertThat(res.get(0).score()).isEqualTo(2);
        assertThat(res.get(0).percentage()).isEqualTo(100.0);
        // Todas las asignadas finalizaron: la revisión está disponible.
        assertThat(res.get(0).reviewAvailable()).isTrue();
        assertThat(res.get(0).canViewDetailedFeedback()).isTrue();
        assertThat(res.get(0).reviewUnlockReason())
                .isEqualTo(ReviewUnlockReason.ALL_ASSIGNED_STUDENTS_FINISHED);
        assertThat(res.get(0).assignedStudentsCount()).isEqualTo(1);
        assertThat(res.get(0).finishedStudentsCount()).isEqualTo(1);
        assertThat(res.get(0).pendingStudentsCount()).isZero();
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
    // 25. Con el grupo aún pendiente, la revisión se bloquea (Caso H: le quedan intentos)
    // =========================================================================

    @Test
    void estudianteConIntentosRestantesTieneRevisionBloqueada() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        // maxAttempts 2: con solo 1 intento usado, la alumna aún no finaliza (Caso H) y el
        // grupo sigue pendiente, por lo que la revisión no se habilita.
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt at = gradedAttempt(50L, eval, alumno, 1, 2, 2);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));
        stubGroup(eval, assignment(40L, eval, docente, "3", "A"), List.of(alumno));
        stubAttemptsUsed(eval, alumno, 1L);

        StudentAttemptResultDetailResponse res = service.getStudentAttemptResult("EST0001", 50L);

        // Revisión bloqueada: sin respuestas, sin nota y con el mensaje de revisión pendiente.
        assertThat(res.reviewAvailable()).isFalse();
        assertThat(res.reviewLocked()).isTrue();
        assertThat(res.canViewDetailedFeedback()).isFalse();
        assertThat(res.answers()).isEmpty();
        assertThat(res.score()).isNull();
        assertThat(res.finalScore()).isNull();
        assertThat(res.reviewUnlockReason()).isEqualTo(ReviewUnlockReason.NO_DEADLINE);
        assertThat(res.reviewMessage()).contains("La revisión estará disponible");
        assertThat(res.pendingStudentsCount()).isEqualTo(1);
    }

    // =========================================================================
    // 26. Cuando todas las asignadas finalizaron, la revisión se habilita (Caso B)
    // =========================================================================

    @Test
    void revisionDisponibleCuandoTodasFinalizaron() {
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
        // Grupo de una sola alumna que ya usó su único intento: todas finalizaron.
        stubGroup(eval, assignment(40L, eval, docente, "3", "A"), List.of(alumno));
        stubAttemptsUsed(eval, alumno, 1L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(incorrecta, correcta));
        when(answerRepository.findByAttemptAndQuestion(at, q)).thenReturn(Optional.of(ans));

        StudentAttemptResultDetailResponse res = service.getStudentAttemptResult("EST0001", 50L);

        assertThat(res.reviewAvailable()).isTrue();
        assertThat(res.canViewDetailedFeedback()).isTrue();
        assertThat(res.reviewUnlockReason()).isEqualTo(ReviewUnlockReason.ALL_ASSIGNED_STUDENTS_FINISHED);
        assertThat(res.answers().get(0).correctOptionText()).isEqualTo("CaO");
        assertThat(res.score()).isEqualTo(2);
    }

    // =========================================================================
    // 26b. Caso A/D: una alumna termina antes; con compañeras pendientes no ve la revisión
    //      aunque llame directamente al endpoint (el backend no envía respuestas correctas).
    // =========================================================================

    @Test
    void alumnaQueTerminaAntesNoVeRevisionSiFaltanCompaneras() {
        StudentProfile alumnaA = student(5L, "EST0001", "3", "A");
        StudentProfile alumnaB = student(6L, "EST0002", "3", "A");
        stubStudent(alumnaA);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt at = gradedAttempt(50L, eval, alumnaA, 1, 2, 2);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));
        // Grupo de dos alumnas: A ya usó su intento; B todavía no ha enviado (0 usados).
        stubGroup(eval, assignment(40L, eval, docente, "3", "A"), List.of(alumnaA, alumnaB));
        stubAttemptsUsed(eval, alumnaA, 1L);
        stubAttemptsUsed(eval, alumnaB, 0L);

        StudentAttemptResultDetailResponse res = service.getStudentAttemptResult("EST0001", 50L);

        // El backend es la fuente de verdad: sin revisión disponible no expone corrección.
        assertThat(res.reviewAvailable()).isFalse();
        assertThat(res.answers()).isEmpty();
        assertThat(res.score()).isNull();
        // Solo se informan conteos del grupo, nunca nombres de compañeras pendientes.
        assertThat(res.assignedStudentsCount()).isEqualTo(2);
        assertThat(res.finishedStudentsCount()).isEqualTo(1);
        assertThat(res.pendingStudentsCount()).isEqualTo(1);
    }

    // =========================================================================
    // 26c. Caso C: vencida la fecha límite, quienes enviaron ven la revisión aunque
    //      falten compañeras por finalizar.
    // =========================================================================

    @Test
    void fechaLimiteVencidaHabilitaRevisionAunConPendientes() {
        StudentProfile alumnaA = student(5L, "EST0001", "3", "A");
        StudentProfile alumnaB = student(6L, "EST0002", "3", "A");
        stubStudent(alumnaA);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationQuestion q = question(20L, eval, 2);
        EvaluationOption incorrecta = option(31L, q, false);
        EvaluationOption correcta = option(32L, q, true);
        EvaluationAttempt at = gradedAttempt(50L, eval, alumnaA, 1, 2, 2);
        EvaluationAnswer ans = EvaluationAnswer.builder()
                .id(60L).attempt(at).question(q).selectedOption(correcta).correct(true).pointsAwarded(2).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(at));
        LocalDateTime dueAt = LocalDateTime.now().minusHours(1);
        stubGroup(eval, assignment(40L, eval, docente, "3", "A", dueAt), List.of(alumnaA, alumnaB));
        stubAttemptsUsed(eval, alumnaA, 1L);
        stubAttemptsUsed(eval, alumnaB, 0L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(incorrecta, correcta));
        when(answerRepository.findByAttemptAndQuestion(at, q)).thenReturn(Optional.of(ans));

        StudentAttemptResultDetailResponse res = service.getStudentAttemptResult("EST0001", 50L);

        assertThat(res.reviewAvailable()).isTrue();
        assertThat(res.reviewUnlockReason()).isEqualTo(ReviewUnlockReason.DEADLINE_REACHED);
        assertThat(res.reviewAvailableAt()).isEqualTo(dueAt);
        assertThat(res.answers().get(0).correctOptionText()).isEqualTo("CaO");
        // Aunque la revisión esté abierta por plazo, el conteo refleja que B sigue pendiente.
        assertThat(res.pendingStudentsCount()).isEqualTo(1);
    }

    // =========================================================================
    // 26d. Caso H: una alumna con un intento en progreso no cuenta como finalizada,
    //      aunque haya alcanzado el máximo de intentos.
    // =========================================================================

    @Test
    void intentoEnProgresoNoCuentaComoFinalizada() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt terminado = gradedAttempt(50L, eval, alumno, 1, 2, 2);
        EvaluationAttempt enProgreso = EvaluationAttempt.builder()
                .id(51L).evaluation(eval).student(alumno).attemptNumber(2)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(terminado));
        when(assignmentRepository.findByEvaluationOrderByAssignedAtDesc(eval))
                .thenReturn(List.of(assignment(40L, eval, docente, "3", "A")));
        when(studentProfileRepository.findByGradeAndSection("3", "A")).thenReturn(List.of(alumno));
        // Tiene un intento en progreso: aún está rindiendo, no finaliza (no se consulta la cuenta).
        when(attemptRepository.findByEvaluationAndStudentAndStatus(eval, alumno, AttemptStatus.IN_PROGRESS))
                .thenReturn(Optional.of(enProgreso));

        StudentAttemptResultDetailResponse res = service.getStudentAttemptResult("EST0001", 50L);

        assertThat(res.reviewAvailable()).isFalse();
        assertThat(res.answers()).isEmpty();
        assertThat(res.finishedStudentsCount()).isZero();
        assertThat(res.pendingStudentsCount()).isEqualTo(1);
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
                3, 45, true, true, QuestionDisplayMode.ONE_BY_ONE, false, true);
        EvaluationResponse response = service.createEvaluation("docente1", request);

        assertThat(response.allowChemicalCalculator()).isTrue();
        assertThat(response.allowPeriodicTable()).isTrue();
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

        var request = new CreateEvaluationRequest("Sales", null, null, null, 1, null, null, null, null, null, null);
        EvaluationResponse response = service.createEvaluation("docente1", request);

        assertThat(response.allowChemicalCalculator()).isFalse();
        assertThat(response.allowPeriodicTable()).isFalse();
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
                2, 60, true, true, QuestionDisplayMode.ONE_BY_ONE, false, true);
        EvaluationResponse response = service.updateEvaluation("docente1", 10L, request);

        assertThat(response.allowPeriodicTable()).isTrue();

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
                "EST0001", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null, null, null));

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
                "EST0001", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null, null, null)))
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
                "EST0002", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null, null, null)))
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
                "EST0001", 50L, new RegisterAttemptEventRequest(AttemptEventType.TAB_HIDDEN, null, null, null));

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
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L, null)))
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
                        List.of(new SubmitEvaluationAnswerRequest(20L, 32L, null))));

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
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L, null));

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
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 32L, null)))
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
                "EST0001", 50L, new SubmitEvaluationAnswerRequest(22L, 99L, null)))
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

        service.saveAnswer("EST0001", 50L, new SubmitEvaluationAnswerRequest(20L, 31L, null));

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
                "EST0002", 50L, new SubmitEvaluationAnswerRequest(20L, 32L, null)))
                .hasMessageContaining("No tienes permiso");
        verify(answerRepository, never()).save(any(EvaluationAnswer.class));
    }

    // =========================================================================
    // 46. El detalle del estudiante expone los permisos de herramientas del intento
    // =========================================================================

    @Test
    void detalleEstudianteExponePermisosDeHerramientas() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setAllowChemicalCalculator(true);
        eval.setAllowPeriodicTable(true);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        EvaluationQuestion q = question(20L, eval, 1);
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(q))
                .thenReturn(List.of(option(31L, q, false), option(32L, q, true)));

        StudentEvaluationDetailResponse response = service.getStudentEvaluationDetail("EST0001", 10L);

        // El estudiante recibe los permisos para mostrar/ocultar herramientas en el intento.
        assertThat(response.allowChemicalCalculator()).isTrue();
        assertThat(response.allowPeriodicTable()).isTrue();
    }

    // =========================================================================
    // 47. El intento informa al estudiante qué herramientas tiene permitidas
    // =========================================================================

    @Test
    void intentoInformaHerramientasPermitidas() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        eval.setAllowChemicalCalculator(true);
        eval.setAllowPeriodicTable(false);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(attemptRepository.findByEvaluationAndStudentAndStatus(eval, alumno, AttemptStatus.IN_PROGRESS))
                .thenReturn(Optional.empty());
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(0L);
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(any())).thenReturn(List.of());

        AttemptResponse response = service.startAttempt("EST0001", 10L, new StartEvaluationAttemptRequest(null));

        // El DTO del intento traslada la configuración real de la evaluación.
        assertThat(response.allowChemicalCalculator()).isTrue();
        assertThat(response.allowPeriodicTable()).isFalse();
    }

    // =========================================================================
    // 48. El estudiante finaliza su intento al salir (se califica lo guardado)
    // =========================================================================

    @Test
    void estudianteFinalizaIntentoAlSalir() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        // Dos preguntas: una respondida correctamente y otra sin responder (queda en cero).
        EvaluationQuestion respondida = question(20L, eval, 2);
        EvaluationQuestion sinResponder = question(21L, eval, 3);
        EvaluationOption correcta = option(32L, respondida, true);
        EvaluationAnswer answer = EvaluationAnswer.builder()
                .id(60L).attempt(null).question(respondida).selectedOption(correcta).build();
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(respondida, sinResponder));
        when(answerRepository.findByAttemptAndQuestion(attempt, respondida)).thenReturn(Optional.of(answer));
        when(answerRepository.findByAttemptAndQuestion(attempt, sinResponder)).thenReturn(Optional.empty());
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of(answer));

        AttemptResponse response = service.exitAttempt("EST0001", 50L);

        // El intento queda finalizado (GRADED) y calificado con lo respondido: 2 de 5 puntos.
        assertThat(response.status()).isEqualTo(AttemptStatus.GRADED);
        assertThat(response.score()).isEqualTo(2);
        assertThat(response.maxScore()).isEqualTo(5);
        assertThat(attempt.getSubmittedAt()).isNotNull();
    }

    // =========================================================================
    // 49. Un intento finalizado por salida no puede retomarse ni reenviarse
    // =========================================================================

    @Test
    void intentoFinalizadoPorSalidaNoSeRetoma() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.GRADED).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        // Ya no está en progreso: ni salir de nuevo ni enviarlo vuelve a procesarlo.
        assertThatThrownBy(() -> service.exitAttempt("EST0001", 50L))
                .hasMessageContaining("ya fue enviado");
        verify(attemptRepository, never()).save(any(EvaluationAttempt.class));
    }

    // =========================================================================
    // 50. El estudiante no puede finalizar (salir de) el intento de otro
    // =========================================================================

    @Test
    void estudianteNoFinalizaIntentoAjeno() {
        StudentProfile dueno = student(5L, "EST0001", "3", "A");
        StudentProfile otro = student(6L, "EST0002", "3", "A");
        stubStudent(otro);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(dueno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.exitAttempt("EST0002", 50L))
                .hasMessageContaining("No tienes permiso");
        verify(attemptRepository, never()).save(any(EvaluationAttempt.class));
    }

    // =========================================================================
    // 51. Iniciar un intento registra el evento ATTEMPT_STARTED (trazabilidad)
    // =========================================================================

    @Test
    void iniciarIntentoRegistraInicio() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        // La detección de salida de pestaña está desactivada: el inicio igual debe registrarse.
        eval.setTrackTabExit(false);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        EvaluationQuestion q1 = question(20L, eval, 1);
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(attemptRepository.findByEvaluationAndStudentAndStatus(eval, alumno, AttemptStatus.IN_PROGRESS))
                .thenReturn(Optional.empty());
        when(attemptRepository.countByEvaluationAndStudent(eval, alumno)).thenReturn(0L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q1));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptEventRepository.save(any(EvaluationAttemptEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(any())).thenReturn(List.of());

        service.startAttempt("EST0001", 10L, new StartEvaluationAttemptRequest(null));

        ArgumentCaptor<EvaluationAttemptEvent> captor = ArgumentCaptor.forClass(EvaluationAttemptEvent.class);
        verify(attemptEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AttemptEventType.ATTEMPT_STARTED);
    }

    // =========================================================================
    // 52. Enviar un intento registra el evento ATTEMPT_SUBMITTED (trazabilidad)
    // =========================================================================

    @Test
    void enviarIntentoRegistraEnvio() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationQuestion q = question(20L, eval, 2);
        EvaluationOption correcta = option(32L, q, true);
        EvaluationAnswer answer = EvaluationAnswer.builder()
                .id(60L).attempt(null).question(q).selectedOption(correcta).build();
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval)).thenReturn(List.of(q));
        when(answerRepository.findByAttemptAndQuestion(attempt, q)).thenReturn(Optional.of(answer));
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptEventRepository.save(any(EvaluationAttemptEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)).thenReturn(List.of(answer));

        AttemptResponse response = service.submitAttempt("EST0001", 50L, null);

        assertThat(response.status()).isEqualTo(AttemptStatus.GRADED);
        ArgumentCaptor<EvaluationAttemptEvent> captor = ArgumentCaptor.forClass(EvaluationAttemptEvent.class);
        verify(attemptEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AttemptEventType.ATTEMPT_SUBMITTED);
    }

    // =========================================================================
    // 53. Se registra el uso de una herramienta aunque trackTabExit esté inactivo
    // =========================================================================

    @Test
    void registraUsoDeHerramientaSinDeteccionDePestania() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTrackTabExit(false);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(attemptEventRepository.findFirstByAttemptOrderByOccurredAtDesc(attempt)).thenReturn(Optional.empty());
        when(attemptEventRepository.save(any(EvaluationAttemptEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptEventRepository.countByAttempt(attempt)).thenReturn(1L);
        when(attemptEventRepository.countByAttemptAndEventTypeIn(eq(attempt), any())).thenReturn(0L);

        AttemptEventSummaryResponse res = service.registerAttemptEvent("EST0001", 50L,
                new RegisterAttemptEventRequest(AttemptEventType.TOOL_OPENED, "Abrió la tabla periódica.",
                        AttemptTool.PERIODIC_TABLE, null));

        assertThat(res.recorded()).isTrue();
        ArgumentCaptor<EvaluationAttemptEvent> captor = ArgumentCaptor.forClass(EvaluationAttemptEvent.class);
        verify(attemptEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AttemptEventType.TOOL_OPENED);
        assertThat(captor.getValue().getMetadata()).isEqualTo("tool=PERIODIC_TABLE");
    }

    // =========================================================================
    // 54. Se registra el intento de salida (EXIT_ATTEMPTED)
    // =========================================================================

    @Test
    void registraIntentoDeSalida() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(attemptEventRepository.findFirstByAttemptOrderByOccurredAtDesc(attempt)).thenReturn(Optional.empty());
        when(attemptEventRepository.save(any(EvaluationAttemptEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptEventRepository.countByAttempt(attempt)).thenReturn(1L);
        when(attemptEventRepository.countByAttemptAndEventTypeIn(eq(attempt), any())).thenReturn(0L);

        AttemptEventSummaryResponse res = service.registerAttemptEvent("EST0001", 50L,
                new RegisterAttemptEventRequest(AttemptEventType.EXIT_ATTEMPTED, null, null, "BUTTON_EXIT"));

        assertThat(res.recorded()).isTrue();
        ArgumentCaptor<EvaluationAttemptEvent> captor = ArgumentCaptor.forClass(EvaluationAttemptEvent.class);
        verify(attemptEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AttemptEventType.EXIT_ATTEMPTED);
        assertThat(captor.getValue().getMetadata()).isEqualTo("source=BUTTON_EXIT");
    }

    // =========================================================================
    // 55. El cliente no puede registrar un hito del ciclo de vida (lo hace el backend)
    // =========================================================================

    @Test
    void clienteNoRegistraHitoDeCicloDeVida() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTrackTabExit(true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.registerAttemptEvent("EST0001", 50L,
                new RegisterAttemptEventRequest(AttemptEventType.ATTEMPT_SUBMITTED, null, null, null)))
                .hasMessageContaining("registra el sistema");
        verify(attemptEventRepository, never()).save(any(EvaluationAttemptEvent.class));
    }

    // =========================================================================
    // 56. La metadata de origen enviada por el cliente se limpia (sin datos sensibles)
    // =========================================================================

    @Test
    void metadataDeOrigenSeLimpia() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(attemptEventRepository.findFirstByAttemptOrderByOccurredAtDesc(attempt)).thenReturn(Optional.empty());
        when(attemptEventRepository.save(any(EvaluationAttemptEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptEventRepository.countByAttempt(attempt)).thenReturn(1L);
        when(attemptEventRepository.countByAttemptAndEventTypeIn(eq(attempt), any())).thenReturn(0L);

        // El origen llega con espacios y caracteres especiales: debe quedar como token limpio.
        service.registerAttemptEvent("EST0001", 50L,
                new RegisterAttemptEventRequest(AttemptEventType.TOOL_RETURNED, null, null,
                        "visibility change; drop table"));

        ArgumentCaptor<EvaluationAttemptEvent> captor = ArgumentCaptor.forClass(EvaluationAttemptEvent.class);
        verify(attemptEventRepository).save(captor.capture());
        String metadata = captor.getValue().getMetadata();
        assertThat(metadata).isEqualTo("source=visibilitychangedroptable");
        assertThat(metadata).doesNotContain(" ").doesNotContain(";");
    }

    // =========================================================================
    // 57. El docente ve la trazabilidad de un intento de su evaluación
    // =========================================================================

    @Test
    void docenteVeTrazabilidadDelIntento() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        eval.setTrackTabExit(true);
        LocalDateTime inicio = LocalDateTime.now().minusMinutes(10);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.GRADED).startedAt(inicio).submittedAt(inicio.plusMinutes(8))
                .active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        List<EvaluationAttemptEvent> eventos = List.of(
                EvaluationAttemptEvent.builder().id(1L).attempt(attempt)
                        .eventType(AttemptEventType.ATTEMPT_STARTED).occurredAt(inicio).build(),
                EvaluationAttemptEvent.builder().id(2L).attempt(attempt)
                        .eventType(AttemptEventType.TAB_HIDDEN).occurredAt(inicio.plusMinutes(1)).build(),
                EvaluationAttemptEvent.builder().id(3L).attempt(attempt)
                        .eventType(AttemptEventType.TAB_VISIBLE).occurredAt(inicio.plusMinutes(2)).build(),
                EvaluationAttemptEvent.builder().id(4L).attempt(attempt)
                        .eventType(AttemptEventType.TOOL_OPENED).metadata("tool=PERIODIC_TABLE")
                        .occurredAt(inicio.plusMinutes(3)).build(),
                EvaluationAttemptEvent.builder().id(5L).attempt(attempt)
                        .eventType(AttemptEventType.EXIT_ATTEMPTED).occurredAt(inicio.plusMinutes(4)).build(),
                EvaluationAttemptEvent.builder().id(6L).attempt(attempt)
                        .eventType(AttemptEventType.ATTEMPT_SUBMITTED).occurredAt(inicio.plusMinutes(8)).build());
        when(attemptEventRepository.findByAttemptOrderByOccurredAtAsc(attempt)).thenReturn(eventos);

        AttemptTraceabilityResponse res = service.getAttemptTraceability("docente1", 50L);

        assertThat(res.totalEvents()).isEqualTo(6);
        assertThat(res.tabExitCount()).isEqualTo(1);
        assertThat(res.tabReturnCount()).isEqualTo(1);
        assertThat(res.exitAttemptCount()).isEqualTo(1);
        assertThat(res.toolsUsed()).containsExactly("PERIODIC_TABLE");
        assertThat(res.finalStatus()).isEqualTo(AttemptStatus.GRADED);
        // Tiempo usado calculado en el backend con timestamps del intento: 8 minutos = 480 s.
        assertThat(res.timeUsedSeconds()).isEqualTo(480L);
        assertThat(res.events()).hasSize(6);
    }

    // =========================================================================
    // 58. El docente no ve la trazabilidad de un intento de otra evaluación
    // =========================================================================

    @Test
    void docenteNoVeTrazabilidadDeIntentoAjeno() {
        TeacherProfile docenteDueno = teacher(1L, "docente1");
        TeacherProfile otroDocente = teacher(2L, "docente2");
        stubTeacher(otroDocente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docenteDueno, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.GRADED).startedAt(LocalDateTime.now()).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.getAttemptTraceability("docente2", 50L))
                .hasMessageContaining("No tienes permiso");
        verify(attemptEventRepository, never()).findByAttemptOrderByOccurredAtAsc(any());
    }

    // =========================================================================
    // PREGUNTAS ABIERTAS Y CALIFICACIÓN MANUAL
    // =========================================================================

    private EvaluationQuestion openQuestion(Long id, Evaluation evaluation, int points, boolean required) {
        return EvaluationQuestion.builder()
                .id(id).evaluation(evaluation).questionText("Explica la formación del óxido de calcio.")
                .questionType(QuestionType.OPEN_TEXT).points(points).orderIndex(1)
                .expectedAnswer("Debe mencionar la reacción del metal con oxígeno.")
                .required(required).active(true).build();
    }

    @Test
    void docenteCreaPreguntaAbiertaSinAlternativas() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));
        when(questionRepository.save(any(EvaluationQuestion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(any())).thenReturn(List.of());

        var request = new CreateQuestionRequest(
                "Explica la formación del óxido de calcio.", QuestionType.OPEN_TEXT, 5, 1,
                null, "Debe mencionar metal + oxígeno.", true, null);

        QuestionResponse response = service.addQuestion("docente1", 10L, request);

        assertThat(response.questionType()).isEqualTo(QuestionType.OPEN_TEXT);
        assertThat(response.expectedAnswer()).isEqualTo("Debe mencionar metal + oxígeno.");
        assertThat(response.options()).isEmpty();
        // No se crean alternativas para una pregunta abierta.
        verify(optionRepository, never()).save(any(EvaluationOption.class));
    }

    @Test
    void rechazaPreguntaAbiertaConAlternativas() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));

        var request = new CreateQuestionRequest(
                "Explica la formación del óxido de calcio.", QuestionType.OPEN_TEXT, 5, 1, null, null, true,
                List.of(new CreateOptionRequest("CaO", true, 0), new CreateOptionRequest("Ca2O", false, 1)));

        assertThatThrownBy(() -> service.addQuestion("docente1", 10L, request))
                .hasMessageContaining("no puede tener alternativas");
        verify(questionRepository, never()).save(any(EvaluationQuestion.class));
    }

    @Test
    void rechazaAlternativaUnicaSinSuficientesAlternativas() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.DRAFT, 1);
        when(evaluationRepository.findById(10L)).thenReturn(Optional.of(eval));

        var request = new CreateQuestionRequest(
                "¿Fórmula del óxido de calcio?", QuestionType.MULTIPLE_CHOICE, 1, 0, null, null, true,
                List.of(new CreateOptionRequest("CaO", true, 0)));

        assertThatThrownBy(() -> service.addQuestion("docente1", 10L, request))
                .hasMessageContaining("al menos dos alternativas");
        verify(questionRepository, never()).save(any(EvaluationQuestion.class));
    }

    @Test
    void intentoMixtoQuedaPendienteDeRevisionManual() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion mc = question(20L, eval, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        EvaluationOption correcta = option(32L, mc, true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        EvaluationAnswer mcAnswer = EvaluationAnswer.builder()
                .id(60L).attempt(attempt).question(mc).selectedOption(correcta).reviewed(true).build();
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("El calcio reacciona con oxígeno.")
                .reviewed(false).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(mc, open));
        when(answerRepository.findByAttemptAndQuestion(attempt, mc)).thenReturn(Optional.of(mcAnswer));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt))
                .thenReturn(List.of(mcAnswer, openAnswer));

        AttemptResponse response = service.submitAttempt(
                "EST0001", 50L, new SubmitEvaluationAttemptRequest(null));

        // La parte de alternativa única se califica (2 pts); la abierta queda pendiente.
        assertThat(response.status()).isEqualTo(AttemptStatus.PENDING_MANUAL_REVIEW);
        assertThat(response.score()).isEqualTo(2);
        assertThat(response.maxScore()).isEqualTo(5);
        assertThat(attempt.getGradedAt()).isNull();
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_ATTEMPT_PENDING_REVIEW),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void rechazaEnviarAbiertaObligatoriaEnBlanco() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.IN_PROGRESS).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitAttempt(
                "EST0001", 50L, new SubmitEvaluationAttemptRequest(null)))
                .hasMessageContaining("obligatorias");
        verify(attemptRepository, never()).save(any(EvaluationAttempt.class));
    }

    @Test
    void docenteCalificaRespuestaAbiertaYSeRecalculaNota() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion mc = question(20L, eval, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        EvaluationOption correcta = option(32L, mc, true);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.PENDING_MANUAL_REVIEW).score(2).maxScore(5)
                .startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now()).active(true).build();
        EvaluationAnswer mcAnswer = EvaluationAnswer.builder()
                .id(60L).attempt(attempt).question(mc).selectedOption(correcta).reviewed(true).build();
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("El calcio reacciona con oxígeno.")
                .reviewed(false).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(answerRepository.findById(61L)).thenReturn(Optional.of(openAnswer));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(mc, open));
        when(answerRepository.findByAttemptAndQuestion(attempt, mc)).thenReturn(Optional.of(mcAnswer));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));
        when(answerRepository.save(any(EvaluationAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

        TeacherAttemptReviewResponse response = service.manualGradeAnswer(
                "docente1", 50L, 61L, new ManualGradeRequest(3, "Respuesta correcta."));

        // Al revisar la única abierta, el intento pasa a GRADED con nota completa (2 + 3).
        assertThat(response.status()).isEqualTo(AttemptStatus.GRADED);
        assertThat(response.score()).isEqualTo(5);
        assertThat(response.maxScore()).isEqualTo(5);
        assertThat(response.pendingOpenCount()).isZero();
        assertThat(openAnswer.getReviewed()).isTrue();
        assertThat(openAnswer.getPointsAwarded()).isEqualTo(3);
        // Se registran logs seguros: revisión de la respuesta y cierre de la revisión.
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_ANSWER_REVIEWED),
                any(), any(), any(), any(), any(), metadata.capture());
        // El log no contiene el texto de la respuesta del estudiante, solo identificadores.
        assertThat(metadata.getValue()).contains("attemptId=50").contains("answerId=61");
        assertThat(metadata.getValue()).doesNotContain("calcio");
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_REVIEW_COMPLETED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void rechazaPuntajeManualMayorAlMaximo() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.PENDING_MANUAL_REVIEW).score(0).maxScore(3).active(true).build();
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(false).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(answerRepository.findById(61L)).thenReturn(Optional.of(openAnswer));

        assertThatThrownBy(() -> service.manualGradeAnswer(
                "docente1", 50L, 61L, new ManualGradeRequest(4, null)))
                .hasMessageContaining("no puede superar el máximo");
        verify(answerRepository, never()).save(any(EvaluationAnswer.class));
    }

    @Test
    void docenteNoRevisaIntentoDeEvaluacionAjena() {
        TeacherProfile docenteDueno = teacher(1L, "docente1");
        TeacherProfile otroDocente = teacher(2L, "docente2");
        stubTeacher(otroDocente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docenteDueno, EvaluationStatus.PUBLISHED, 1);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.PENDING_MANUAL_REVIEW).active(true).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.getAttemptReview("docente2", 50L))
                .hasMessageContaining("No tienes permiso");
    }

    @Test
    void estudianteNoRecibeCriterioDeCorreccionDePreguntaAbierta() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationAssignment asig = assignment(40L, eval, docente, "3", "A");
        EvaluationQuestion open = openQuestion(21L, eval, 3, true);
        when(assignmentRepository.findActiveForSectionByEvaluation(10L, "3", "A", EvaluationStatus.PUBLISHED))
                .thenReturn(Optional.of(asig));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(open)).thenReturn(List.of());

        StudentEvaluationDetailResponse response = service.getStudentEvaluationDetail("EST0001", 10L);

        StudentQuestionResponse q = response.questions().get(0);
        // El estudiante ve el tipo y la obligatoriedad, pero la pregunta abierta no trae
        // alternativas y el DTO no expone el criterio de corrección (expectedAnswer).
        assertThat(q.questionType()).isEqualTo(QuestionType.OPEN_TEXT);
        assertThat(q.required()).isTrue();
        assertThat(q.options()).isEmpty();
    }

    // =========================================================================
    // Ajustes manuales de puntaje, retroalimentación general y cierre
    // =========================================================================

    // Intento ya calificado (GRADED) y aún sin cerrar, con un puntaje en puntos dado, listo
    // para aplicarle ajustes manuales.
    private EvaluationAttempt openGradedAttempt(Long id, Evaluation eval, StudentProfile alumno,
                                                int score, int maxScore) {
        return EvaluationAttempt.builder()
                .id(id).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.GRADED).score(score).maxScore(maxScore).gradeClosed(false)
                .startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now())
                .gradedAt(LocalDateTime.now()).active(true).build();
    }

    private EvaluationAttemptAdjustment adjustment(Long id, EvaluationAttempt attempt,
                                                   TeacherProfile docente, String amount,
                                                   AdjustmentType type) {
        return EvaluationAttemptAdjustment.builder()
                .id(id).attempt(attempt).amount(new BigDecimal(amount)).type(type)
                .reason("Justificación").createdBy(docente).active(true)
                .createdAt(LocalDateTime.now()).build();
    }

    @Test
    void docenteAgregaAjustePositivoYRecalculaNotaFinal() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        // Nota base: 2/5*20 = 8.00.
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 2, 5);
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(true)
                .pointsAwarded(0).build();
        EvaluationAttemptAdjustment adj = adjustment(70L, attempt, docente, "1.00", AdjustmentType.BONUS);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(adjustmentRepository.save(any(EvaluationAttemptAdjustment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));
        // El ajuste recién creado queda activo y se refleja en el recálculo de la nota final.
        when(adjustmentRepository.findByAttemptAndActiveTrueOrderByCreatedAtAsc(attempt))
                .thenReturn(List.of(adj));

        TeacherAttemptReviewResponse response = service.addAdjustment(
                "docente1", 50L, new CreateAdjustmentRequest(new BigDecimal("1"), "Desarrollo claro"));

        assertThat(response.baseScore()).isEqualByComparingTo("8.00");
        assertThat(response.adjustmentsTotal()).isEqualByComparingTo("1.00");
        assertThat(response.finalScore()).isEqualByComparingTo("9.00");
        assertThat(response.adjustments()).hasSize(1);

        // El ajuste guardado conserva el motivo y deriva el tipo del signo del monto.
        ArgumentCaptor<EvaluationAttemptAdjustment> saved =
                ArgumentCaptor.forClass(EvaluationAttemptAdjustment.class);
        verify(adjustmentRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(AdjustmentType.BONUS);
        assertThat(saved.getValue().getReason()).isEqualTo("Desarrollo claro");
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("1.00");

        // Log seguro: registra el tipo y los identificadores, nunca el monto ni el motivo.
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_ADJUSTMENT_ADDED),
                any(), any(), any(), any(), any(), metadata.capture());
        assertThat(metadata.getValue()).contains("attemptId=50").contains("type=BONUS");
        assertThat(metadata.getValue()).doesNotContain("Desarrollo");
    }

    @Test
    void docenteAgregaAjusteNegativoYNotaNoBajaDeCero() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        // Nota base: 0/5*20 = 0.00; un ajuste de -3 no puede dejar la nota por debajo de 0.
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 0, 5);
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(true)
                .pointsAwarded(0).build();
        EvaluationAttemptAdjustment adj = adjustment(70L, attempt, docente, "-3.00", AdjustmentType.PENALTY);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(adjustmentRepository.save(any(EvaluationAttemptAdjustment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));
        when(adjustmentRepository.findByAttemptAndActiveTrueOrderByCreatedAtAsc(attempt))
                .thenReturn(List.of(adj));

        TeacherAttemptReviewResponse response = service.addAdjustment(
                "docente1", 50L, new CreateAdjustmentRequest(new BigDecimal("-3"), "Presentación incompleta"));

        assertThat(response.finalScore()).isEqualByComparingTo("0.00");
        ArgumentCaptor<EvaluationAttemptAdjustment> saved =
                ArgumentCaptor.forClass(EvaluationAttemptAdjustment.class);
        verify(adjustmentRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(AdjustmentType.PENALTY);
    }

    @Test
    void ajustePositivoNoSupera20() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 5, false);
        // Nota base máxima: 5/5*20 = 20.00; un bono de +5 no puede superar 20.
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 5, 5);
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(true)
                .pointsAwarded(5).build();
        EvaluationAttemptAdjustment adj = adjustment(70L, attempt, docente, "5.00", AdjustmentType.BONUS);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(adjustmentRepository.save(any(EvaluationAttemptAdjustment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));
        when(adjustmentRepository.findByAttemptAndActiveTrueOrderByCreatedAtAsc(attempt))
                .thenReturn(List.of(adj));

        TeacherAttemptReviewResponse response = service.addAdjustment(
                "docente1", 50L, new CreateAdjustmentRequest(new BigDecimal("5"), "Explicación adicional"));

        assertThat(response.finalScore()).isEqualByComparingTo("20.00");
    }

    @Test
    void rechazaAjusteSinMotivo() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 2, 5);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.addAdjustment(
                "docente1", 50L, new CreateAdjustmentRequest(new BigDecimal("1"), "   ")))
                .hasMessageContaining("motivo");
        verify(adjustmentRepository, never()).save(any(EvaluationAttemptAdjustment.class));
    }

    @Test
    void rechazaAjusteConMontoCero() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 2, 5);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.addAdjustment(
                "docente1", 50L, new CreateAdjustmentRequest(new BigDecimal("0"), "Sin efecto")))
                .hasMessageContaining("cero");
        verify(adjustmentRepository, never()).save(any(EvaluationAttemptAdjustment.class));
    }

    @Test
    void impideAjustarIntentoConCalificacionCerrada() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 2, 5);
        attempt.setGradeClosed(true);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.addAdjustment(
                "docente1", 50L, new CreateAdjustmentRequest(new BigDecimal("1"), "Tarde")))
                .hasMessageContaining("cerrada");
        verify(adjustmentRepository, never()).save(any(EvaluationAttemptAdjustment.class));
    }

    @Test
    void docenteNoAjustaIntentoDeOtroDocente() {
        TeacherProfile dueno = teacher(1L, "docente1");
        TeacherProfile otro = teacher(2L, "docente2");
        stubTeacher(otro);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, dueno, EvaluationStatus.PUBLISHED, 2);
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 2, 5);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> service.addAdjustment(
                "docente2", 50L, new CreateAdjustmentRequest(new BigDecimal("1"), "Ajeno")))
                .hasMessageContaining("No tienes permiso");
        verify(adjustmentRepository, never()).save(any(EvaluationAttemptAdjustment.class));
    }

    @Test
    void docenteAnulaAjusteYRecalculaNota() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 2, 5);
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(true)
                .pointsAwarded(0).build();
        EvaluationAttemptAdjustment adj = adjustment(70L, attempt, docente, "1.00", AdjustmentType.BONUS);
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(adjustmentRepository.findById(70L)).thenReturn(Optional.of(adj));
        when(adjustmentRepository.save(any(EvaluationAttemptAdjustment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));
        // Tras anularlo no quedan ajustes activos: la nota vuelve a la base (8.00).
        when(adjustmentRepository.findByAttemptAndActiveTrueOrderByCreatedAtAsc(attempt))
                .thenReturn(List.of());

        TeacherAttemptReviewResponse response = service.deleteAdjustment("docente1", 50L, 70L);

        assertThat(adj.getActive()).isFalse();
        assertThat(response.finalScore()).isEqualByComparingTo("8.00");
        assertThat(response.adjustments()).isEmpty();
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_ADJUSTMENT_REMOVED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void docenteAgregaRetroalimentacionGeneral() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 2, 5);
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(true)
                .pointsAwarded(0).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));

        TeacherAttemptReviewResponse response = service.updateOverallFeedback(
                "docente1", 50L, new UpdateAttemptFeedbackRequest("Buen trabajo, justifica mejor."));

        assertThat(attempt.getOverallFeedback()).isEqualTo("Buen trabajo, justifica mejor.");
        assertThat(response.overallFeedback()).isEqualTo("Buen trabajo, justifica mejor.");
        // Log seguro: no incluye el texto de la retroalimentación, solo el identificador.
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_FEEDBACK_UPDATED),
                any(), any(), any(), any(), any(), metadata.capture());
        assertThat(metadata.getValue()).contains("attemptId=50");
        assertThat(metadata.getValue()).doesNotContain("Buen trabajo");
    }

    @Test
    void docenteCierraCalificacionConAbiertasRevisadas() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 4, false);
        EvaluationAttempt attempt = openGradedAttempt(50L, eval, alumno, 0, 4);
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(true)
                .pointsAwarded(4).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(attemptRepository.save(any(EvaluationAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));

        TeacherAttemptReviewResponse response = service.closeGrade("docente1", 50L);

        // La única abierta está revisada (4/4): se cierra y la nota final es 20.00.
        assertThat(attempt.getGradeClosed()).isTrue();
        assertThat(attempt.getGradeClosedAt()).isNotNull();
        assertThat(attempt.getGradeClosedBy()).isEqualTo(docente);
        assertThat(response.gradeClosed()).isTrue();
        assertThat(response.finalScore()).isEqualByComparingTo("20.00");
        verify(auditLogService).recordInfo(eq(LogEventType.EVALUATION_GRADE_CLOSED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void impideCerrarCalificacionSiFaltanAbiertasPorRevisar() {
        TeacherProfile docente = teacher(1L, "docente1");
        stubTeacher(docente);
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 2);
        EvaluationQuestion open = openQuestion(21L, eval, 4, false);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.PENDING_MANUAL_REVIEW).score(0).maxScore(4).gradeClosed(false)
                .active(true).build();
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Texto").reviewed(false).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));

        assertThatThrownBy(() -> service.closeGrade("docente1", 50L))
                .hasMessageContaining("abiertas por revisar");
        verify(attemptRepository, never()).save(any(EvaluationAttempt.class));
    }

    @Test
    void estudianteNoVeNotaFinalAntesDelCierre() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        // El grupo ya finalizó (revisión disponible), pero la calificación del intento sigue
        // pendiente de revisión manual: la nota aún no se muestra.
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationQuestion open = openQuestion(21L, eval, 3, false);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.PENDING_MANUAL_REVIEW).score(2).maxScore(5)
                .finalScore(new BigDecimal("8.00")).gradeClosed(false)
                .overallFeedback("Comentario interno").submittedAt(LocalDateTime.now()).active(true).build();
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Mi respuesta").reviewed(false).build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        stubGroup(eval, assignment(40L, eval, docente, "3", "A"), List.of(alumno));
        stubAttemptsUsed(eval, alumno, 1L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));

        StudentAttemptResultDetailResponse response = service.getStudentAttemptResult("EST0001", 50L);

        // Revisión disponible para el grupo, pero calificación sin cerrar: ve su propia
        // respuesta y ninguna nota ni retro.
        assertThat(response.reviewAvailable()).isTrue();
        assertThat(response.gradeClosed()).isFalse();
        assertThat(response.finalScore()).isNull();
        assertThat(response.score()).isNull();
        assertThat(response.overallFeedback()).isNull();
        assertThat(response.answers().get(0).answerText()).isEqualTo("Mi respuesta");
        assertThat(response.answers().get(0).pointsAwarded()).isNull();
    }

    @Test
    void estudianteVeNotaFinalYRetroTrasCierre() {
        StudentProfile alumno = student(5L, "EST0001", "3", "A");
        stubStudent(alumno);
        TeacherProfile docente = teacher(1L, "docente1");
        Evaluation eval = evaluation(10L, docente, EvaluationStatus.PUBLISHED, 1);
        EvaluationQuestion open = openQuestion(21L, eval, 5, false);
        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .id(50L).evaluation(eval).student(alumno).attemptNumber(1)
                .status(AttemptStatus.GRADED).score(4).maxScore(5)
                .finalScore(new BigDecimal("16.00")).gradeClosed(true).gradeClosedAt(LocalDateTime.now())
                .overallFeedback("Buen trabajo.").submittedAt(LocalDateTime.now()).gradedAt(LocalDateTime.now())
                .active(true).build();
        EvaluationAnswer openAnswer = EvaluationAnswer.builder()
                .id(61L).attempt(attempt).question(open).answerText("Mi respuesta").reviewed(true)
                .pointsAwarded(4).teacherFeedback("Bien explicado.").build();
        when(attemptRepository.findById(50L)).thenReturn(Optional.of(attempt));
        // El grupo finalizó y la calificación está cerrada: se revela todo.
        stubGroup(eval, assignment(40L, eval, docente, "3", "A"), List.of(alumno));
        stubAttemptsUsed(eval, alumno, 1L);
        when(questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(eval))
                .thenReturn(List.of(open));
        when(answerRepository.findByAttemptAndQuestion(attempt, open)).thenReturn(Optional.of(openAnswer));

        StudentAttemptResultDetailResponse response = service.getStudentAttemptResult("EST0001", 50L);

        // Tras el cierre y con la revisión disponible: nota final, retroalimentación general
        // y comentario por respuesta.
        assertThat(response.reviewAvailable()).isTrue();
        assertThat(response.gradeClosed()).isTrue();
        assertThat(response.finalScore()).isEqualByComparingTo("16.00");
        assertThat(response.overallFeedback()).isEqualTo("Buen trabajo.");
        assertThat(response.answers().get(0).pointsAwarded()).isEqualTo(4);
        assertThat(response.answers().get(0).teacherFeedback()).isEqualTo("Bien explicado.");
    }
}

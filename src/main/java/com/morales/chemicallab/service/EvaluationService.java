package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Lógica de negocio del módulo de evaluaciones.
 *
 * <p>El docente crea evaluaciones, les agrega preguntas con alternativas, las publica
 * y las asigna a grados/secciones. El estudiante consulta solo las evaluaciones
 * publicadas asignadas a su grado/sección, inicia un intento, guarda respuestas y lo
 * envía. El administrador puede listar todo para supervisión.</p>
 *
 * <p>El docente y el estudiante se resuelven a partir del usuario autenticado (no de
 * identificadores enviados por el frontend), de modo que nadie pueda operar sobre
 * evaluaciones o intentos ajenos.</p>
 *
 * <p><b>Calificación y resultados:</b> al enviar un intento se ejecuta la
 * calificación automática de alternativa única y el intento queda en estado GRADED
 * con su {@code score}, {@code maxScore} y {@code gradedAt}. El docente consulta los
 * resultados de sus evaluaciones y el detalle de cada intento; el estudiante consulta
 * sus propias calificaciones. La retroalimentación detallada (alternativa correcta)
 * se le muestra al estudiante solo cuando ya no le quedan intentos disponibles o la
 * evaluación está archivada, para no facilitar la trampa.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EvaluationService {

    private final EvaluationRepository evaluationRepository;
    private final EvaluationQuestionRepository questionRepository;
    private final EvaluationOptionRepository optionRepository;
    private final EvaluationAssignmentRepository assignmentRepository;
    private final EvaluationAttemptRepository attemptRepository;
    private final EvaluationAttemptEventRepository attemptEventRepository;
    private final EvaluationAnswerRepository answerRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final AuditLogService auditLogService;

    private static final String TARGET_EVALUATION = "Evaluation";

    // Estados de un intento que ya representa un resultado consultable.
    private static final Set<AttemptStatus> RESULT_STATUSES =
            EnumSet.of(AttemptStatus.SUBMITTED, AttemptStatus.GRADED);

    // Umbral de aprobación (en %) usado solo para los contadores aprobados/desaprobados.
    private static final double APPROVAL_PERCENTAGE = 60.0;

    // Margen de gracia (segundos) sobre el límite de tiempo, para tolerar latencia de red
    // y desfase de reloj en el envío automático del frontend. Pasado este margen, el
    // backend deja de aceptar respuestas nuevas: el tiempo deja de protegerse solo en el
    // frontend.
    private static final long SUBMIT_GRACE_SECONDS = 60;

    // Ventana mínima (segundos) entre incidencias de foco idénticas. Evita registrar
    // ráfagas de eventos duplicados (control simple de throttling).
    private static final long EVENT_THROTTLE_SECONDS = 2;

    // Tipos de incidencia que se consideran una "salida" de la evaluación.
    private static final Set<AttemptEventType> EXIT_EVENT_TYPES =
            EnumSet.of(AttemptEventType.TAB_HIDDEN, AttemptEventType.WINDOW_BLUR);

    // =========================================================================
    // DOCENTE — evaluaciones
    // =========================================================================

    public EvaluationResponse createEvaluation(String username, CreateEvaluationRequest request) {
        TeacherProfile teacher = requireTeacher(username);

        Evaluation evaluation = Evaluation.builder()
                .title(request.title().trim())
                .description(trimOrNull(request.description()))
                .instructions(trimOrNull(request.instructions()))
                .topic(trimOrNull(request.topic()))
                .maxAttempts(request.maxAttempts())
                .timeLimitMinutes(request.timeLimitMinutes())
                .allowChemicalCalculator(Boolean.TRUE.equals(request.allowChemicalCalculator()))
                .trackTabExit(Boolean.TRUE.equals(request.trackTabExit()))
                .questionDisplayMode(request.questionDisplayMode() == null
                        ? QuestionDisplayMode.ALL_AT_ONCE : request.questionDisplayMode())
                .createdByTeacher(teacher)
                .status(EvaluationStatus.DRAFT)
                .active(true)
                .build();

        evaluationRepository.save(evaluation);

        auditLogService.recordInfo(LogEventType.EVALUATION_CREATED, TARGET_EVALUATION, evaluation.getId(),
                evaluation.getTitle(), "Crear evaluación",
                "Se creó la evaluación «" + evaluation.getTitle() + "».", null);

        return toEvaluationResponse(evaluation);
    }

    @Transactional(readOnly = true)
    public List<EvaluationResponse> listTeacherEvaluations(String username) {
        TeacherProfile teacher = requireTeacher(username);
        return evaluationRepository.findByCreatedByTeacherOrderByCreatedAtDesc(teacher)
                .stream()
                .map(this::toEvaluationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EvaluationDetailResponse getTeacherEvaluationDetail(String username, Long evaluationId) {
        TeacherProfile teacher = requireTeacher(username);
        return toEvaluationDetailResponse(requireOwnedEvaluation(evaluationId, teacher));
    }

    public EvaluationResponse updateEvaluation(String username, Long evaluationId, UpdateEvaluationRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        // Configuración avanzada previa, para detectar si cambió y registrar el log.
        boolean trackTabExitBefore = Boolean.TRUE.equals(evaluation.getTrackTabExit());

        evaluation.setTitle(request.title().trim());
        evaluation.setDescription(trimOrNull(request.description()));
        evaluation.setInstructions(trimOrNull(request.instructions()));
        evaluation.setTopic(trimOrNull(request.topic()));
        evaluation.setMaxAttempts(request.maxAttempts());
        evaluation.setTimeLimitMinutes(request.timeLimitMinutes());
        evaluation.setAllowChemicalCalculator(Boolean.TRUE.equals(request.allowChemicalCalculator()));
        evaluation.setTrackTabExit(Boolean.TRUE.equals(request.trackTabExit()));
        evaluation.setQuestionDisplayMode(request.questionDisplayMode() == null
                ? QuestionDisplayMode.ALL_AT_ONCE : request.questionDisplayMode());

        evaluationRepository.save(evaluation);

        // Trazabilidad de la edición de configuración avanzada. Descripción segura: solo
        // nombra la evaluación, nunca preguntas, claves ni respuestas.
        auditLogService.recordInfo(LogEventType.EVALUATION_CONFIG_UPDATED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Actualizar configuración de evaluación",
                "Se actualizó la configuración de la evaluación «" + evaluation.getTitle() + "».", null);

        // Cuando se activa por primera vez la detección de salida de pestaña, se deja
        // constancia explícita (sigue siendo una descripción segura y agregada).
        if (!trackTabExitBefore && Boolean.TRUE.equals(evaluation.getTrackTabExit())) {
            auditLogService.recordInfo(LogEventType.EVALUATION_CONFIG_UPDATED, TARGET_EVALUATION,
                    evaluation.getId(), evaluation.getTitle(), "Activar detección de salida de pestaña",
                    "Se habilitó la detección de salida de pestaña en una evaluación.", null);
        }

        return toEvaluationResponse(evaluation);
    }

    public EvaluationDetailResponse publishEvaluation(String username, Long evaluationId) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        if (evaluation.getStatus() == EvaluationStatus.ARCHIVED) {
            throw new IllegalArgumentException("No se puede publicar una evaluación archivada.");
        }

        List<EvaluationQuestion> questions =
                questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(evaluation);

        if (questions.isEmpty()) {
            throw new IllegalArgumentException("No se puede publicar una evaluación sin preguntas.");
        }

        // Cada pregunta debe tener alternativas y exactamente una correcta.
        for (EvaluationQuestion question : questions) {
            List<EvaluationOption> options =
                    optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("Cada pregunta debe tener alternativas para poder publicar.");
            }
            long correctCount = options.stream().filter(EvaluationOption::getCorrect).count();
            if (correctCount != 1) {
                throw new IllegalArgumentException("Cada pregunta debe tener exactamente una alternativa correcta.");
            }
        }

        evaluation.setStatus(EvaluationStatus.PUBLISHED);
        evaluationRepository.save(evaluation);

        auditLogService.recordInfo(LogEventType.EVALUATION_PUBLISHED, TARGET_EVALUATION, evaluation.getId(),
                evaluation.getTitle(), "Publicar evaluación",
                "Se publicó la evaluación «" + evaluation.getTitle() + "».", null);

        return toEvaluationDetailResponse(evaluation);
    }

    public EvaluationResponse archiveEvaluation(String username, Long evaluationId) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        evaluation.setStatus(EvaluationStatus.ARCHIVED);
        evaluationRepository.save(evaluation);
        return toEvaluationResponse(evaluation);
    }

    // =========================================================================
    // DOCENTE — preguntas y alternativas
    // =========================================================================

    public QuestionResponse addQuestion(String username, Long evaluationId, CreateQuestionRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        EvaluationQuestion question = EvaluationQuestion.builder()
                .evaluation(evaluation)
                .questionText(request.questionText().trim())
                .questionType(request.questionType() == null ? QuestionType.MULTIPLE_CHOICE : request.questionType())
                .points(request.points())
                .orderIndex(request.orderIndex() == null ? 0 : request.orderIndex())
                .explanation(trimOrNull(request.explanation()))
                .active(true)
                .build();
        questionRepository.save(question);

        replaceOptions(question, request.options());
        return toQuestionResponse(question);
    }

    public QuestionResponse updateQuestion(String username, Long evaluationId, Long questionId,
                                           UpdateQuestionRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);
        EvaluationQuestion question = requireQuestionOfEvaluation(questionId, evaluation);

        question.setQuestionText(request.questionText().trim());
        question.setQuestionType(request.questionType() == null ? QuestionType.MULTIPLE_CHOICE : request.questionType());
        question.setPoints(request.points());
        question.setOrderIndex(request.orderIndex() == null ? 0 : request.orderIndex());
        question.setExplanation(trimOrNull(request.explanation()));
        questionRepository.save(question);

        replaceOptions(question, request.options());
        return toQuestionResponse(question);
    }

    public QuestionResponse deactivateQuestion(String username, Long evaluationId, Long questionId) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);
        EvaluationQuestion question = requireQuestionOfEvaluation(questionId, evaluation);

        question.setActive(false);
        questionRepository.save(question);
        return toQuestionResponse(question);
    }

    // =========================================================================
    // DOCENTE — asignaciones
    // =========================================================================

    public EvaluationAssignmentResponse assignEvaluationToSection(String username, Long evaluationId,
                                                                  AssignEvaluationRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        if (evaluation.getStatus() == EvaluationStatus.ARCHIVED) {
            throw new IllegalArgumentException("No se puede asignar una evaluación archivada.");
        }

        String grade = request.grade().trim();
        String section = request.section().trim();

        if (assignmentRepository.existsByEvaluationAndGradeAndSectionAndActiveTrue(evaluation, grade, section)) {
            throw new IllegalArgumentException("Ya existe una asignación activa para esta sección.");
        }

        EvaluationAssignment assignment = EvaluationAssignment.builder()
                .evaluation(evaluation)
                .teacher(teacher)
                .grade(grade)
                .section(section)
                .startAt(request.startAt())
                .dueAt(request.dueAt())
                .active(true)
                .build();

        assignmentRepository.save(assignment);

        auditLogService.recordInfo(LogEventType.EVALUATION_ASSIGNED, TARGET_EVALUATION, evaluation.getId(),
                evaluation.getTitle(), "Asignar evaluación",
                "Se asignó la evaluación «" + evaluation.getTitle() + "» a " + grade + "° " + section + ".",
                "grade=" + grade + ";section=" + section);

        return toAssignmentResponse(assignment);
    }

    public EvaluationAssignmentResponse deactivateAssignment(String username, Long evaluationId, Long assignmentId) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        EvaluationAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new EntityNotFoundException("La asignación no existe."));

        if (!assignment.getEvaluation().getId().equals(evaluation.getId())) {
            throw new IllegalArgumentException("La asignación no pertenece a esta evaluación.");
        }

        assignment.setActive(false);
        assignmentRepository.save(assignment);
        return toAssignmentResponse(assignment);
    }

    // =========================================================================
    // ESTUDIANTE
    // =========================================================================

    @Transactional(readOnly = true)
    public List<StudentEvaluationResponse> listAvailableEvaluationsForStudent(String username) {
        StudentProfile student = requireStudent(username);
        return assignmentRepository
                .findActiveForSection(student.getGrade(), student.getSection(), EvaluationStatus.PUBLISHED)
                .stream()
                .map(assignment -> toStudentEvaluationResponse(assignment, student))
                .toList();
    }

    @Transactional(readOnly = true)
    public StudentEvaluationDetailResponse getStudentEvaluationDetail(String username, Long evaluationId) {
        StudentProfile student = requireStudent(username);
        EvaluationAssignment assignment = requireActiveAssignmentForStudent(evaluationId, student);
        return toStudentEvaluationDetailResponse(assignment.getEvaluation(), assignment.getId());
    }

    public AttemptResponse startAttempt(String username, Long evaluationId, StartEvaluationAttemptRequest request) {
        StudentProfile student = requireStudent(username);
        EvaluationAssignment assignment = requireActiveAssignmentForStudent(evaluationId, student);
        Evaluation evaluation = assignment.getEvaluation();

        if (assignment.getDueAt() != null && LocalDateTime.now().isAfter(assignment.getDueAt())) {
            throw new IllegalArgumentException("El plazo de la evaluación ya venció.");
        }

        attemptRepository.findByEvaluationAndStudentAndStatus(evaluation, student, AttemptStatus.IN_PROGRESS)
                .ifPresent(a -> { throw new IllegalArgumentException("Ya existe un intento en progreso."); });

        long used = attemptRepository.countByEvaluationAndStudent(evaluation, student);
        if (used >= evaluation.getMaxAttempts()) {
            throw new IllegalArgumentException("Ya superaste el número máximo de intentos.");
        }

        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .evaluation(evaluation)
                .assignment(assignment)
                .student(student)
                .attemptNumber((int) used + 1)
                .status(AttemptStatus.IN_PROGRESS)
                .active(true)
                .build();
        attemptRepository.save(attempt);

        return toAttemptResponse(attempt);
    }

    @Transactional(readOnly = true)
    public AttemptResponse getAttempt(String username, Long attemptId) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        return toAttemptResponse(attempt);
    }

    public AttemptResponse saveAnswer(String username, Long attemptId, SubmitEvaluationAnswerRequest request) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        requireInProgress(attempt);

        // El tiempo se protege en el backend, no solo en el frontend: una vez vencido el
        // límite (con su margen de gracia) ya no se aceptan respuestas nuevas.
        if (isPastTimeLimit(attempt)) {
            throw new IllegalArgumentException("El tiempo de la evaluación ya finalizó.");
        }

        upsertAnswer(attempt, request);
        return toAttemptResponse(attempt);
    }

    public AttemptResponse submitAttempt(String username, Long attemptId, SubmitEvaluationAttemptRequest request) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        requireInProgress(attempt);

        // Si el envío llega fuera de tiempo (más allá del margen de gracia) se ignoran las
        // respuestas que vengan en el cuerpo: solo se califican las guardadas a tiempo. El
        // intento se cierra igualmente para no dejarlo abierto indefinidamente.
        boolean pastTimeLimit = isPastTimeLimit(attempt);

        // Persistimos las respuestas que lleguen en el envío (si se enviaron y a tiempo).
        if (!pastTimeLimit && request != null && request.answers() != null) {
            for (SubmitEvaluationAnswerRequest answer : request.answers()) {
                upsertAnswer(attempt, answer);
            }
        }

        gradeAttempt(attempt);

        // La calificación de alternativa única es automática y completa, por lo que el
        // intento queda directamente GRADED (no en un SUBMITTED a la espera de revisión).
        LocalDateTime now = LocalDateTime.now();
        attempt.setStatus(AttemptStatus.GRADED);
        attempt.setSubmittedAt(now);
        attempt.setGradedAt(now);
        attemptRepository.save(attempt);

        // Solo se registra el envío del intento; nunca las respuestas individuales.
        Evaluation evaluation = attempt.getEvaluation();
        auditLogService.recordInfo(LogEventType.EVALUATION_ATTEMPT_SUBMITTED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Enviar intento",
                "Se envió un intento de la evaluación «" + evaluation.getTitle() + "».",
                "attemptId=" + attempt.getId() + (pastTimeLimit ? ";outOfTime=true" : ""));

        return toAttemptResponse(attempt);
    }

    /**
     * Registra una incidencia de foco (salida/retorno de pestaña o ventana) durante un
     * intento. Solo procede si la evaluación tiene activada la detección de salida de
     * pestaña, el intento pertenece al estudiante autenticado y sigue en progreso.
     *
     * <p>Aplica un control simple de duplicados: descarta el evento si es idéntico al
     * último registrado dentro de una ventana corta de tiempo. No guarda contenido de
     * otras pestañas, capturas ni datos sensibles: solo el tipo de evento, el momento y
     * una descripción breve. Es trazabilidad del intento, no un log global de auditoría.</p>
     */
    public AttemptEventSummaryResponse registerAttemptEvent(String username, Long attemptId,
                                                            RegisterAttemptEventRequest request) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        requireInProgress(attempt);

        Evaluation evaluation = attempt.getEvaluation();
        if (!Boolean.TRUE.equals(evaluation.getTrackTabExit())) {
            throw new IllegalArgumentException(
                    "La detección de salida de pestaña no está activada para esta evaluación.");
        }

        boolean recorded = false;
        if (!isDuplicateEvent(attempt, request.eventType())) {
            EvaluationAttemptEvent event = EvaluationAttemptEvent.builder()
                    .attempt(attempt)
                    .eventType(request.eventType())
                    .description(trimOrNull(request.description()))
                    .build();
            attemptEventRepository.save(event);
            recorded = true;
        }

        return new AttemptEventSummaryResponse(
                attempt.getId(), recorded,
                attemptEventRepository.countByAttempt(attempt),
                attemptEventRepository.countByAttemptAndEventTypeIn(attempt, EXIT_EVENT_TYPES));
    }

    // =========================================================================
    // ADMINISTRADOR
    // =========================================================================

    @Transactional(readOnly = true)
    public List<EvaluationResponse> listAllEvaluations() {
        return evaluationRepository.findAll()
                .stream()
                .map(this::toEvaluationResponse)
                .toList();
    }

    /**
     * Detalle de una evaluación para la supervisión institucional del administrador.
     * Es de solo lectura y usa un DTO específico que omite la alternativa correcta de
     * cada pregunta: el administrador supervisa, pero nunca visualiza la clave de
     * respuestas (esa información queda reservada al docente).
     */
    @Transactional(readOnly = true)
    public AdminEvaluationDetailResponse getAdminEvaluationDetail(Long evaluationId) {
        Evaluation evaluation = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new EntityNotFoundException("La evaluación no existe."));
        return toAdminEvaluationDetailResponse(evaluation);
    }

    // =========================================================================
    // RESULTADOS — DOCENTE
    // =========================================================================

    /**
     * Resultados de una evaluación propia: agregados generales y la lista de intentos
     * calificados de los estudiantes. Solo considera intentos en estados terminales.
     * No es de solo lectura porque, por seguridad, recalcula el puntaje de intentos
     * antiguos que pudieran haberse enviado sin {@code score} (ver {@link #ensureScored}).
     */
    public TeacherEvaluationResultsResponse getTeacherEvaluationResults(String username, Long evaluationId) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        List<TeacherStudentResultResponse> results = terminalAttemptsOf(evaluation).stream()
                .map(this::toTeacherStudentResult)
                .toList();

        return buildTeacherResults(evaluation, results);
    }

    /** Igual que {@link #getTeacherEvaluationResults} pero sin la lista de intentos. */
    public TeacherEvaluationResultsSummaryResponse getTeacherEvaluationResultsSummary(String username,
                                                                                      Long evaluationId) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);

        List<TeacherStudentResultResponse> results = terminalAttemptsOf(evaluation).stream()
                .map(this::toTeacherStudentResult)
                .toList();
        TeacherEvaluationResultsResponse full = buildTeacherResults(evaluation, results);

        return new TeacherEvaluationResultsSummaryResponse(
                full.evaluationId(), full.title(), full.topic(), full.maxScore(),
                full.totalAttempts(), full.averageScore(), full.averagePercentage(),
                full.highestScore(), full.lowestScore(), full.approvedCount(), full.failedCount());
    }

    /**
     * Detalle del resultado de un intento, solo si pertenece a una evaluación del
     * docente autenticado. Muestra la corrección pregunta a pregunta con la alternativa
     * correcta visible.
     */
    public TeacherAttemptResultDetailResponse getTeacherAttemptResult(String username, Long attemptId) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException("El intento no existe."));

        Evaluation evaluation = attempt.getEvaluation();
        if (!evaluation.getCreatedByTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para ver este intento.");
        }
        requireGradedResult(attempt);
        ensureScored(attempt);

        StudentProfile student = attempt.getStudent();
        List<TeacherAnswerResultResponse> answers = answerDetailsOf(attempt).stream()
                .map(d -> new TeacherAnswerResultResponse(
                        d.question.getId(), d.question.getQuestionText(),
                        d.selected == null ? null : d.selected.getId(),
                        d.selected == null ? null : d.selected.getOptionText(),
                        d.correctOption == null ? null : d.correctOption.getId(),
                        d.correctOption == null ? null : d.correctOption.getOptionText(),
                        d.isCorrect, d.question.getPoints(), d.pointsAwarded, d.question.getExplanation()))
                .toList();

        return new TeacherAttemptResultDetailResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(),
                student.getId(), student.getStudentCode(), fullName(student),
                student.getGrade(), student.getSection(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                attempt.getScore(), attempt.getMaxScore(), percentageOf(attempt),
                attempt.getStartedAt(), attempt.getSubmittedAt(), attempt.getGradedAt(),
                tabExitCountOf(attempt), answers);
    }

    // =========================================================================
    // RESULTADOS — ESTUDIANTE
    // =========================================================================

    /** Lista las calificaciones de los intentos terminales del estudiante autenticado. */
    public List<StudentResultSummaryResponse> listStudentResults(String username) {
        StudentProfile student = requireStudent(username);
        return attemptRepository
                .findByStudentAndStatusInOrderBySubmittedAtDesc(student, RESULT_STATUSES)
                .stream()
                .map(this::toStudentResultSummary)
                .toList();
    }

    /** Detalle del resultado de un intento propio del estudiante. */
    public StudentAttemptResultDetailResponse getStudentAttemptResult(String username, Long attemptId) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        requireGradedResult(attempt);
        ensureScored(attempt);

        Evaluation evaluation = attempt.getEvaluation();
        boolean canView = canViewDetailedFeedback(evaluation, student);

        List<StudentAnswerResultResponse> answers = answerDetailsOf(attempt).stream()
                .map(d -> new StudentAnswerResultResponse(
                        d.question.getId(), d.question.getQuestionText(),
                        d.selected == null ? null : d.selected.getOptionText(),
                        d.isCorrect, d.question.getPoints(), d.pointsAwarded,
                        canView && d.correctOption != null ? d.correctOption.getOptionText() : null,
                        canView ? d.question.getExplanation() : null))
                .toList();

        return new StudentAttemptResultDetailResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(), evaluation.getTopic(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                attempt.getScore(), attempt.getMaxScore(), percentageOf(attempt),
                attempt.getSubmittedAt(), canView, answers);
    }

    // =========================================================================
    // RESULTADOS — AUXILIARES
    // =========================================================================

    private List<EvaluationAttempt> terminalAttemptsOf(Evaluation evaluation) {
        List<EvaluationAttempt> attempts =
                attemptRepository.findByEvaluationAndStatusInOrderBySubmittedAtDesc(evaluation, RESULT_STATUSES);
        attempts.forEach(this::ensureScored);
        return attempts;
    }

    private TeacherEvaluationResultsResponse buildTeacherResults(Evaluation evaluation,
                                                                 List<TeacherStudentResultResponse> results) {
        int maxScore = activeMaxScore(evaluation);
        int total = results.size();

        Double averageScore = null;
        Double averagePercentage = null;
        Integer highest = null;
        Integer lowest = null;
        int approved = 0;
        int failed = 0;

        if (total > 0) {
            int sumScore = 0;
            double sumPct = 0;
            highest = Integer.MIN_VALUE;
            lowest = Integer.MAX_VALUE;
            for (TeacherStudentResultResponse r : results) {
                int score = r.score() == null ? 0 : r.score();
                double pct = r.percentage() == null ? 0 : r.percentage();
                sumScore += score;
                sumPct += pct;
                highest = Math.max(highest, score);
                lowest = Math.min(lowest, score);
                if (pct >= APPROVAL_PERCENTAGE) {
                    approved++;
                } else {
                    failed++;
                }
            }
            averageScore = round1((double) sumScore / total);
            averagePercentage = round1(sumPct / total);
        }

        return new TeacherEvaluationResultsResponse(
                evaluation.getId(), evaluation.getTitle(), evaluation.getTopic(), maxScore,
                total, averageScore, averagePercentage, highest, lowest, approved, failed, results);
    }

    private TeacherStudentResultResponse toTeacherStudentResult(EvaluationAttempt attempt) {
        StudentProfile student = attempt.getStudent();
        return new TeacherStudentResultResponse(
                attempt.getId(), student.getId(), student.getStudentCode(), fullName(student),
                student.getGrade(), student.getSection(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                attempt.getScore(), attempt.getMaxScore(), percentageOf(attempt),
                attempt.getSubmittedAt(), attempt.getGradedAt(), tabExitCountOf(attempt));
    }

    private StudentResultSummaryResponse toStudentResultSummary(EvaluationAttempt attempt) {
        ensureScored(attempt);
        Evaluation evaluation = attempt.getEvaluation();
        StudentProfile student = attempt.getStudent();
        int attemptsUsed = (int) attemptRepository.countByEvaluationAndStudent(evaluation, student);
        return new StudentResultSummaryResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(), evaluation.getTopic(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                attempt.getScore(), attempt.getMaxScore(), percentageOf(attempt),
                attempt.getSubmittedAt(), canViewDetailedFeedback(evaluation, student),
                attemptsUsed, evaluation.getMaxAttempts());
    }

    /**
     * Criterio conservador de retroalimentación: el estudiante solo ve la alternativa
     * correcta cuando ya no le quedan intentos disponibles o la evaluación está
     * archivada. Así no puede usar un resultado para acertar en un intento posterior.
     */
    private boolean canViewDetailedFeedback(Evaluation evaluation, StudentProfile student) {
        long attemptsUsed = attemptRepository.countByEvaluationAndStudent(evaluation, student);
        boolean noAttemptsLeft = attemptsUsed >= evaluation.getMaxAttempts();
        return noAttemptsLeft || evaluation.getStatus() == EvaluationStatus.ARCHIVED;
    }

    /**
     * Calcula el desglose de respuestas de un intento recorriendo las preguntas activas
     * (las inactivas no cuentan). Incluye las preguntas no respondidas para que el
     * detalle sea completo.
     */
    private List<AnswerDetail> answerDetailsOf(EvaluationAttempt attempt) {
        return questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(attempt.getEvaluation())
                .stream()
                .map(question -> {
                    List<EvaluationOption> options =
                            optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question);
                    EvaluationOption correctOption = options.stream()
                            .filter(o -> Boolean.TRUE.equals(o.getCorrect()))
                            .findFirst().orElse(null);
                    EvaluationAnswer answer =
                            answerRepository.findByAttemptAndQuestion(attempt, question).orElse(null);
                    EvaluationOption selected = answer == null ? null : answer.getSelectedOption();
                    boolean isCorrect = answer != null && Boolean.TRUE.equals(answer.getCorrect());
                    int pointsAwarded = answer == null || answer.getPointsAwarded() == null
                            ? 0 : answer.getPointsAwarded();
                    return new AnswerDetail(question, selected, correctOption, isCorrect, pointsAwarded);
                })
                .toList();
    }

    /**
     * Recalcula de forma segura el puntaje de un intento terminal al que le falte
     * {@code score}/{@code maxScore} (p. ej. intentos antiguos enviados antes de esta
     * sesión). No altera intentos en progreso ni duplica respuestas.
     */
    private void ensureScored(EvaluationAttempt attempt) {
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) {
            return;
        }
        if (attempt.getScore() == null || attempt.getMaxScore() == null) {
            gradeAttempt(attempt);
            if (attempt.getGradedAt() == null) {
                attempt.setGradedAt(attempt.getSubmittedAt() != null
                        ? attempt.getSubmittedAt() : LocalDateTime.now());
            }
            attemptRepository.save(attempt);
        }
    }

    /** Un resultado solo existe para intentos enviados/calificados, nunca en progreso. */
    private void requireGradedResult(EvaluationAttempt attempt) {
        if (attempt.getStatus() == AttemptStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("El intento aún está en progreso; todavía no tiene resultado.");
        }
    }

    private int activeMaxScore(Evaluation evaluation) {
        return questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(evaluation)
                .stream()
                .mapToInt(EvaluationQuestion::getPoints)
                .sum();
    }

    /** Cantidad de "salidas" (pestaña oculta o ventana sin foco) registradas en el intento. */
    private long tabExitCountOf(EvaluationAttempt attempt) {
        return attemptEventRepository.countByAttemptAndEventTypeIn(attempt, EXIT_EVENT_TYPES);
    }

    private Double percentageOf(EvaluationAttempt attempt) {
        Integer score = attempt.getScore();
        Integer maxScore = attempt.getMaxScore();
        if (score == null || maxScore == null || maxScore == 0) {
            return 0.0;
        }
        return round1(score * 100.0 / maxScore);
    }

    private String fullName(StudentProfile student) {
        return (student.getNames() + " " + student.getLastNames()).trim();
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** Estructura interna para el desglose de una respuesta dentro de un resultado. */
    private record AnswerDetail(
            EvaluationQuestion question,
            EvaluationOption selected,
            EvaluationOption correctOption,
            boolean isCorrect,
            int pointsAwarded
    ) {}

    // =========================================================================
    // CALIFICACIÓN AUTOMÁTICA (alternativa única)
    // =========================================================================

    /**
     * Calcula un puntaje básico para preguntas de alternativa única: una respuesta es
     * correcta si su alternativa elegida está marcada como correcta, y entonces otorga
     * los puntos de la pregunta. El puntaje máximo es la suma de los puntos de todas
     * las preguntas activas (las inactivas no cuentan) y nunca se otorga puntaje
     * negativo. Deja {@code correct} y {@code pointsAwarded} en cada respuesta y
     * {@code score}/{@code maxScore} en el intento.
     */
    private void gradeAttempt(EvaluationAttempt attempt) {
        List<EvaluationQuestion> questions =
                questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(attempt.getEvaluation());

        int maxScore = 0;
        int score = 0;

        for (EvaluationQuestion question : questions) {
            maxScore += question.getPoints();

            EvaluationAnswer answer = answerRepository.findByAttemptAndQuestion(attempt, question).orElse(null);
            if (answer == null) {
                continue;
            }

            boolean correct = answer.getSelectedOption() != null
                    && Boolean.TRUE.equals(answer.getSelectedOption().getCorrect());
            int awarded = correct ? question.getPoints() : 0;

            answer.setCorrect(correct);
            answer.setPointsAwarded(awarded);
            answerRepository.save(answer);

            score += awarded;
        }

        attempt.setScore(score);
        attempt.setMaxScore(maxScore);
    }

    // =========================================================================
    // MÉTODOS PRIVADOS AUXILIARES
    // =========================================================================

    private TeacherProfile requireTeacher(String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
        if (user.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario autenticado no es un docente.");
        }
        return teacherProfileRepository.findByUser(user)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
    }

    private StudentProfile requireStudent(String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
        if (user.getRole() != Role.ESTUDIANTE) {
            throw new IllegalArgumentException("El usuario autenticado no es un estudiante.");
        }
        return studentProfileRepository.findByStudentCode(user.getUsername())
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
    }

    /**
     * Carga una evaluación y valida que pertenezca al docente. Distingue entre
     * evaluación inexistente y evaluación de otro docente.
     */
    private Evaluation requireOwnedEvaluation(Long evaluationId, TeacherProfile teacher) {
        Evaluation evaluation = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new EntityNotFoundException("La evaluación no existe."));
        if (!evaluation.getCreatedByTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para modificar esta evaluación.");
        }
        return evaluation;
    }

    private EvaluationQuestion requireQuestionOfEvaluation(Long questionId, Evaluation evaluation) {
        EvaluationQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new EntityNotFoundException("La pregunta no existe."));
        if (!question.getEvaluation().getId().equals(evaluation.getId())) {
            throw new IllegalArgumentException("La pregunta no pertenece a esta evaluación.");
        }
        return question;
    }

    private EvaluationAssignment requireActiveAssignmentForStudent(Long evaluationId, StudentProfile student) {
        return assignmentRepository.findActiveForSectionByEvaluation(
                        evaluationId, student.getGrade(), student.getSection(), EvaluationStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException("La evaluación no está asignada a tu sección."));
    }

    private EvaluationAttempt requireOwnedAttempt(Long attemptId, StudentProfile student) {
        EvaluationAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException("El intento no existe."));
        if (!attempt.getStudent().getId().equals(student.getId())) {
            throw new IllegalArgumentException("No tienes permiso para acceder a este intento.");
        }
        return attempt;
    }

    private void requireInProgress(EvaluationAttempt attempt) {
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("El intento ya fue enviado.");
        }
    }

    /**
     * Indica si un intento superó el límite de tiempo de su evaluación, contando un
     * margen de gracia para tolerar latencia de red y desfase de reloj. Si la evaluación
     * no define límite o el intento no tiene inicio registrado, nunca está fuera de tiempo.
     */
    private boolean isPastTimeLimit(EvaluationAttempt attempt) {
        Integer limitMinutes = attempt.getEvaluation().getTimeLimitMinutes();
        if (limitMinutes == null || limitMinutes <= 0 || attempt.getStartedAt() == null) {
            return false;
        }
        LocalDateTime deadline = attempt.getStartedAt()
                .plusMinutes(limitMinutes)
                .plusSeconds(SUBMIT_GRACE_SECONDS);
        return LocalDateTime.now().isAfter(deadline);
    }

    /**
     * Control simple de duplicados: considera duplicada una incidencia si coincide en
     * tipo con la última registrada del intento y ocurrió dentro de la ventana de
     * throttling. Evita registrar ráfagas de eventos idénticos.
     */
    private boolean isDuplicateEvent(EvaluationAttempt attempt, AttemptEventType eventType) {
        return attemptEventRepository.findFirstByAttemptOrderByOccurredAtDesc(attempt)
                .filter(last -> last.getEventType() == eventType)
                .filter(last -> last.getOccurredAt() != null
                        && last.getOccurredAt().isAfter(LocalDateTime.now().minusSeconds(EVENT_THROTTLE_SECONDS)))
                .isPresent();
    }

    /**
     * Crea o actualiza la respuesta de una pregunta dentro de un intento, validando que
     * la pregunta pertenezca a la evaluación y que la alternativa pertenezca a la pregunta.
     */
    private void upsertAnswer(EvaluationAttempt attempt, SubmitEvaluationAnswerRequest request) {
        EvaluationQuestion question = questionRepository.findById(request.questionId())
                .orElseThrow(() -> new EntityNotFoundException("La pregunta no existe."));
        if (!question.getEvaluation().getId().equals(attempt.getEvaluation().getId())) {
            throw new IllegalArgumentException("La pregunta no pertenece a esta evaluación.");
        }

        EvaluationOption selectedOption = null;
        if (request.selectedOptionId() != null) {
            selectedOption = optionRepository.findById(request.selectedOptionId())
                    .orElseThrow(() -> new EntityNotFoundException("La alternativa no existe."));
            if (!selectedOption.getQuestion().getId().equals(question.getId())) {
                throw new IllegalArgumentException("La alternativa seleccionada no pertenece a la pregunta.");
            }
        }

        EvaluationAnswer answer = answerRepository.findByAttemptAndQuestion(attempt, question)
                .orElseGet(() -> EvaluationAnswer.builder()
                        .attempt(attempt)
                        .question(question)
                        .build());
        answer.setSelectedOption(selectedOption);
        answerRepository.save(answer);
    }

    /**
     * Reemplaza por completo las alternativas de una pregunta y valida que haya
     * exactamente una marcada como correcta (regla de alternativa única). Se ejecuta
     * tanto al crear como al editar una pregunta, dejándola lista para publicar.
     */
    private void replaceOptions(EvaluationQuestion question, List<CreateOptionRequest> options) {
        long correctCount = options.stream()
                .filter(o -> Boolean.TRUE.equals(o.correct()))
                .count();
        if (correctCount != 1) {
            throw new IllegalArgumentException("Cada pregunta debe tener exactamente una alternativa correcta.");
        }

        // Borra las alternativas previas (edición) y crea las nuevas en orden.
        List<EvaluationOption> existing = optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question);
        if (!existing.isEmpty()) {
            optionRepository.deleteAll(existing);
        }

        int index = 0;
        for (CreateOptionRequest opt : options) {
            EvaluationOption option = EvaluationOption.builder()
                    .question(question)
                    .optionText(opt.optionText().trim())
                    .correct(Boolean.TRUE.equals(opt.correct()))
                    .orderIndex(opt.orderIndex() == null ? index : opt.orderIndex())
                    .active(true)
                    .build();
            optionRepository.save(option);
            index++;
        }
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // =========================================================================
    // MÉTODOS PRIVADOS DE MAPEO
    // =========================================================================

    private EvaluationResponse toEvaluationResponse(Evaluation evaluation) {
        long questionCount = questionRepository.countByEvaluationAndActiveTrue(evaluation);
        long assignmentCount = assignmentRepository.findByEvaluationOrderByAssignedAtDesc(evaluation).size();

        return new EvaluationResponse(
                evaluation.getId(),
                evaluation.getTitle(),
                evaluation.getDescription(),
                evaluation.getInstructions(),
                evaluation.getTopic(),
                evaluation.getStatus(),
                evaluation.getMaxAttempts(),
                evaluation.getTimeLimitMinutes(),
                evaluation.getAllowChemicalCalculator(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                evaluation.getActive(),
                questionCount,
                assignmentCount,
                evaluation.getCreatedAt(),
                evaluation.getUpdatedAt()
        );
    }

    private EvaluationDetailResponse toEvaluationDetailResponse(Evaluation evaluation) {
        List<QuestionResponse> questions =
                questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(evaluation)
                        .stream()
                        .map(this::toQuestionResponse)
                        .toList();

        List<EvaluationAssignmentResponse> assignments =
                assignmentRepository.findByEvaluationOrderByAssignedAtDesc(evaluation)
                        .stream()
                        .map(this::toAssignmentResponse)
                        .toList();

        return new EvaluationDetailResponse(
                evaluation.getId(),
                evaluation.getTitle(),
                evaluation.getDescription(),
                evaluation.getInstructions(),
                evaluation.getTopic(),
                evaluation.getStatus(),
                evaluation.getMaxAttempts(),
                evaluation.getTimeLimitMinutes(),
                evaluation.getAllowChemicalCalculator(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                evaluation.getActive(),
                questions,
                assignments,
                evaluation.getCreatedAt(),
                evaluation.getUpdatedAt()
        );
    }

    private QuestionResponse toQuestionResponse(EvaluationQuestion question) {
        List<OptionResponse> options =
                optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question)
                        .stream()
                        .map(o -> new OptionResponse(o.getId(), o.getOptionText(), o.getCorrect(), o.getOrderIndex()))
                        .toList();

        return new QuestionResponse(
                question.getId(),
                question.getQuestionText(),
                question.getQuestionType(),
                question.getPoints(),
                question.getOrderIndex(),
                question.getExplanation(),
                options
        );
    }

    private AdminEvaluationDetailResponse toAdminEvaluationDetailResponse(Evaluation evaluation) {
        List<AdminEvaluationQuestionResponse> questions =
                questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(evaluation)
                        .stream()
                        .map(this::toAdminQuestionResponse)
                        .toList();

        List<EvaluationAssignmentResponse> assignments =
                assignmentRepository.findByEvaluationOrderByAssignedAtDesc(evaluation)
                        .stream()
                        .map(this::toAssignmentResponse)
                        .toList();

        return new AdminEvaluationDetailResponse(
                evaluation.getId(),
                evaluation.getTitle(),
                evaluation.getDescription(),
                evaluation.getInstructions(),
                evaluation.getTopic(),
                evaluation.getStatus(),
                evaluation.getMaxAttempts(),
                evaluation.getTimeLimitMinutes(),
                evaluation.getActive(),
                teacherFullName(evaluation.getCreatedByTeacher()),
                questions.size(),
                questions,
                assignments,
                evaluation.getCreatedAt(),
                evaluation.getUpdatedAt()
        );
    }

    private AdminEvaluationQuestionResponse toAdminQuestionResponse(EvaluationQuestion question) {
        // De forma deliberada se omiten el campo "correct" y la explicación: el
        // administrador supervisa el contenido sin acceder a la clave de respuestas.
        List<AdminEvaluationOptionResponse> options =
                optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question)
                        .stream()
                        .map(o -> new AdminEvaluationOptionResponse(o.getId(), o.getOptionText(), o.getOrderIndex()))
                        .toList();

        return new AdminEvaluationQuestionResponse(
                question.getId(),
                question.getQuestionText(),
                question.getQuestionType(),
                question.getPoints(),
                question.getOrderIndex(),
                options
        );
    }

    private String teacherFullName(TeacherProfile teacher) {
        if (teacher == null) {
            return null;
        }
        return teacher.getNames() + " " + teacher.getLastNames();
    }

    private EvaluationAssignmentResponse toAssignmentResponse(EvaluationAssignment assignment) {
        return new EvaluationAssignmentResponse(
                assignment.getId(),
                assignment.getGrade(),
                assignment.getSection(),
                assignment.getStartAt(),
                assignment.getDueAt(),
                assignment.getActive(),
                assignment.getAssignedAt()
        );
    }

    private StudentEvaluationResponse toStudentEvaluationResponse(EvaluationAssignment assignment, StudentProfile student) {
        Evaluation evaluation = assignment.getEvaluation();
        long questionCount = questionRepository.countByEvaluationAndActiveTrue(evaluation);
        int attemptsUsed = (int) attemptRepository.countByEvaluationAndStudent(evaluation, student);
        AttemptStatus lastStatus = attemptRepository
                .findFirstByEvaluationAndStudentOrderByAttemptNumberDesc(evaluation, student)
                .map(EvaluationAttempt::getStatus)
                .orElse(null);

        return new StudentEvaluationResponse(
                evaluation.getId(),
                evaluation.getTitle(),
                evaluation.getDescription(),
                evaluation.getInstructions(),
                evaluation.getTopic(),
                evaluation.getTimeLimitMinutes(),
                evaluation.getMaxAttempts(),
                evaluation.getAllowChemicalCalculator(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                questionCount,
                assignment.getId(),
                assignment.getStartAt(),
                assignment.getDueAt(),
                lastStatus,
                attemptsUsed
        );
    }

    private StudentEvaluationDetailResponse toStudentEvaluationDetailResponse(Evaluation evaluation, Long assignmentId) {
        List<StudentQuestionResponse> questions =
                questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(evaluation)
                        .stream()
                        .map(this::toStudentQuestionResponse)
                        .toList();

        return new StudentEvaluationDetailResponse(
                evaluation.getId(),
                evaluation.getTitle(),
                evaluation.getDescription(),
                evaluation.getInstructions(),
                evaluation.getTopic(),
                evaluation.getTimeLimitMinutes(),
                evaluation.getAllowChemicalCalculator(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                questions,
                assignmentId
        );
    }

    private StudentQuestionResponse toStudentQuestionResponse(EvaluationQuestion question) {
        // De forma deliberada se omite el campo "correct" de cada alternativa.
        List<StudentOptionResponse> options =
                optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question)
                        .stream()
                        .map(o -> new StudentOptionResponse(o.getId(), o.getOptionText(), o.getOrderIndex()))
                        .toList();

        return new StudentQuestionResponse(
                question.getId(),
                question.getQuestionText(),
                question.getQuestionType(),
                question.getPoints(),
                question.getOrderIndex(),
                options
        );
    }

    private AttemptResponse toAttemptResponse(EvaluationAttempt attempt) {
        List<EvaluationAnswerResponse> answers =
                answerRepository.findByAttemptOrderByAnsweredAtAsc(attempt)
                        .stream()
                        .map(a -> new EvaluationAnswerResponse(
                                a.getId(),
                                a.getQuestion().getId(),
                                a.getSelectedOption() == null ? null : a.getSelectedOption().getId(),
                                a.getCorrect(),
                                a.getPointsAwarded(),
                                a.getAnsweredAt()))
                        .toList();

        return new AttemptResponse(
                attempt.getId(),
                attempt.getEvaluation().getId(),
                attempt.getAssignment() == null ? null : attempt.getAssignment().getId(),
                attempt.getStatus(),
                attempt.getAttemptNumber(),
                attempt.getStartedAt(),
                attempt.getSubmittedAt(),
                attempt.getScore(),
                attempt.getMaxScore(),
                answers
        );
    }
}

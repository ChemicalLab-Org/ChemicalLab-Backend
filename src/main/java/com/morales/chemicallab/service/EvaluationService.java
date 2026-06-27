package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final EvaluationAttemptAdjustmentRepository adjustmentRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final AuditLogService auditLogService;

    private static final String TARGET_EVALUATION = "Evaluation";

    // Estados de un intento que ya representa un resultado consultable. Incluye
    // PENDING_MANUAL_REVIEW para que los intentos con preguntas abiertas sin calificar
    // aparezcan en los listados de resultados (con su estado "pendiente").
    private static final Set<AttemptStatus> RESULT_STATUSES =
            EnumSet.of(AttemptStatus.SUBMITTED, AttemptStatus.PENDING_MANUAL_REVIEW, AttemptStatus.GRADED);

    // Umbral de aprobación (en %) usado solo para los contadores aprobados/desaprobados.
    private static final double APPROVAL_PERCENTAGE = 60.0;

    // Nota máxima de la escala vigesimal (0–20) en la que se expresa la nota final del
    // intento. La nota base se deriva del puntaje en puntos (score/maxScore*20) y los
    // ajustes manuales se aplican sobre esta misma escala.
    private static final BigDecimal MAX_GRADE = new BigDecimal("20");

    // Margen de gracia (segundos) sobre el límite de tiempo, para tolerar latencia de red
    // y desfase de reloj en el envío automático del frontend. Pasado este margen, el
    // backend deja de aceptar respuestas nuevas: el tiempo deja de protegerse solo en el
    // frontend.
    private static final long SUBMIT_GRACE_SECONDS = 60;

    // Ventana mínima (segundos) entre incidencias de foco idénticas. Evita registrar
    // ráfagas de eventos duplicados (control simple de throttling).
    private static final long EVENT_THROTTLE_SECONDS = 2;

    // Tipos de incidencia que se consideran una "salida" de pestaña/ventana.
    private static final Set<AttemptEventType> EXIT_EVENT_TYPES =
            EnumSet.of(AttemptEventType.TAB_HIDDEN, AttemptEventType.WINDOW_BLUR);

    // Tipos de incidencia que se consideran un "regreso" a la pestaña/ventana.
    private static final Set<AttemptEventType> RETURN_EVENT_TYPES =
            EnumSet.of(AttemptEventType.TAB_VISIBLE, AttemptEventType.WINDOW_FOCUS);

    // Eventos de foco del navegador: solo se registran si la evaluación activa trackTabExit.
    private static final Set<AttemptEventType> FOCUS_EVENT_TYPES =
            EnumSet.of(AttemptEventType.TAB_HIDDEN, AttemptEventType.TAB_VISIBLE,
                    AttemptEventType.WINDOW_BLUR, AttemptEventType.WINDOW_FOCUS);

    // Hitos del ciclo de vida del intento: los registra el backend en sus propios métodos
    // (iniciar/enviar/salir), nunca el cliente, para que no puedan falsificarse.
    private static final Set<AttemptEventType> BACKEND_ONLY_EVENT_TYPES =
            EnumSet.of(AttemptEventType.ATTEMPT_STARTED, AttemptEventType.ATTEMPT_SUBMITTED,
                    AttemptEventType.TIME_EXPIRED, AttemptEventType.ATTEMPT_EXITED);

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
                .allowPeriodicTable(Boolean.TRUE.equals(request.allowPeriodicTable()))
                .trackTabExit(Boolean.TRUE.equals(request.trackTabExit()))
                .questionDisplayMode(request.questionDisplayMode() == null
                        ? QuestionDisplayMode.ALL_AT_ONCE : request.questionDisplayMode())
                .randomizeQuestions(Boolean.TRUE.equals(request.randomizeQuestions()))
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
        evaluation.setAllowPeriodicTable(Boolean.TRUE.equals(request.allowPeriodicTable()));
        evaluation.setTrackTabExit(Boolean.TRUE.equals(request.trackTabExit()));
        evaluation.setQuestionDisplayMode(request.questionDisplayMode() == null
                ? QuestionDisplayMode.ALL_AT_ONCE : request.questionDisplayMode());
        evaluation.setRandomizeQuestions(Boolean.TRUE.equals(request.randomizeQuestions()));

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

        // Cada pregunta de alternativa única debe tener alternativas y exactamente una
        // correcta. Las preguntas abiertas no tienen alternativas: no se validan aquí.
        for (EvaluationQuestion question : questions) {
            if (question.getQuestionType() == QuestionType.OPEN_TEXT) {
                continue;
            }
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

        QuestionType type = request.questionType() == null ? QuestionType.MULTIPLE_CHOICE : request.questionType();
        validateQuestionByType(type, request.options());

        EvaluationQuestion question = EvaluationQuestion.builder()
                .evaluation(evaluation)
                .questionText(request.questionText().trim())
                .questionType(type)
                .points(request.points())
                .orderIndex(request.orderIndex() == null ? 0 : request.orderIndex())
                .explanation(trimOrNull(request.explanation()))
                .expectedAnswer(type == QuestionType.OPEN_TEXT ? trimOrNull(request.expectedAnswer()) : null)
                .required(request.required() == null || request.required())
                .active(true)
                .build();
        questionRepository.save(question);

        // Las alternativas solo aplican a preguntas de alternativa única; las abiertas no
        // tienen ni piden alternativas.
        if (type == QuestionType.MULTIPLE_CHOICE) {
            replaceOptions(question, request.options());
        } else {
            logOpenQuestionSaved(evaluation, "Crear pregunta abierta");
        }
        return toQuestionResponse(question);
    }

    public QuestionResponse updateQuestion(String username, Long evaluationId, Long questionId,
                                           UpdateQuestionRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        Evaluation evaluation = requireOwnedEvaluation(evaluationId, teacher);
        EvaluationQuestion question = requireQuestionOfEvaluation(questionId, evaluation);

        QuestionType type = request.questionType() == null ? QuestionType.MULTIPLE_CHOICE : request.questionType();
        validateQuestionByType(type, request.options());

        question.setQuestionText(request.questionText().trim());
        question.setQuestionType(type);
        question.setPoints(request.points());
        question.setOrderIndex(request.orderIndex() == null ? 0 : request.orderIndex());
        question.setExplanation(trimOrNull(request.explanation()));
        question.setExpectedAnswer(type == QuestionType.OPEN_TEXT ? trimOrNull(request.expectedAnswer()) : null);
        question.setRequired(request.required() == null || request.required());
        questionRepository.save(question);

        if (type == QuestionType.MULTIPLE_CHOICE) {
            replaceOptions(question, request.options());
        } else {
            // Si la pregunta pasó a abierta, se eliminan las alternativas que pudiera tener.
            clearOptions(question);
            logOpenQuestionSaved(evaluation, "Editar pregunta abierta");
        }
        return toQuestionResponse(question);
    }

    /**
     * Valida las alternativas según el tipo de pregunta: una pregunta abierta no puede
     * tener alternativas; una de alternativa única debe traer al menos dos (la unicidad
     * de la correcta la valida {@link #replaceOptions}).
     */
    private void validateQuestionByType(QuestionType type, List<CreateOptionRequest> options) {
        if (type == QuestionType.OPEN_TEXT) {
            if (options != null && !options.isEmpty()) {
                throw new IllegalArgumentException("Una pregunta abierta no puede tener alternativas.");
            }
        } else if (options == null || options.size() < 2) {
            throw new IllegalArgumentException("La pregunta debe tener al menos dos alternativas.");
        }
    }

    /** Registra de forma segura (sin enunciado ni criterio) el alta/edición de una abierta. */
    private void logOpenQuestionSaved(Evaluation evaluation, String action) {
        auditLogService.recordInfo(LogEventType.EVALUATION_OPEN_QUESTION_SAVED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), action,
                "El docente guardó una pregunta abierta en la evaluación «" + evaluation.getTitle() + "».", null);
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

        // El orden de preguntas se fija aquí (aleatorio si la evaluación lo indica) y se
        // guarda en el intento, de modo que sea estable entre recargas y consultas.
        List<Long> order = buildInitialOrder(evaluation);

        EvaluationAttempt attempt = EvaluationAttempt.builder()
                .evaluation(evaluation)
                .assignment(assignment)
                .student(student)
                .attemptNumber((int) used + 1)
                .status(AttemptStatus.IN_PROGRESS)
                .questionOrder(serializeOrder(order))
                .currentQuestionIndex(0)
                .active(true)
                .build();
        attemptRepository.save(attempt);

        // Trazabilidad del intento: el inicio se registra desde el backend al crear el
        // intento (no depende del frontend), como ancla de la línea de tiempo. Se registra
        // siempre, aunque la detección de salida de pestaña esté desactivada.
        recordLifecycleEvent(attempt, AttemptEventType.ATTEMPT_STARTED,
                "El estudiante inició el intento.", null);

        return toAttemptResponse(attempt);
    }

    public AttemptResponse getAttempt(String username, Long attemptId) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        // Garantiza el orden de preguntas también para intentos antiguos (sin orden guardado).
        ensureAttemptOrder(attempt);
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

        Evaluation evaluation = attempt.getEvaluation();
        if (evaluation.getQuestionDisplayMode() == QuestionDisplayMode.ONE_BY_ONE) {
            return saveAnswerSequential(attempt, request);
        }

        upsertAnswer(attempt, request);
        return toAttemptResponse(attempt);
    }

    /**
     * Guarda la respuesta en el flujo secuencial (ONE_BY_ONE): solo se puede responder la
     * pregunta actual del orden del intento. Las anteriores quedan bloqueadas (sin
     * retroceso) y no se puede saltar a una futura. Tras guardar, se avanza a la siguiente.
     * El bloqueo se valida en el backend: el frontend no es la única protección.
     */
    private AttemptResponse saveAnswerSequential(EvaluationAttempt attempt,
                                                 SubmitEvaluationAnswerRequest request) {
        List<Long> order = ensureAttemptOrder(attempt);
        int index = attempt.getCurrentQuestionIndex() == null ? 0 : attempt.getCurrentQuestionIndex();

        if (index >= order.size()) {
            throw new IllegalArgumentException("Ya respondiste todas las preguntas de este intento.");
        }

        Long expectedId = order.get(index);
        if (!expectedId.equals(request.questionId())) {
            int requestedPos = order.indexOf(request.questionId());
            if (requestedPos >= 0 && requestedPos < index) {
                throw new IllegalArgumentException(
                        "No puedes volver a una pregunta anterior en el modo una por una.");
            }
            throw new IllegalArgumentException("Debes responder las preguntas en orden.");
        }

        upsertAnswer(attempt, request);
        // La pregunta actual queda bloqueada: se avanza a la siguiente.
        attempt.setCurrentQuestionIndex(index + 1);
        attemptRepository.save(attempt);
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
        // En el modo una por una las respuestas ya se guardaron al avanzar; reprocesar el
        // cuerpo del envío chocaría con el bloqueo de preguntas anteriores, así que se
        // ignora. En "todas juntas" sí se persisten las respuestas que lleguen (a tiempo).
        boolean sequential =
                attempt.getEvaluation().getQuestionDisplayMode() == QuestionDisplayMode.ONE_BY_ONE;

        if (!pastTimeLimit && !sequential && request != null && request.answers() != null) {
            for (SubmitEvaluationAnswerRequest answer : request.answers()) {
                upsertAnswer(attempt, answer);
            }
        }

        // Una pregunta abierta obligatoria no puede quedar en blanco en un envío normal. En
        // un cierre por tiempo agotado no se bloquea: el intento se cierra igual y esas
        // preguntas quedarán pendientes/0 en la revisión manual.
        if (!pastTimeLimit) {
            requireRequiredOpenAnswered(attempt);
        }

        // Cada pregunta abierta queda con una fila de respuesta (aunque vaya en blanco) para
        // que el docente pueda calificarla en la revisión manual.
        ensureOpenAnswerRows(attempt);

        gradeAttempt(attempt);

        // La parte de alternativa única se califica automáticamente. Si el intento contiene
        // preguntas abiertas sin revisar, queda PENDING_MANUAL_REVIEW (puntaje parcial, sin
        // gradedAt); si no, queda directamente GRADED.
        boolean pendingReview = hasPendingManualReview(attempt);
        LocalDateTime now = LocalDateTime.now();
        attempt.setSubmittedAt(now);
        if (pendingReview) {
            // Queda pendiente de revisión manual: la calificación no se cierra (el estudiante
            // no verá su nota final hasta que el docente cierre la calificación).
            attempt.setStatus(AttemptStatus.PENDING_MANUAL_REVIEW);
            attempt.setGradedAt(null);
            attempt.setGradeClosed(false);
        } else {
            // Solo alternativa única: se califica y se cierra automáticamente, como hasta ahora,
            // para que el resultado quede visible de inmediato sin acción del docente.
            attempt.setStatus(AttemptStatus.GRADED);
            attempt.setGradedAt(now);
            attempt.setGradeClosed(true);
            attempt.setGradeClosedAt(now);
        }
        recomputeFinalScore(attempt);
        attemptRepository.save(attempt);

        // Trazabilidad del intento: si el envío llega fuera de tiempo, se deja constancia
        // de que el tiempo se agotó antes de marcar el envío. Nunca se registran respuestas.
        if (pastTimeLimit) {
            recordLifecycleEvent(attempt, AttemptEventType.TIME_EXPIRED,
                    "El tiempo de la evaluación se agotó.", "source=TIME_LIMIT");
        }
        recordLifecycleEvent(attempt, AttemptEventType.ATTEMPT_SUBMITTED,
                "El estudiante envió el intento.",
                pastTimeLimit ? "source=TIME_LIMIT" : "source=USER_SUBMIT");

        // Solo se registra el envío del intento; nunca las respuestas individuales.
        Evaluation evaluation = attempt.getEvaluation();
        auditLogService.recordInfo(LogEventType.EVALUATION_ATTEMPT_SUBMITTED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Enviar intento",
                "Se envió un intento de la evaluación «" + evaluation.getTitle() + "».",
                "attemptId=" + attempt.getId() + (pastTimeLimit ? ";outOfTime=true" : ""));

        // Si quedó pendiente de revisión manual, se deja constancia segura (sin respuestas).
        if (pendingReview) {
            auditLogService.recordInfo(LogEventType.EVALUATION_ATTEMPT_PENDING_REVIEW, TARGET_EVALUATION,
                    evaluation.getId(), evaluation.getTitle(), "Intento pendiente de revisión",
                    "Un intento de la evaluación «" + evaluation.getTitle()
                            + "» quedó pendiente de revisión manual.",
                    "attemptId=" + attempt.getId());
        }

        return toAttemptResponse(attempt);
    }

    /**
     * Verifica que todas las preguntas abiertas obligatorias del intento tengan una
     * respuesta con texto no vacío. Se usa al enviar un intento a tiempo: el estudiante no
     * puede dejar en blanco una abierta obligatoria.
     */
    private void requireRequiredOpenAnswered(EvaluationAttempt attempt) {
        for (EvaluationQuestion question :
                questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(attempt.getEvaluation())) {
            if (question.getQuestionType() != QuestionType.OPEN_TEXT
                    || !Boolean.TRUE.equals(question.getRequired())) {
                continue;
            }
            EvaluationAnswer answer =
                    answerRepository.findByAttemptAndQuestion(attempt, question).orElse(null);
            if (answer == null || answer.getAnswerText() == null || answer.getAnswerText().isBlank()) {
                throw new IllegalArgumentException(
                        "Debes responder todas las preguntas abiertas obligatorias antes de enviar.");
            }
        }
    }

    /**
     * Indica si el intento tiene preguntas abiertas activas sin revisión manual completa.
     * Una pregunta abierta cuenta como pendiente si no tiene respuesta o si su respuesta
     * todavía no fue revisada por el docente.
     */
    private boolean hasPendingManualReview(EvaluationAttempt attempt) {
        return questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(attempt.getEvaluation())
                .stream()
                .filter(q -> q.getQuestionType() == QuestionType.OPEN_TEXT)
                .anyMatch(q -> {
                    EvaluationAnswer answer =
                            answerRepository.findByAttemptAndQuestion(attempt, q).orElse(null);
                    return answer == null || !Boolean.TRUE.equals(answer.getReviewed());
                });
    }

    /**
     * Finaliza un intento porque el estudiante decidió salir de la evaluación. El intento
     * se da por terminado y <b>no</b> queda retomable: se califica con las respuestas que
     * el estudiante alcanzó a guardar (las preguntas sin responder cuentan como no
     * respondidas, con cero puntos) y pasa a estado GRADED.
     *
     * <p>No se aceptan respuestas nuevas en el cuerpo: salir es un cierre, no un envío.
     * El intento cuenta como usado (igual que un envío normal), de modo que con
     * {@code maxAttempts = 1} el estudiante ya no podrá iniciar otro, y con más intentos
     * solo podrá iniciar uno nuevo si todavía le quedan disponibles.</p>
     *
     * <p>Valida que el intento sea del estudiante autenticado y que siga en progreso; un
     * docente no finaliza intentos por esta vía. No expone respuestas correctas.</p>
     */
    public AttemptResponse exitAttempt(String username, Long attemptId) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        requireInProgress(attempt);

        // Se califica con lo guardado hasta el momento: las preguntas no respondidas
        // quedan en cero según la misma lógica de calificación automática del envío.
        ensureOpenAnswerRows(attempt);
        gradeAttempt(attempt);

        // Si el intento tiene preguntas abiertas sin revisar, queda pendiente de revisión
        // manual igual que un envío normal (el docente las calificará, normalmente con 0 si
        // quedaron en blanco). Si no, se cierra como GRADED.
        boolean pendingReview = hasPendingManualReview(attempt);
        LocalDateTime now = LocalDateTime.now();
        attempt.setSubmittedAt(now);
        if (pendingReview) {
            // Queda pendiente de revisión manual: la calificación no se cierra (el estudiante
            // no verá su nota final hasta que el docente cierre la calificación).
            attempt.setStatus(AttemptStatus.PENDING_MANUAL_REVIEW);
            attempt.setGradedAt(null);
            attempt.setGradeClosed(false);
        } else {
            // Solo alternativa única: se califica y se cierra automáticamente, como hasta ahora,
            // para que el resultado quede visible de inmediato sin acción del docente.
            attempt.setStatus(AttemptStatus.GRADED);
            attempt.setGradedAt(now);
            attempt.setGradeClosed(true);
            attempt.setGradeClosedAt(now);
        }
        recomputeFinalScore(attempt);
        attemptRepository.save(attempt);

        Evaluation evaluation = attempt.getEvaluation();

        // Trazabilidad del intento: se deja constancia de la salida voluntaria (abandono)
        // como hito del ciclo de vida, siempre (no depende de trackTabExit). No cuenta como
        // "salida de pestaña" y no guarda contenido sensible: solo el tipo, el momento y un
        // motivo seguro.
        recordLifecycleEvent(attempt, AttemptEventType.ATTEMPT_EXITED,
                "El estudiante salió del intento y se dio por finalizado.",
                "reason=USER_CONFIRMED_EXIT");

        // Log de auditoría agregado: solo identifica la evaluación y marca que fue una
        // salida; nunca registra respuestas ni payloads.
        auditLogService.recordInfo(LogEventType.EVALUATION_ATTEMPT_SUBMITTED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Finalizar intento por salida",
                "Un intento de la evaluación «" + evaluation.getTitle()
                        + "» se finalizó porque el estudiante salió de la evaluación.",
                "attemptId=" + attempt.getId() + ";exited=true");

        return toAttemptResponse(attempt);
    }

    /**
     * Registra un evento de trazabilidad reportado por el frontend durante un intento:
     * incidencia de foco (salida/retorno de pestaña o ventana), intento de salida
     * ({@code EXIT_ATTEMPTED}) o uso de una herramienta permitida ({@code TOOL_OPENED}/
     * {@code TOOL_RETURNED}). Solo procede si el intento pertenece al estudiante
     * autenticado y sigue en progreso.
     *
     * <p>Reglas de registro:</p>
     * <ul>
     *   <li>Los hitos del ciclo de vida (inicio, envío, expiración, salida confirmada) los
     *       registra el backend en sus propios métodos: el cliente no puede falsificarlos.</li>
     *   <li>Las incidencias de foco solo se registran si la evaluación activa
     *       {@code trackTabExit}; el resto (intento de salida, herramientas) se registra
     *       siempre, porque es trazabilidad del intento, no detección de pérdida de foco.</li>
     * </ul>
     *
     * <p>Aplica un control simple de duplicados (descarta un evento idéntico al último
     * dentro de una ventana corta) y solo guarda metadata segura y acotada
     * ({@code tool}/{@code source}). No guarda respuestas, claves, contenido de otras
     * pestañas, capturas ni datos sensibles. Es trazabilidad del intento, no un log global
     * de auditoría.</p>
     */
    public AttemptEventSummaryResponse registerAttemptEvent(String username, Long attemptId,
                                                            RegisterAttemptEventRequest request) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        requireInProgress(attempt);

        AttemptEventType type = request.eventType();
        if (BACKEND_ONLY_EVENT_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Ese tipo de evento lo registra el sistema, no el cliente.");
        }

        Evaluation evaluation = attempt.getEvaluation();
        if (FOCUS_EVENT_TYPES.contains(type) && !Boolean.TRUE.equals(evaluation.getTrackTabExit())) {
            throw new IllegalArgumentException(
                    "La detección de salida de pestaña no está activada para esta evaluación.");
        }

        boolean recorded = false;
        if (!isDuplicateEvent(attempt, type)) {
            EvaluationAttemptEvent event = EvaluationAttemptEvent.builder()
                    .attempt(attempt)
                    .eventType(type)
                    .description(trimOrNull(request.description()))
                    .metadata(buildSafeMetadata(request.tool(), request.source()))
                    .build();
            attemptEventRepository.save(event);
            recorded = true;
        }

        return new AttemptEventSummaryResponse(
                attempt.getId(), recorded,
                attemptEventRepository.countByAttempt(attempt),
                attemptEventRepository.countByAttemptAndEventTypeIn(attempt, EXIT_EVENT_TYPES));
    }

    /**
     * Trazabilidad completa de un intento para el docente: resumen (tiempo usado, estado
     * final, salidas/regresos de pestaña, intentos de salida, herramientas consultadas) y
     * la línea de tiempo de eventos. Solo accesible si el intento pertenece a una
     * evaluación creada por el docente autenticado. Nunca expone respuestas ni claves.
     */
    @Transactional(readOnly = true)
    public AttemptTraceabilityResponse getAttemptTraceability(String username, Long attemptId) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException("El intento no existe."));

        Evaluation evaluation = attempt.getEvaluation();
        if (!evaluation.getCreatedByTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para ver este intento.");
        }

        List<EvaluationAttemptEvent> events =
                attemptEventRepository.findByAttemptOrderByOccurredAtAsc(attempt);

        long tabExit = 0;
        long tabReturn = 0;
        long exitAttempts = 0;
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        List<AttemptEventResponse> timeline = new ArrayList<>(events.size());

        for (EvaluationAttemptEvent e : events) {
            AttemptEventType t = e.getEventType();
            if (EXIT_EVENT_TYPES.contains(t)) {
                tabExit++;
            } else if (RETURN_EVENT_TYPES.contains(t)) {
                tabReturn++;
            } else if (t == AttemptEventType.EXIT_ATTEMPTED) {
                exitAttempts++;
            } else if (t == AttemptEventType.TOOL_OPENED) {
                String tool = toolFromMetadata(e.getMetadata());
                if (tool != null) {
                    tools.add(tool);
                }
            }
            timeline.add(new AttemptEventResponse(
                    e.getId(), t, e.getDescription(), e.getMetadata(), e.getOccurredAt()));
        }

        StudentProfile s = attempt.getStudent();
        return new AttemptTraceabilityResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(),
                s.getId(), s.getStudentCode(), fullName(s),
                attempt.getStatus(), attempt.getStartedAt(), attempt.getSubmittedAt(),
                timeUsedSecondsOf(attempt), Boolean.TRUE.equals(evaluation.getTrackTabExit()),
                events.size(), tabExit, tabReturn, exitAttempts,
                new ArrayList<>(tools), timeline);
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
                .map(d -> {
                    boolean open = d.question.getQuestionType() == QuestionType.OPEN_TEXT;
                    return new TeacherAnswerResultResponse(
                            d.question.getId(), d.question.getQuestionText(), d.question.getQuestionType(),
                            d.selected == null ? null : d.selected.getId(),
                            d.selected == null ? null : d.selected.getOptionText(),
                            d.correctOption == null ? null : d.correctOption.getId(),
                            d.correctOption == null ? null : d.correctOption.getOptionText(),
                            d.answerText,
                            // "correct" solo aplica a alternativa única.
                            open ? null : d.isCorrect,
                            d.question.getPoints(),
                            // En una abierta sin revisar todavía no hay puntaje asignado.
                            open && !d.reviewed ? null : d.pointsAwarded,
                            d.reviewed, d.teacherFeedback, d.question.getExplanation());
                })
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
    // REVISIÓN MANUAL — DOCENTE
    // =========================================================================

    /**
     * Bandeja de revisión manual del docente: intentos de sus evaluaciones que quedaron
     * pendientes de calificar por contener preguntas abiertas. No expone el texto de las
     * respuestas, solo el resumen del intento.
     */
    @Transactional(readOnly = true)
    public List<PendingReviewAttemptResponse> listPendingManualReview(String username) {
        TeacherProfile teacher = requireTeacher(username);
        return attemptRepository
                .findByEvaluation_CreatedByTeacherAndStatusOrderBySubmittedAtDesc(
                        teacher, AttemptStatus.PENDING_MANUAL_REVIEW)
                .stream()
                .map(this::toPendingReviewAttempt)
                .toList();
    }

    /**
     * Detalle de un intento para la revisión manual: las preguntas abiertas con la
     * respuesta del estudiante, el puntaje máximo y el criterio de corrección (solo
     * docente). Solo accesible si el intento pertenece a una evaluación del docente.
     */
    @Transactional(readOnly = true)
    public TeacherAttemptReviewResponse getAttemptReview(String username, Long attemptId) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = requireOwnedAttemptForTeacher(attemptId, teacher);
        requireGradedResult(attempt);
        return toAttemptReview(attempt);
    }

    /**
     * Califica manualmente una respuesta abierta: asigna un puntaje (entre 0 y el máximo
     * de la pregunta) y una retroalimentación opcional. Tras guardar, recalcula la nota del
     * intento y, si ya no quedan respuestas abiertas pendientes, lo marca como GRADED.
     * Solo el docente dueño de la evaluación puede revisar el intento.
     */
    public TeacherAttemptReviewResponse manualGradeAnswer(String username, Long attemptId, Long answerId,
                                                          ManualGradeRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = requireOwnedAttemptForTeacher(attemptId, teacher);
        requireGradedResult(attempt);
        requireNotClosed(attempt);

        EvaluationAnswer answer = answerRepository.findById(answerId)
                .orElseThrow(() -> new EntityNotFoundException("La respuesta no existe."));
        if (!answer.getAttempt().getId().equals(attempt.getId())) {
            throw new IllegalArgumentException("La respuesta no pertenece a este intento.");
        }
        EvaluationQuestion question = answer.getQuestion();
        if (question.getQuestionType() != QuestionType.OPEN_TEXT) {
            throw new IllegalArgumentException("Solo se califican manualmente las preguntas abiertas.");
        }
        if (request.score() > question.getPoints()) {
            throw new IllegalArgumentException("El puntaje no puede superar el máximo de la pregunta.");
        }

        answer.setPointsAwarded(request.score());
        answer.setTeacherFeedback(trimOrNull(request.feedback()));
        answer.setReviewed(true);
        answer.setReviewedAt(LocalDateTime.now());
        answer.setReviewedBy(teacher);
        answer.setCorrect(null);
        answerRepository.save(answer);

        Evaluation evaluation = attempt.getEvaluation();
        // Log seguro: nunca incluye el texto de la respuesta ni la retroalimentación.
        auditLogService.recordInfo(LogEventType.EVALUATION_ANSWER_REVIEWED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Revisar respuesta abierta",
                "El docente revisó una respuesta abierta de la evaluación «" + evaluation.getTitle() + "».",
                "attemptId=" + attempt.getId() + ";answerId=" + answer.getId());

        if (recalculateAfterReview(attempt)) {
            logReviewCompleted(attempt, evaluation);
        }
        return toAttemptReview(attempt);
    }

    /**
     * Cierra la revisión de un intento: exige que no queden respuestas abiertas pendientes,
     * recalcula la nota final y lo marca como GRADED. Útil como confirmación explícita del
     * docente. Es idempotente si el intento ya estaba calificado.
     */
    public TeacherAttemptReviewResponse completeReview(String username, Long attemptId) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = requireOwnedAttemptForTeacher(attemptId, teacher);
        requireGradedResult(attempt);

        requireNotClosed(attempt);

        if (hasPendingManualReview(attempt)) {
            throw new IllegalArgumentException("Aún quedan respuestas abiertas por revisar.");
        }

        boolean wasGraded = attempt.getStatus() == AttemptStatus.GRADED;
        gradeAttempt(attempt);
        attempt.setStatus(AttemptStatus.GRADED);
        if (attempt.getGradedAt() == null) {
            attempt.setGradedAt(LocalDateTime.now());
        }
        recomputeFinalScore(attempt);
        attemptRepository.save(attempt);

        if (!wasGraded) {
            logReviewCompleted(attempt, attempt.getEvaluation());
        }
        return toAttemptReview(attempt);
    }

    /**
     * Recalcula la nota del intento tras una revisión manual y actualiza su estado: queda
     * PENDING_MANUAL_REVIEW si aún hay abiertas sin revisar, o GRADED si ya están todas.
     * Devuelve true solo cuando el intento <i>acaba</i> de pasar a GRADED (para registrar
     * el cierre una sola vez).
     */
    private boolean recalculateAfterReview(EvaluationAttempt attempt) {
        boolean wasGraded = attempt.getStatus() == AttemptStatus.GRADED;
        gradeAttempt(attempt);
        if (hasPendingManualReview(attempt)) {
            attempt.setStatus(AttemptStatus.PENDING_MANUAL_REVIEW);
            attempt.setGradedAt(null);
            recomputeFinalScore(attempt);
            attemptRepository.save(attempt);
            return false;
        }
        // Todas las abiertas quedaron revisadas: el intento pasa a GRADED, pero no se cierra
        // automáticamente. El docente puede aún ajustar puntajes/ajustes y debe cerrar la
        // calificación de forma explícita para que el estudiante vea su nota final.
        attempt.setStatus(AttemptStatus.GRADED);
        if (attempt.getGradedAt() == null) {
            attempt.setGradedAt(LocalDateTime.now());
        }
        recomputeFinalScore(attempt);
        attemptRepository.save(attempt);
        return !wasGraded;
    }

    private void logReviewCompleted(EvaluationAttempt attempt, Evaluation evaluation) {
        auditLogService.recordInfo(LogEventType.EVALUATION_REVIEW_COMPLETED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Completar revisión de intento",
                "Se actualizó la calificación manual de un intento de la evaluación «"
                        + evaluation.getTitle() + "».",
                "attemptId=" + attempt.getId());
    }

    // =========================================================================
    // AJUSTES MANUALES, RETROALIMENTACIÓN GENERAL Y CIERRE — DOCENTE
    // =========================================================================

    /**
     * Agrega un ajuste manual de puntaje al intento completo (bonificación si el monto es
     * positivo, penalización si es negativo) con un motivo obligatorio. El ajuste no
     * sobrescribe la nota: queda registrado con su autor y fecha, y la nota final se
     * recompone como la nota base (0–20) más la suma de los ajustes activos, acotada a
     * [0, 20]. Solo el docente dueño de la evaluación puede ajustar, y solo mientras la
     * calificación no esté cerrada.
     */
    public TeacherAttemptReviewResponse addAdjustment(String username, Long attemptId,
                                                      CreateAdjustmentRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = requireOwnedAttemptForTeacher(attemptId, teacher);
        requireGradedResult(attempt);
        requireNotClosed(attempt);

        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("El monto del ajuste no puede ser cero.");
        }
        String reason = trimOrNull(request.reason());
        if (reason == null) {
            throw new IllegalArgumentException("El motivo del ajuste es obligatorio.");
        }
        AdjustmentType type = amount.signum() > 0 ? AdjustmentType.BONUS : AdjustmentType.PENALTY;

        EvaluationAttemptAdjustment adjustment = EvaluationAttemptAdjustment.builder()
                .attempt(attempt).amount(amount).type(type).reason(reason)
                .createdBy(teacher).active(true).build();
        adjustmentRepository.save(adjustment);

        recomputeFinalScore(attempt);
        attemptRepository.save(attempt);

        Evaluation evaluation = attempt.getEvaluation();
        // Log seguro: registra el tipo (enum) y los identificadores, nunca el monto exacto ni
        // el motivo escrito por el docente.
        auditLogService.recordInfo(LogEventType.EVALUATION_ADJUSTMENT_ADDED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Agregar ajuste de puntaje",
                "Se agregó un ajuste de puntaje al intento de la evaluación «"
                        + evaluation.getTitle() + "».",
                "attemptId=" + attempt.getId() + ";adjustmentId=" + adjustment.getId()
                        + ";type=" + type.name());

        return toAttemptReview(attempt);
    }

    /**
     * Anula (de forma lógica) un ajuste manual de un intento: deja de afectar la nota final
     * pero permanece registrado para conservar la trazabilidad. Solo el docente dueño puede
     * anularlo y solo mientras la calificación no esté cerrada.
     */
    public TeacherAttemptReviewResponse deleteAdjustment(String username, Long attemptId,
                                                         Long adjustmentId) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = requireOwnedAttemptForTeacher(attemptId, teacher);
        requireGradedResult(attempt);
        requireNotClosed(attempt);

        EvaluationAttemptAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new EntityNotFoundException("El ajuste no existe."));
        if (!adjustment.getAttempt().getId().equals(attempt.getId())) {
            throw new IllegalArgumentException("El ajuste no pertenece a este intento.");
        }

        if (Boolean.TRUE.equals(adjustment.getActive())) {
            adjustment.setActive(false);
            adjustmentRepository.save(adjustment);
            recomputeFinalScore(attempt);
            attemptRepository.save(attempt);

            Evaluation evaluation = attempt.getEvaluation();
            auditLogService.recordInfo(LogEventType.EVALUATION_ADJUSTMENT_REMOVED, TARGET_EVALUATION,
                    evaluation.getId(), evaluation.getTitle(), "Anular ajuste de puntaje",
                    "Se anuló un ajuste de puntaje del intento de la evaluación «"
                            + evaluation.getTitle() + "».",
                    "attemptId=" + attempt.getId() + ";adjustmentId=" + adjustment.getId());
        }
        return toAttemptReview(attempt);
    }

    /**
     * Guarda la retroalimentación general que el docente escribe para el estudiante sobre el
     * intento. Es opcional (puede vaciarse) y solo se le muestra al estudiante una vez que la
     * calificación esté cerrada. Solo el docente dueño puede editarla y solo si no está
     * cerrada.
     */
    public TeacherAttemptReviewResponse updateOverallFeedback(String username, Long attemptId,
                                                              UpdateAttemptFeedbackRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = requireOwnedAttemptForTeacher(attemptId, teacher);
        requireGradedResult(attempt);
        requireNotClosed(attempt);

        attempt.setOverallFeedback(trimOrNull(request.overallFeedback()));
        attemptRepository.save(attempt);

        Evaluation evaluation = attempt.getEvaluation();
        // Log seguro: nunca incluye el texto de la retroalimentación, solo el identificador.
        auditLogService.recordInfo(LogEventType.EVALUATION_FEEDBACK_UPDATED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Actualizar retroalimentación general",
                "Se actualizó la retroalimentación general de un intento de la evaluación «"
                        + evaluation.getTitle() + "».",
                "attemptId=" + attempt.getId());

        return toAttemptReview(attempt);
    }

    /**
     * Cierra la calificación de un intento: exige que no queden respuestas abiertas por
     * revisar, recalcula el puntaje y la nota final (con los ajustes manuales), y deja la
     * nota visible para el estudiante. Registra fecha y docente del cierre y bloquea la
     * edición posterior de puntajes, ajustes y retroalimentación (no se implementa
     * reapertura en esta sesión). Solo el docente dueño puede cerrar.
     */
    public TeacherAttemptReviewResponse closeGrade(String username, Long attemptId) {
        TeacherProfile teacher = requireTeacher(username);
        EvaluationAttempt attempt = requireOwnedAttemptForTeacher(attemptId, teacher);
        requireGradedResult(attempt);

        if (Boolean.TRUE.equals(attempt.getGradeClosed())) {
            throw new IllegalArgumentException("La calificación ya está cerrada.");
        }
        if (hasPendingManualReview(attempt)) {
            throw new IllegalArgumentException("Aún quedan respuestas abiertas por revisar.");
        }

        gradeAttempt(attempt);
        attempt.setStatus(AttemptStatus.GRADED);
        recomputeFinalScore(attempt);
        LocalDateTime now = LocalDateTime.now();
        if (attempt.getGradedAt() == null) {
            attempt.setGradedAt(now);
        }
        attempt.setGradeClosed(true);
        attempt.setGradeClosedAt(now);
        attempt.setGradeClosedBy(teacher);
        attemptRepository.save(attempt);

        Evaluation evaluation = attempt.getEvaluation();
        auditLogService.recordInfo(LogEventType.EVALUATION_GRADE_CLOSED, TARGET_EVALUATION,
                evaluation.getId(), evaluation.getTitle(), "Cerrar calificación",
                "Se cerró la calificación de un intento de la evaluación «"
                        + evaluation.getTitle() + "».",
                "attemptId=" + attempt.getId());

        return toAttemptReview(attempt);
    }

    /** Impide modificar la calificación de un intento ya cerrado (no hay reapertura). */
    private void requireNotClosed(EvaluationAttempt attempt) {
        if (Boolean.TRUE.equals(attempt.getGradeClosed())) {
            throw new IllegalArgumentException(
                    "La calificación ya está cerrada; no se puede modificar.");
        }
    }

    /** Nota base del intento en escala 0–20, derivada del puntaje en puntos. */
    private BigDecimal baseScore20(EvaluationAttempt attempt) {
        Integer score = attempt.getScore();
        Integer maxScore = attempt.getMaxScore();
        if (score == null || maxScore == null || maxScore == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(score)
                .multiply(MAX_GRADE)
                .divide(BigDecimal.valueOf(maxScore), 2, RoundingMode.HALF_UP);
    }

    /** Suma de los ajustes manuales activos del intento (en escala 0–20, con su signo). */
    private BigDecimal adjustmentsTotal(EvaluationAttempt attempt) {
        return adjustmentRepository.findByAttemptAndActiveTrueOrderByCreatedAtAsc(attempt).stream()
                .map(EvaluationAttemptAdjustment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Nota final del intento en escala 0–20: nota base más la suma de los ajustes activos,
     * acotada a [0, 20] para que nunca sea negativa ni supere el máximo de la escala.
     */
    private BigDecimal computeFinalScore(EvaluationAttempt attempt) {
        BigDecimal raw = baseScore20(attempt).add(adjustmentsTotal(attempt));
        BigDecimal clamped = raw.max(BigDecimal.ZERO).min(MAX_GRADE);
        return clamped.setScale(2, RoundingMode.HALF_UP);
    }

    /** Recalcula y fija la nota final del intento (el llamador persiste el intento). */
    private void recomputeFinalScore(EvaluationAttempt attempt) {
        attempt.setFinalScore(computeFinalScore(attempt));
    }

    private AttemptAdjustmentResponse toAdjustmentResponse(EvaluationAttemptAdjustment a) {
        return new AttemptAdjustmentResponse(
                a.getId(), a.getAmount(), a.getType(), a.getReason(),
                a.getCreatedBy() == null ? null : fullName(a.getCreatedBy()), a.getCreatedAt());
    }

    /** Preguntas abiertas activas de una evaluación, en su orden. */
    private List<EvaluationQuestion> openQuestionsOf(Evaluation evaluation) {
        return questionRepository.findByEvaluationAndActiveTrueOrderByOrderIndexAsc(evaluation)
                .stream()
                .filter(q -> q.getQuestionType() == QuestionType.OPEN_TEXT)
                .toList();
    }

    /**
     * Crea una fila de respuesta vacía (sin texto, sin revisar) para cada pregunta abierta
     * del intento que aún no tenga una. Así toda pregunta abierta tiene un identificador de
     * respuesta que el docente puede calificar, incluso si el estudiante la dejó en blanco.
     */
    private void ensureOpenAnswerRows(EvaluationAttempt attempt) {
        for (EvaluationQuestion question : openQuestionsOf(attempt.getEvaluation())) {
            if (answerRepository.findByAttemptAndQuestion(attempt, question).isEmpty()) {
                answerRepository.save(EvaluationAnswer.builder()
                        .attempt(attempt)
                        .question(question)
                        .reviewed(false)
                        .build());
            }
        }
    }

    private PendingReviewAttemptResponse toPendingReviewAttempt(EvaluationAttempt attempt) {
        Evaluation evaluation = attempt.getEvaluation();
        StudentProfile s = attempt.getStudent();
        int open = 0;
        int pending = 0;
        for (EvaluationQuestion q : openQuestionsOf(evaluation)) {
            open++;
            EvaluationAnswer a = answerRepository.findByAttemptAndQuestion(attempt, q).orElse(null);
            if (a == null || !Boolean.TRUE.equals(a.getReviewed())) {
                pending++;
            }
        }
        return new PendingReviewAttemptResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(),
                s.getId(), s.getStudentCode(), fullName(s), s.getGrade(), s.getSection(),
                attempt.getAttemptNumber(), attempt.getStatus(), attempt.getSubmittedAt(),
                open, pending);
    }

    private TeacherAttemptReviewResponse toAttemptReview(EvaluationAttempt attempt) {
        Evaluation evaluation = attempt.getEvaluation();
        StudentProfile s = attempt.getStudent();
        List<TeacherReviewAnswerResponse> openAnswers = new ArrayList<>();
        int pending = 0;
        for (EvaluationQuestion q : openQuestionsOf(evaluation)) {
            EvaluationAnswer a = answerRepository.findByAttemptAndQuestion(attempt, q).orElse(null);
            boolean reviewed = a != null && Boolean.TRUE.equals(a.getReviewed());
            if (!reviewed) {
                pending++;
            }
            openAnswers.add(new TeacherReviewAnswerResponse(
                    a == null ? null : a.getId(), q.getId(), q.getQuestionText(), q.getPoints(),
                    q.getExpectedAnswer(), a == null ? null : a.getAnswerText(),
                    reviewed, a == null ? null : a.getPointsAwarded(),
                    a == null ? null : a.getTeacherFeedback()));
        }
        List<AttemptAdjustmentResponse> adjustments =
                adjustmentRepository.findByAttemptAndActiveTrueOrderByCreatedAtAsc(attempt).stream()
                        .map(this::toAdjustmentResponse)
                        .toList();
        return new TeacherAttemptReviewResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(),
                s.getId(), s.getStudentCode(), fullName(s), s.getGrade(), s.getSection(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                attempt.getScore(), attempt.getMaxScore(),
                baseScore20(attempt), adjustmentsTotal(attempt), computeFinalScore(attempt),
                attempt.getOverallFeedback(), attempt.getGradeClosed(), attempt.getGradeClosedAt(),
                attempt.getSubmittedAt(), attempt.getGradedAt(), pending, openAnswers, adjustments);
    }

    /**
     * Carga un intento y valida que pertenezca a una evaluación creada por el docente
     * autenticado. Distingue intento inexistente de intento ajeno.
     */
    private EvaluationAttempt requireOwnedAttemptForTeacher(Long attemptId, TeacherProfile teacher) {
        EvaluationAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new EntityNotFoundException("El intento no existe."));
        if (!attempt.getEvaluation().getCreatedByTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para ver este intento.");
        }
        return attempt;
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
        // Mientras la calificación no esté cerrada, el estudiante no ve su nota final ni el
        // detalle de puntajes/retroalimentación: el intento aparece como pendiente de
        // revisión. Solo se revela todo (nota, puntajes por pregunta, comentarios) al cerrar.
        boolean closed = Boolean.TRUE.equals(attempt.getGradeClosed());
        boolean canView = canViewDetailedFeedback(evaluation, student);

        List<StudentAnswerResultResponse> answers = answerDetailsOf(attempt).stream()
                .map(d -> {
                    boolean open = d.question.getQuestionType() == QuestionType.OPEN_TEXT;
                    return new StudentAnswerResultResponse(
                            d.question.getId(), d.question.getQuestionText(), d.question.getQuestionType(),
                            // El estudiante siempre ve su alternativa elegida o su propio texto.
                            d.selected == null ? null : d.selected.getOptionText(),
                            open ? d.answerText : null,
                            closed && !open ? d.isCorrect : null,
                            d.question.getPoints(),
                            // El puntaje por pregunta y la retroalimentación solo se muestran con
                            // la calificación cerrada (y, en abiertas, una vez revisadas).
                            closed && !(open && !d.reviewed) ? d.pointsAwarded : null,
                            closed && d.reviewed,
                            closed && open && d.reviewed ? d.teacherFeedback : null,
                            closed && canView && !open && d.correctOption != null
                                    ? d.correctOption.getOptionText() : null,
                            closed && canView ? d.question.getExplanation() : null);
                })
                .toList();

        return new StudentAttemptResultDetailResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(), evaluation.getTopic(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                closed ? attempt.getScore() : null,
                closed ? attempt.getMaxScore() : null,
                closed ? percentageOf(attempt) : null,
                closed ? computeFinalScore(attempt) : null,
                closed ? attempt.getOverallFeedback() : null,
                closed,
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

        // Los promedios y los contadores aprobado/desaprobado solo consideran intentos ya
        // calificados (GRADED). Los pendientes de revisión manual aparecen en la lista con
        // su estado, pero su nota aún es parcial y no debe afectar las estadísticas.
        int graded = 0;
        int sumScore = 0;
        double sumPct = 0;
        for (TeacherStudentResultResponse r : results) {
            if (r.status() != AttemptStatus.GRADED) {
                continue;
            }
            int score = r.score() == null ? 0 : r.score();
            double pct = r.percentage() == null ? 0 : r.percentage();
            graded++;
            sumScore += score;
            sumPct += pct;
            highest = highest == null ? score : Math.max(highest, score);
            lowest = lowest == null ? score : Math.min(lowest, score);
            if (pct >= APPROVAL_PERCENTAGE) {
                approved++;
            } else {
                failed++;
            }
        }
        if (graded > 0) {
            averageScore = round1((double) sumScore / graded);
            averagePercentage = round1(sumPct / graded);
        }

        return new TeacherEvaluationResultsResponse(
                evaluation.getId(), evaluation.getTitle(), evaluation.getTopic(), maxScore,
                total, averageScore, averagePercentage, highest, lowest, approved, failed, results);
    }

    private TeacherStudentResultResponse toTeacherStudentResult(EvaluationAttempt attempt) {
        StudentProfile student = attempt.getStudent();
        boolean closed = Boolean.TRUE.equals(attempt.getGradeClosed());
        return new TeacherStudentResultResponse(
                attempt.getId(), student.getId(), student.getStudentCode(), fullName(student),
                student.getGrade(), student.getSection(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                attempt.getScore(), attempt.getMaxScore(), percentageOf(attempt),
                // La nota final (con ajustes) solo es definitiva con la calificación cerrada.
                closed ? computeFinalScore(attempt) : null, closed,
                attempt.getSubmittedAt(), attempt.getGradedAt(), tabExitCountOf(attempt));
    }

    private StudentResultSummaryResponse toStudentResultSummary(EvaluationAttempt attempt) {
        ensureScored(attempt);
        Evaluation evaluation = attempt.getEvaluation();
        StudentProfile student = attempt.getStudent();
        boolean closed = Boolean.TRUE.equals(attempt.getGradeClosed());
        int attemptsUsed = (int) attemptRepository.countByEvaluationAndStudent(evaluation, student);
        return new StudentResultSummaryResponse(
                attempt.getId(), evaluation.getId(), evaluation.getTitle(), evaluation.getTopic(),
                attempt.getAttemptNumber(), attempt.getStatus(),
                // Antes del cierre no se expone la nota: el estudiante ve "pendiente de revisión".
                closed ? attempt.getScore() : null,
                closed ? attempt.getMaxScore() : null,
                closed ? percentageOf(attempt) : null,
                closed ? computeFinalScore(attempt) : null, closed,
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
                    boolean open = question.getQuestionType() == QuestionType.OPEN_TEXT;
                    EvaluationOption correctOption = open ? null
                            : optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question).stream()
                                    .filter(o -> Boolean.TRUE.equals(o.getCorrect()))
                                    .findFirst().orElse(null);
                    EvaluationAnswer answer =
                            answerRepository.findByAttemptAndQuestion(attempt, question).orElse(null);
                    EvaluationOption selected = answer == null ? null : answer.getSelectedOption();
                    boolean isCorrect = answer != null && Boolean.TRUE.equals(answer.getCorrect());
                    int pointsAwarded = answer == null || answer.getPointsAwarded() == null
                            ? 0 : answer.getPointsAwarded();
                    String answerText = answer == null ? null : answer.getAnswerText();
                    // Una abierta sin respuesta cuenta como no revisada (pendiente); el resto
                    // hereda el flag de la respuesta, y la alternativa única siempre está revisada.
                    boolean reviewed = answer == null ? !open : Boolean.TRUE.equals(answer.getReviewed());
                    String teacherFeedback = answer == null ? null : answer.getTeacherFeedback();
                    return new AnswerDetail(question, selected, correctOption, isCorrect, pointsAwarded,
                            answerText, reviewed, teacherFeedback);
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
            // gradedAt solo se fija para intentos ya calificados; un intento pendiente de
            // revisión manual conserva gradedAt en null hasta que el docente cierre la revisión.
            if (attempt.getStatus() == AttemptStatus.GRADED && attempt.getGradedAt() == null) {
                attempt.setGradedAt(attempt.getSubmittedAt() != null
                        ? attempt.getSubmittedAt() : LocalDateTime.now());
            }
            recomputeFinalScore(attempt);
            attemptRepository.save(attempt);
        } else if (attempt.getFinalScore() == null) {
            // Intentos terminales con puntaje pero sin nota final (anteriores a esta sesión):
            // se completa la nota final en escala 0–20 sin recalcular el puntaje en puntos.
            recomputeFinalScore(attempt);
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

    /**
     * Tiempo usado por el intento, en segundos, calculado en el backend con los timestamps
     * del propio intento (no con el contador del frontend). Si el intento ya terminó, se
     * mide hasta {@code submittedAt} (también cubre el cierre por salida o por tiempo); si
     * sigue en progreso, hasta el momento actual. Nunca devuelve un valor negativo.
     */
    private Long timeUsedSecondsOf(EvaluationAttempt attempt) {
        if (attempt.getStartedAt() == null) {
            return null;
        }
        LocalDateTime end = attempt.getSubmittedAt() != null
                ? attempt.getSubmittedAt() : LocalDateTime.now();
        return Math.max(0, Duration.between(attempt.getStartedAt(), end).getSeconds());
    }

    /**
     * Registra un hito del ciclo de vida del intento (inicio, envío, expiración, salida).
     * Lo emite el backend, no el cliente, y se guarda siempre (no depende de
     * {@code trackTabExit}) para que la línea de tiempo del intento quede completa. Solo
     * persiste tipo, descripción breve y metadata segura.
     */
    private void recordLifecycleEvent(EvaluationAttempt attempt, AttemptEventType type,
                                      String description, String metadata) {
        attemptEventRepository.save(EvaluationAttemptEvent.builder()
                .attempt(attempt)
                .eventType(type)
                .description(description)
                .metadata(metadata)
                .build());
    }

    /**
     * Construye una metadata segura y acotada a partir de los únicos datos permitidos:
     * la herramienta (enum cerrado) y un origen corto sanitizado. Devuelve null si no hay
     * nada que guardar. Por diseño no puede transportar respuestas, claves ni texto libre.
     */
    private String buildSafeMetadata(AttemptTool tool, String source) {
        List<String> parts = new ArrayList<>();
        if (tool != null) {
            parts.add("tool=" + tool.name());
        }
        String safeSource = sanitizeMetadataToken(source);
        if (safeSource != null) {
            parts.add("source=" + safeSource);
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }

    /**
     * Limpia un valor de metadata enviado por el cliente: conserva solo letras, dígitos y
     * guion bajo, y lo acota a 60 caracteres. Así un valor con contenido inesperado o
     * sensible queda reducido a una etiqueta corta e inocua (o null si queda vacío).
     */
    private String sanitizeMetadataToken(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    /** Extrae el nombre de la herramienta de una metadata "tool=...". Devuelve null si no hay. */
    private String toolFromMetadata(String metadata) {
        if (metadata == null) {
            return null;
        }
        for (String part : metadata.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("tool=")) {
                String value = trimmed.substring("tool=".length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
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

    private String fullName(TeacherProfile teacher) {
        return (teacher.getNames() + " " + teacher.getLastNames()).trim();
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
            int pointsAwarded,
            String answerText,
            boolean reviewed,
            String teacherFeedback
    ) {}

    // =========================================================================
    // CALIFICACIÓN AUTOMÁTICA (alternativa única)
    // =========================================================================

    /**
     * Calcula el puntaje del intento. El puntaje máximo es la suma de los puntos de todas
     * las preguntas activas (las inactivas no cuentan), sea cual sea su tipo.
     *
     * <p>Las preguntas de alternativa única se califican automáticamente: la respuesta es
     * correcta si la alternativa elegida está marcada como correcta, y entonces otorga los
     * puntos de la pregunta. Las preguntas abiertas no se califican aquí: solo suman al
     * puntaje el valor que el docente les haya asignado manualmente (sus respuestas ya
     * revisadas); mientras no estén revisadas aportan 0 y no se tocan. Nunca se otorga
     * puntaje negativo. Deja {@code score}/{@code maxScore} en el intento.</p>
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

            if (question.getQuestionType() == QuestionType.OPEN_TEXT) {
                // Calificación manual: solo cuenta si el docente ya la revisó. No se modifica
                // la respuesta (su puntaje lo fija la revisión manual).
                if (Boolean.TRUE.equals(answer.getReviewed()) && answer.getPointsAwarded() != null) {
                    score += answer.getPointsAwarded();
                }
                continue;
            }

            boolean correct = answer.getSelectedOption() != null
                    && Boolean.TRUE.equals(answer.getSelectedOption().getCorrect());
            int awarded = correct ? question.getPoints() : 0;

            answer.setCorrect(correct);
            answer.setPointsAwarded(awarded);
            answer.setReviewed(true);
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

    // =========================================================================
    // ORDEN DE PREGUNTAS POR INTENTO
    // =========================================================================

    /**
     * Construye el orden inicial de preguntas de un intento a partir de las preguntas
     * activas. Si la evaluación tiene el orden aleatorio activado, lo baraja; de lo
     * contrario respeta el orden definido por el docente.
     */
    private List<Long> buildInitialOrder(Evaluation evaluation) {
        List<Long> ids = questionRepository
                .findByEvaluationAndActiveTrueOrderByOrderIndexAsc(evaluation)
                .stream()
                .map(EvaluationQuestion::getId)
                .collect(Collectors.toCollection(ArrayList::new));
        if (Boolean.TRUE.equals(evaluation.getRandomizeQuestions())) {
            Collections.shuffle(ids);
        }
        return ids;
    }

    private String serializeOrder(List<Long> order) {
        return order.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Long> parseOrder(String csv) {
        List<Long> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(Long.valueOf(trimmed));
            }
        }
        return ids;
    }

    /**
     * Garantiza que el intento tenga un orden de preguntas persistido. Los intentos
     * creados antes de esta funcionalidad no lo tienen: se inicializa con el orden
     * natural (sin barajar, para no alterar un intento en curso) y se guarda una vez.
     */
    private List<Long> ensureAttemptOrder(EvaluationAttempt attempt) {
        List<Long> order = parseOrder(attempt.getQuestionOrder());
        boolean changed = false;
        if (order.isEmpty()) {
            order = questionRepository
                    .findByEvaluationAndActiveTrueOrderByOrderIndexAsc(attempt.getEvaluation())
                    .stream()
                    .map(EvaluationQuestion::getId)
                    .collect(Collectors.toCollection(ArrayList::new));
            attempt.setQuestionOrder(serializeOrder(order));
            changed = true;
        }
        if (attempt.getCurrentQuestionIndex() == null) {
            attempt.setCurrentQuestionIndex(0);
            changed = true;
        }
        if (changed) {
            attemptRepository.save(attempt);
        }
        return order;
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

        EvaluationAnswer answer = answerRepository.findByAttemptAndQuestion(attempt, question)
                .orElseGet(() -> EvaluationAnswer.builder()
                        .attempt(attempt)
                        .question(question)
                        .build());

        if (question.getQuestionType() == QuestionType.OPEN_TEXT) {
            // Pregunta abierta: se guarda el texto del estudiante y queda pendiente de
            // revisión manual (sin alternativa ni corrección automática).
            answer.setSelectedOption(null);
            answer.setAnswerText(trimOrNull(request.answerText()));
            answer.setReviewed(false);
            answer.setCorrect(null);
            answerRepository.save(answer);
            return;
        }

        // Pregunta de alternativa única: se valida la alternativa elegida.
        EvaluationOption selectedOption = null;
        if (request.selectedOptionId() != null) {
            selectedOption = optionRepository.findById(request.selectedOptionId())
                    .orElseThrow(() -> new EntityNotFoundException("La alternativa no existe."));
            if (!selectedOption.getQuestion().getId().equals(question.getId())) {
                throw new IllegalArgumentException("La alternativa seleccionada no pertenece a la pregunta.");
            }
        }
        answer.setSelectedOption(selectedOption);
        answer.setAnswerText(null);
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

    /** Elimina las alternativas activas de una pregunta (al convertirla en abierta). */
    private void clearOptions(EvaluationQuestion question) {
        List<EvaluationOption> existing = optionRepository.findByQuestionAndActiveTrueOrderByOrderIndexAsc(question);
        if (!existing.isEmpty()) {
            optionRepository.deleteAll(existing);
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
                evaluation.getAllowPeriodicTable(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                evaluation.getRandomizeQuestions(),
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
                evaluation.getAllowPeriodicTable(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                evaluation.getRandomizeQuestions(),
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
                question.getExpectedAnswer(),
                question.getRequired(),
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
                evaluation.getAllowPeriodicTable(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                evaluation.getRandomizeQuestions(),
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
                evaluation.getAllowPeriodicTable(),
                evaluation.getTrackTabExit(),
                evaluation.getQuestionDisplayMode(),
                evaluation.getRandomizeQuestions(),
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
                question.getRequired(),
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
                                a.getQuestion().getQuestionType(),
                                a.getSelectedOption() == null ? null : a.getSelectedOption().getId(),
                                a.getAnswerText(),
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
                parseOrder(attempt.getQuestionOrder()),
                attempt.getCurrentQuestionIndex(),
                Boolean.TRUE.equals(attempt.getEvaluation().getAllowChemicalCalculator()),
                Boolean.TRUE.equals(attempt.getEvaluation().getAllowPeriodicTable()),
                answers
        );
    }
}

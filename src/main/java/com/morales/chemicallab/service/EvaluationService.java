package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
 * <p><b>Alcance:</b> al enviar un intento se calcula un puntaje básico (alternativa
 * única) y el intento queda en estado SUBMITTED. La calificación definitiva
 * (estado GRADED) y la vista de resultados/reportes se implementarán en la sesión de
 * resultados; aquí solo se deja la estructura preparada.</p>
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
    private final EvaluationAnswerRepository answerRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;

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
                .createdByTeacher(teacher)
                .status(EvaluationStatus.DRAFT)
                .active(true)
                .build();

        evaluationRepository.save(evaluation);
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

        evaluation.setTitle(request.title().trim());
        evaluation.setDescription(trimOrNull(request.description()));
        evaluation.setInstructions(trimOrNull(request.instructions()));
        evaluation.setTopic(trimOrNull(request.topic()));
        evaluation.setMaxAttempts(request.maxAttempts());
        evaluation.setTimeLimitMinutes(request.timeLimitMinutes());

        evaluationRepository.save(evaluation);
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

        upsertAnswer(attempt, request);
        return toAttemptResponse(attempt);
    }

    public AttemptResponse submitAttempt(String username, Long attemptId, SubmitEvaluationAttemptRequest request) {
        StudentProfile student = requireStudent(username);
        EvaluationAttempt attempt = requireOwnedAttempt(attemptId, student);
        requireInProgress(attempt);

        // Persistimos las respuestas que lleguen en el envío (si se enviaron).
        if (request != null && request.answers() != null) {
            for (SubmitEvaluationAnswerRequest answer : request.answers()) {
                upsertAnswer(attempt, answer);
            }
        }

        gradeAttempt(attempt);

        attempt.setStatus(AttemptStatus.SUBMITTED);
        attempt.setSubmittedAt(LocalDateTime.now());
        attemptRepository.save(attempt);
        return toAttemptResponse(attempt);
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

    @Transactional(readOnly = true)
    public EvaluationDetailResponse getAnyEvaluationDetail(Long evaluationId) {
        Evaluation evaluation = evaluationRepository.findById(evaluationId)
                .orElseThrow(() -> new EntityNotFoundException("La evaluación no existe."));
        return toEvaluationDetailResponse(evaluation);
    }

    // =========================================================================
    // CALIFICACIÓN AUTOMÁTICA (básica, encapsulada)
    // =========================================================================

    /**
     * Calcula un puntaje básico para preguntas de alternativa única: una respuesta es
     * correcta si su alternativa elegida está marcada como correcta, y entonces otorga
     * los puntos de la pregunta. El puntaje máximo es la suma de los puntos de todas
     * las preguntas activas. Deja {@code correct} y {@code pointsAwarded} en cada
     * respuesta. La calificación definitiva (estado GRADED) y los reportes son parte
     * del módulo de resultados, que se implementará después.
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

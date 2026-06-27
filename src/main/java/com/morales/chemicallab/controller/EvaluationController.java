package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.service.EvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints del módulo de evaluaciones. El acceso por rol se controla en
 * SecurityConfig; el docente y el estudiante se identifican a partir del usuario
 * autenticado (no de identificadores enviados por el cliente).
 */
@RestController
@RequestMapping("/api/evaluations")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    // =========================================================================
    // DOCENTE
    // =========================================================================

    @PostMapping("/teacher")
    public ResponseEntity<EvaluationResponse> crearEvaluacion(
            Authentication authentication,
            @Valid @RequestBody CreateEvaluationRequest request) {
        EvaluationResponse response = evaluationService.createEvaluation(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/teacher")
    public List<EvaluationResponse> listarMisEvaluaciones(Authentication authentication) {
        return evaluationService.listTeacherEvaluations(authentication.getName());
    }

    @GetMapping("/teacher/{evaluationId}")
    public EvaluationDetailResponse verMiEvaluacion(
            Authentication authentication,
            @PathVariable Long evaluationId) {
        return evaluationService.getTeacherEvaluationDetail(authentication.getName(), evaluationId);
    }

    @PutMapping("/teacher/{evaluationId}")
    public EvaluationResponse editarEvaluacion(
            Authentication authentication,
            @PathVariable Long evaluationId,
            @Valid @RequestBody UpdateEvaluationRequest request) {
        return evaluationService.updateEvaluation(authentication.getName(), evaluationId, request);
    }

    @PostMapping("/teacher/{evaluationId}/questions")
    public ResponseEntity<QuestionResponse> agregarPregunta(
            Authentication authentication,
            @PathVariable Long evaluationId,
            @Valid @RequestBody CreateQuestionRequest request) {
        QuestionResponse response = evaluationService.addQuestion(authentication.getName(), evaluationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/teacher/{evaluationId}/questions/{questionId}")
    public QuestionResponse editarPregunta(
            Authentication authentication,
            @PathVariable Long evaluationId,
            @PathVariable Long questionId,
            @Valid @RequestBody UpdateQuestionRequest request) {
        return evaluationService.updateQuestion(authentication.getName(), evaluationId, questionId, request);
    }

    @PatchMapping("/teacher/{evaluationId}/questions/{questionId}/deactivate")
    public QuestionResponse desactivarPregunta(
            Authentication authentication,
            @PathVariable Long evaluationId,
            @PathVariable Long questionId) {
        return evaluationService.deactivateQuestion(authentication.getName(), evaluationId, questionId);
    }

    @PatchMapping("/teacher/{evaluationId}/publish")
    public EvaluationDetailResponse publicarEvaluacion(
            Authentication authentication,
            @PathVariable Long evaluationId) {
        return evaluationService.publishEvaluation(authentication.getName(), evaluationId);
    }

    @PatchMapping("/teacher/{evaluationId}/archive")
    public EvaluationResponse archivarEvaluacion(
            Authentication authentication,
            @PathVariable Long evaluationId) {
        return evaluationService.archiveEvaluation(authentication.getName(), evaluationId);
    }

    @PostMapping("/teacher/{evaluationId}/assignments")
    public ResponseEntity<EvaluationAssignmentResponse> asignarEvaluacion(
            Authentication authentication,
            @PathVariable Long evaluationId,
            @Valid @RequestBody AssignEvaluationRequest request) {
        EvaluationAssignmentResponse response =
                evaluationService.assignEvaluationToSection(authentication.getName(), evaluationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/teacher/{evaluationId}/assignments/{assignmentId}/deactivate")
    public EvaluationAssignmentResponse desactivarAsignacion(
            Authentication authentication,
            @PathVariable Long evaluationId,
            @PathVariable Long assignmentId) {
        return evaluationService.deactivateAssignment(authentication.getName(), evaluationId, assignmentId);
    }

    // =========================================================================
    // DOCENTE — resultados
    // =========================================================================

    @GetMapping("/teacher/{evaluationId}/results")
    public TeacherEvaluationResultsResponse verResultadosEvaluacion(
            Authentication authentication,
            @PathVariable Long evaluationId) {
        return evaluationService.getTeacherEvaluationResults(authentication.getName(), evaluationId);
    }

    @GetMapping("/teacher/{evaluationId}/results/summary")
    public TeacherEvaluationResultsSummaryResponse verResumenResultadosEvaluacion(
            Authentication authentication,
            @PathVariable Long evaluationId) {
        return evaluationService.getTeacherEvaluationResultsSummary(authentication.getName(), evaluationId);
    }

    @GetMapping("/teacher/attempts/{attemptId}/result")
    public TeacherAttemptResultDetailResponse verResultadoIntentoDocente(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.getTeacherAttemptResult(authentication.getName(), attemptId);
    }

    /**
     * Trazabilidad del intento para el docente: resumen (tiempo usado, estado final,
     * salidas/regresos de pestaña, intentos de salida, herramientas consultadas) y línea
     * de tiempo de eventos. El docente solo puede consultar intentos de sus propias
     * evaluaciones; nunca se exponen respuestas ni claves.
     */
    @GetMapping("/teacher/attempts/{attemptId}/traceability")
    public AttemptTraceabilityResponse verTrazabilidadIntento(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.getAttemptTraceability(authentication.getName(), attemptId);
    }

    // =========================================================================
    // DOCENTE — revisión manual de preguntas abiertas
    // =========================================================================

    /** Bandeja de intentos del docente pendientes de revisión manual. */
    @GetMapping("/teacher/manual-review")
    public List<PendingReviewAttemptResponse> listarPendientesRevision(Authentication authentication) {
        return evaluationService.listPendingManualReview(authentication.getName());
    }

    /** Detalle de un intento para revisar sus respuestas abiertas. */
    @GetMapping("/teacher/attempts/{attemptId}/review")
    public TeacherAttemptReviewResponse verRevisionIntento(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.getAttemptReview(authentication.getName(), attemptId);
    }

    /** Asigna puntaje y retroalimentación a una respuesta abierta. */
    @PatchMapping("/teacher/attempts/{attemptId}/answers/{answerId}/manual-grade")
    public TeacherAttemptReviewResponse calificarRespuestaAbierta(
            Authentication authentication,
            @PathVariable Long attemptId,
            @PathVariable Long answerId,
            @Valid @RequestBody ManualGradeRequest request) {
        return evaluationService.manualGradeAnswer(authentication.getName(), attemptId, answerId, request);
    }

    /** Cierra la revisión de un intento y recalcula su nota final. */
    @PatchMapping("/teacher/attempts/{attemptId}/complete-review")
    public TeacherAttemptReviewResponse completarRevisionIntento(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.completeReview(authentication.getName(), attemptId);
    }

    /** Agrega un ajuste manual de puntaje (bonificación o penalización) al intento. */
    @PostMapping("/teacher/attempts/{attemptId}/adjustments")
    public ResponseEntity<TeacherAttemptReviewResponse> agregarAjuste(
            Authentication authentication,
            @PathVariable Long attemptId,
            @Valid @RequestBody CreateAdjustmentRequest request) {
        TeacherAttemptReviewResponse response =
                evaluationService.addAdjustment(authentication.getName(), attemptId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Anula un ajuste manual de puntaje previamente aplicado al intento. */
    @DeleteMapping("/teacher/attempts/{attemptId}/adjustments/{adjustmentId}")
    public TeacherAttemptReviewResponse anularAjuste(
            Authentication authentication,
            @PathVariable Long attemptId,
            @PathVariable Long adjustmentId) {
        return evaluationService.deleteAdjustment(authentication.getName(), attemptId, adjustmentId);
    }

    /** Guarda la retroalimentación general del intento para el estudiante. */
    @PatchMapping("/teacher/attempts/{attemptId}/feedback")
    public TeacherAttemptReviewResponse actualizarRetroalimentacion(
            Authentication authentication,
            @PathVariable Long attemptId,
            @Valid @RequestBody UpdateAttemptFeedbackRequest request) {
        return evaluationService.updateOverallFeedback(authentication.getName(), attemptId, request);
    }

    /** Cierra la calificación del intento: fija la nota final y la deja visible al estudiante. */
    @PatchMapping("/teacher/attempts/{attemptId}/close-grade")
    public TeacherAttemptReviewResponse cerrarCalificacion(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.closeGrade(authentication.getName(), attemptId);
    }

    // =========================================================================
    // ESTUDIANTE
    // =========================================================================

    @GetMapping("/student")
    public List<StudentEvaluationResponse> listarEvaluacionesEstudiante(Authentication authentication) {
        return evaluationService.listAvailableEvaluationsForStudent(authentication.getName());
    }

    @GetMapping("/student/{evaluationId}")
    public StudentEvaluationDetailResponse verEvaluacionEstudiante(
            Authentication authentication,
            @PathVariable Long evaluationId) {
        return evaluationService.getStudentEvaluationDetail(authentication.getName(), evaluationId);
    }

    @PostMapping("/student/{evaluationId}/attempts")
    public ResponseEntity<AttemptResponse> iniciarIntento(
            Authentication authentication,
            @PathVariable Long evaluationId,
            @RequestBody(required = false) StartEvaluationAttemptRequest request) {
        AttemptResponse response = evaluationService.startAttempt(authentication.getName(), evaluationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/student/attempts/{attemptId}")
    public AttemptResponse verIntento(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.getAttempt(authentication.getName(), attemptId);
    }

    @PostMapping("/student/attempts/{attemptId}/answers")
    public AttemptResponse guardarRespuesta(
            Authentication authentication,
            @PathVariable Long attemptId,
            @Valid @RequestBody SubmitEvaluationAnswerRequest request) {
        return evaluationService.saveAnswer(authentication.getName(), attemptId, request);
    }

    @PostMapping("/student/attempts/{attemptId}/submit")
    public AttemptResponse enviarIntento(
            Authentication authentication,
            @PathVariable Long attemptId,
            @RequestBody(required = false) @Valid SubmitEvaluationAttemptRequest request) {
        return evaluationService.submitAttempt(authentication.getName(), attemptId, request);
    }

    /**
     * Finaliza el intento porque el estudiante decide salir de la evaluación. El intento
     * se cierra y no queda retomable (cuenta como usado). El backend valida que el intento
     * sea del estudiante autenticado y que siga en progreso.
     */
    @PostMapping("/student/attempts/{attemptId}/exit")
    public AttemptResponse salirIntento(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.exitAttempt(authentication.getName(), attemptId);
    }

    /**
     * Registra una incidencia de foco (salida/retorno de pestaña o ventana) del propio
     * intento del estudiante. Solo aplica si la evaluación tiene activada la detección de
     * salida de pestaña; el backend valida la propiedad del intento y descarta duplicados.
     */
    @PostMapping("/student/attempts/{attemptId}/events")
    public AttemptEventSummaryResponse registrarEventoIntento(
            Authentication authentication,
            @PathVariable Long attemptId,
            @Valid @RequestBody RegisterAttemptEventRequest request) {
        return evaluationService.registerAttemptEvent(authentication.getName(), attemptId, request);
    }

    // =========================================================================
    // ESTUDIANTE — resultados
    // =========================================================================

    @GetMapping("/student/results")
    public List<StudentResultSummaryResponse> listarResultadosEstudiante(Authentication authentication) {
        return evaluationService.listStudentResults(authentication.getName());
    }

    @GetMapping("/student/attempts/{attemptId}/result")
    public StudentAttemptResultDetailResponse verResultadoIntentoEstudiante(
            Authentication authentication,
            @PathVariable Long attemptId) {
        return evaluationService.getStudentAttemptResult(authentication.getName(), attemptId);
    }

    // =========================================================================
    // ADMINISTRADOR
    // =========================================================================

    @GetMapping("/admin")
    public List<EvaluationResponse> listarTodas() {
        return evaluationService.listAllEvaluations();
    }

    @GetMapping("/admin/{evaluationId}")
    public AdminEvaluationDetailResponse verCualquierEvaluacion(@PathVariable Long evaluationId) {
        return evaluationService.getAdminEvaluationDetail(evaluationId);
    }
}

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

    // =========================================================================
    // ADMINISTRADOR
    // =========================================================================

    @GetMapping("/admin")
    public List<EvaluationResponse> listarTodas() {
        return evaluationService.listAllEvaluations();
    }

    @GetMapping("/admin/{evaluationId}")
    public EvaluationDetailResponse verCualquierEvaluacion(@PathVariable Long evaluationId) {
        return evaluationService.getAnyEvaluationDetail(evaluationId);
    }
}

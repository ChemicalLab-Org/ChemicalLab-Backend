package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle de un intento para la revisión manual del docente: datos del estudiante y de
 * la evaluación, estado y puntaje parcial, y la lista de preguntas abiertas con la
 * respuesta del estudiante para asignarles puntaje. Solo se entrega si el intento
 * pertenece a una evaluación del docente autenticado. No incluye preguntas de
 * alternativa única (esas ya están calificadas) ni claves de esas alternativas.
 */
public record TeacherAttemptReviewResponse(
        Long attemptId,
        Long evaluationId,
        String evaluationTitle,
        Long studentId,
        String studentCode,
        String studentName,
        String grade,
        String section,
        Integer attemptNumber,
        AttemptStatus status,
        // Puntaje provisional: alternativa única + preguntas abiertas ya revisadas.
        Integer score,
        Integer maxScore,
        LocalDateTime submittedAt,
        LocalDateTime gradedAt,
        int pendingOpenCount,
        List<TeacherReviewAnswerResponse> openAnswers
) {}

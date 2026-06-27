package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle del resultado de un intento propio del estudiante. Incluye su calificación
 * y, solo si {@code canViewDetailedFeedback} es true, la corrección pregunta a
 * pregunta con la alternativa correcta. Cuando es false, las respuestas se entregan
 * sin revelar la alternativa correcta.
 */
public record StudentAttemptResultDetailResponse(
        Long attemptId,
        Long evaluationId,
        String evaluationTitle,
        String topic,
        Integer attemptNumber,
        AttemptStatus status,
        Integer score,
        Integer maxScore,
        Double percentage,
        // Nota final en escala 0–20 (con ajustes manuales). Solo se entrega cuando la
        // calificación está cerrada; antes va null y el estudiante ve "pendiente de revisión".
        BigDecimal finalScore,
        // Retroalimentación general del docente, visible solo con la calificación cerrada.
        String overallFeedback,
        // Indica si la calificación ya está cerrada (y por tanto la nota final es definitiva).
        boolean gradeClosed,
        LocalDateTime submittedAt,
        boolean canViewDetailedFeedback,
        List<StudentAnswerResultResponse> answers
) {}

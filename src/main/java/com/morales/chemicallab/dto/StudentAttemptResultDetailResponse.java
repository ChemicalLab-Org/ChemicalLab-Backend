package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

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
        LocalDateTime submittedAt,
        boolean canViewDetailedFeedback,
        List<StudentAnswerResultResponse> answers
) {}

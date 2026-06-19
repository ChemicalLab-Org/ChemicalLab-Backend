package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista de un intento de evaluación del estudiante, con sus respuestas. El puntaje
 * ({@code score}/{@code maxScore}) queda disponible una vez enviado el intento.
 */
public record AttemptResponse(
        Long id,
        Long evaluationId,
        Long assignmentId,
        AttemptStatus status,
        Integer attemptNumber,
        LocalDateTime startedAt,
        LocalDateTime submittedAt,
        Integer score,
        Integer maxScore,
        List<EvaluationAnswerResponse> answers
) {}

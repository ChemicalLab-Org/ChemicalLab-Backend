package com.morales.chemicallab.dto;

import java.time.LocalDateTime;

/**
 * Respuesta registrada dentro de un intento. Los campos {@code correct} y
 * {@code pointsAwarded} solo se rellenan tras enviar el intento.
 */
public record EvaluationAnswerResponse(
        Long id,
        Long questionId,
        Long selectedOptionId,
        Boolean correct,
        Integer pointsAwarded,
        LocalDateTime answeredAt
) {}

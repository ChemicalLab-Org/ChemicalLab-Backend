package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Resumen de la calificación de un intento propio del estudiante. Siempre expone su
 * puntaje y porcentaje; {@code canViewDetailedFeedback} indica si ya puede ver la
 * retroalimentación detallada (alternativas correctas) sin facilitar la trampa en
 * intentos restantes.
 */
public record StudentResultSummaryResponse(
        Long attemptId,
        Long evaluationId,
        String evaluationTitle,
        String topic,
        Integer attemptNumber,
        AttemptStatus status,
        Integer score,
        Integer maxScore,
        Double percentage,
        // Nota final en escala 0–20 (con ajustes), null mientras la calificación no esté cerrada.
        BigDecimal finalScore,
        // Indica si la calificación del intento ya fue cerrada (nota final definitiva).
        boolean gradeClosed,
        LocalDateTime submittedAt,
        boolean canViewDetailedFeedback,
        int attemptsUsed,
        int maxAttempts
) {}

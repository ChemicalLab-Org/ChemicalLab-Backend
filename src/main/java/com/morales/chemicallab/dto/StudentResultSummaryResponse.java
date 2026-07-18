package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Resumen de la calificación de un intento propio del estudiante. La nota solo se
 * expone cuando la revisión está disponible para el grupo ({@code reviewAvailable});
 * mientras siga bloqueada el estudiante ve el intento como "revisión pendiente" sin su
 * puntaje, para que una alumna que termina antes no filtre resultados a sus compañeras.
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
        // Nota final en escala 0–20 (con ajustes), null mientras la revisión no esté disponible.
        BigDecimal finalScore,
        // Indica si la calificación del intento ya fue cerrada (nota final definitiva).
        boolean gradeClosed,
        LocalDateTime submittedAt,
        // Se mantiene por compatibilidad: equivale a reviewAvailable (revisión desbloqueada).
        boolean canViewDetailedFeedback,
        int attemptsUsed,
        int maxAttempts,
        // --- Disponibilidad de la revisión para el grupo (18.6) ---
        boolean reviewAvailable,
        boolean reviewLocked,
        ReviewUnlockReason reviewUnlockReason,
        LocalDateTime reviewAvailableAt,
        int assignedStudentsCount,
        int finishedStudentsCount,
        int pendingStudentsCount
) {}

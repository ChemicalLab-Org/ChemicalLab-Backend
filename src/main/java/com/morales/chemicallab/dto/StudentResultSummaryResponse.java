package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Resumen de la calificación de un intento propio del estudiante.
 *
 * <p>La nota (puntaje, porcentaje y nota final) solo se expone cuando la revisión está
 * disponible para el grupo ({@code reviewAvailable}) y la calificación está cerrada;
 * mientras la revisión sigue bloqueada, esos campos van null y el estudiante ve
 * "revisión pendiente". {@code canViewDetailedFeedback} se conserva como alias de
 * {@code reviewAvailable}.</p>
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
        int maxAttempts,
        // Disponibilidad de revisión para las estudiantes (regla grupal).
        boolean reviewAvailable,
        com.morales.chemicallab.dto.ReviewUnlockReason reviewUnlockReason,
        // Fecha en que la revisión se desbloquea por vencimiento; null si no hay fecha límite.
        LocalDateTime reviewAvailableAt,
        Integer assignedStudentsCount,
        Integer finishedStudentsCount,
        Integer pendingStudentsCount,
        // Mensaje para la estudiante cuando la revisión sigue bloqueada; null si ya disponible.
        String message
) {}

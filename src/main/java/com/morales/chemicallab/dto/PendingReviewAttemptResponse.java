package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.time.LocalDateTime;

/**
 * Fila de la bandeja de revisión manual del docente: un intento que contiene preguntas
 * abiertas pendientes de calificar. Resume el estudiante, la evaluación, la fecha de
 * envío y cuántas respuestas abiertas faltan por revisar. Nunca incluye el texto de las
 * respuestas ni claves.
 */
public record PendingReviewAttemptResponse(
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
        LocalDateTime submittedAt,
        // Total de preguntas abiertas del intento y cuántas siguen pendientes de revisión.
        int openQuestionCount,
        int pendingOpenCount
) {}

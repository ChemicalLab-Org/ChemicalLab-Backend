package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle del resultado de un intento propio del estudiante.
 *
 * <p>La revisión detallada solo se entrega cuando {@code reviewAvailable} es true, es
 * decir, cuando la evaluación cerró para todo el grupo (todas las estudiantes asignadas
 * finalizaron) o venció la fecha límite. Mientras {@code reviewAvailable} es false, la
 * lista {@code answers} va vacía y no se revelan puntajes, aciertos/errores, alternativas
 * correctas ni explicaciones: la estudiante solo ve el estado del envío y el
 * {@code message} de revisión pendiente. Los conteos del grupo permiten mostrar el avance
 * general (p. ej. "18 de 22 finalizaron") sin exponer nombres de compañeras.</p>
 *
 * <p>{@code canViewDetailedFeedback} se conserva como alias de {@code reviewAvailable}
 * para compatibilidad con el frontend existente.</p>
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
        List<StudentAnswerResultResponse> answers,
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

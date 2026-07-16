package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle del resultado de un intento propio del estudiante. La revisión detallada
 * (nota, corrección pregunta a pregunta y alternativas correctas) solo se entrega
 * cuando la revisión está disponible para el grupo ({@code reviewAvailable}): mientras
 * siga bloqueada, {@code answers} llega vacío, la nota va en null y solo se informa el
 * estado del grupo y el mensaje de revisión pendiente. De este modo una alumna que
 * termina antes no puede ver las respuestas correctas ni filtrarlas a sus compañeras.
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
        // calificación está cerrada y la revisión está disponible; antes va null y el
        // estudiante ve "pendiente de revisión".
        BigDecimal finalScore,
        // Retroalimentación general del docente, visible solo con la revisión disponible.
        String overallFeedback,
        // Indica si la calificación ya está cerrada (y por tanto la nota final es definitiva).
        boolean gradeClosed,
        LocalDateTime submittedAt,
        // Se mantiene por compatibilidad: equivale a reviewAvailable (revisión desbloqueada
        // para el grupo). Cuando es false, la corrección detallada no se expone.
        boolean canViewDetailedFeedback,
        List<StudentAnswerResultResponse> answers,
        // --- Disponibilidad de la revisión para el grupo (18.6) ---
        // true cuando la revisión detallada ya está desbloqueada (venció el plazo o todas
        // las estudiantes asignadas finalizaron). false mientras siga bloqueada.
        boolean reviewAvailable,
        boolean reviewLocked,
        ReviewUnlockReason reviewUnlockReason,
        // Fecha en que la revisión quedará (o quedó) disponible por plazo, si la evaluación
        // tiene fecha límite; null si no hay plazo definido.
        LocalDateTime reviewAvailableAt,
        // Progreso general del grupo (sin nombres): cuántas estudiantes asignadas hay,
        // cuántas ya finalizaron y cuántas siguen pendientes.
        int assignedStudentsCount,
        int finishedStudentsCount,
        int pendingStudentsCount,
        // Mensaje para el estudiante cuando la revisión está bloqueada; null si está disponible.
        String reviewMessage
) {}

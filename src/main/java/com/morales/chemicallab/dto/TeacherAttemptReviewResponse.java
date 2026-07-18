package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle de un intento para la revisión manual del docente: datos del estudiante y de
 * la evaluación, estado y puntaje parcial, y la lista de preguntas abiertas con la
 * respuesta del estudiante para asignarles puntaje. Solo se entrega si el intento
 * pertenece a una evaluación del docente autenticado. No incluye preguntas de
 * alternativa única (esas ya están calificadas) ni claves de esas alternativas.
 */
public record TeacherAttemptReviewResponse(
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
        // Puntaje provisional en puntos: alternativa única + preguntas abiertas ya revisadas.
        Integer score,
        Integer maxScore,
        // Nota base en escala 0–20 derivada del puntaje en puntos (score/maxScore*20).
        BigDecimal baseScore,
        // Suma de los ajustes manuales activos (en escala 0–20, con su signo).
        BigDecimal adjustmentsTotal,
        // Nota final estimada: nota base + ajustes, acotada a [0, 20]. Es la nota que verá el
        // estudiante una vez cerrada la calificación.
        BigDecimal finalScore,
        // Retroalimentación general del docente para el estudiante (visible al cerrar).
        String overallFeedback,
        // Indica si la calificación ya fue cerrada (bloquea la edición de puntajes/ajustes).
        Boolean gradeClosed,
        LocalDateTime gradeClosedAt,
        LocalDateTime submittedAt,
        LocalDateTime gradedAt,
        int pendingOpenCount,
        List<TeacherReviewAnswerResponse> openAnswers,
        List<AttemptAdjustmentResponse> adjustments
) {}

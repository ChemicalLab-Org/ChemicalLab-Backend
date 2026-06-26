package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Detalle completo del resultado de un intento de un estudiante, para el docente.
 * Incluye los datos del estudiante, la calificación y la corrección pregunta a
 * pregunta (con la alternativa correcta visible).
 */
public record TeacherAttemptResultDetailResponse(
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
        Integer score,
        Integer maxScore,
        Double percentage,
        LocalDateTime startedAt,
        LocalDateTime submittedAt,
        LocalDateTime gradedAt,
        // Cantidad de salidas de pestaña detectadas en el intento (0 si la evaluación
        // no tiene activada la detección).
        long tabExitCount,
        List<TeacherAnswerResultResponse> answers
) {}

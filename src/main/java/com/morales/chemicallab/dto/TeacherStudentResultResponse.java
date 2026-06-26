package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.AttemptStatus;

import java.time.LocalDateTime;

/**
 * Fila de resultado de un estudiante dentro de una evaluación, para la vista del
 * docente. Resume la calificación de un intento sin entrar al detalle de cada
 * respuesta (eso lo entrega {@link TeacherAttemptResultDetailResponse}).
 */
public record TeacherStudentResultResponse(
        Long attemptId,
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
        LocalDateTime submittedAt,
        LocalDateTime gradedAt,
        // Cantidad de salidas de pestaña detectadas en el intento (0 si la evaluación
        // no tiene activada la detección). Indicador simple para el docente.
        long tabExitCount
) {}

package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.EvaluationStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista de detalle de una evaluación para la supervisión institucional del
 * administrador. Es de solo lectura e incluye la información general (título,
 * docente creador, estado, asignaciones a grado/sección, número de preguntas) y el
 * contenido de las preguntas, pero <strong>no</strong> expone la alternativa
 * correcta de ninguna pregunta.
 */
public record AdminEvaluationDetailResponse(
        Long id,
        String title,
        String description,
        String instructions,
        String topic,
        EvaluationStatus status,
        Integer maxAttempts,
        Integer timeLimitMinutes,
        Boolean active,
        String createdByTeacher,
        long questionCount,
        List<AdminEvaluationQuestionResponse> questions,
        List<EvaluationAssignmentResponse> assignments,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

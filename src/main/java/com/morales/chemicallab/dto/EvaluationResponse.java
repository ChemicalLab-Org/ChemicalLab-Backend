package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.QuestionDisplayMode;

import java.time.LocalDateTime;

/**
 * Vista resumida de una evaluación para el docente o el administrador. Incluye
 * conteos de preguntas y asignaciones pero no su detalle completo.
 */
public record EvaluationResponse(
        Long id,
        String title,
        String description,
        String instructions,
        String topic,
        EvaluationStatus status,
        Integer maxAttempts,
        Integer timeLimitMinutes,
        Boolean allowChemicalCalculator,
        Boolean allowPeriodicTable,
        Boolean trackTabExit,
        QuestionDisplayMode questionDisplayMode,
        Boolean randomizeQuestions,
        Boolean active,
        long questionCount,
        long assignmentCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.QuestionDisplayMode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista completa de una evaluación para el docente: incluye las preguntas con sus
 * alternativas (mostrando cuál es la correcta) y las asignaciones a grado/sección.
 */
public record EvaluationDetailResponse(
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
        List<QuestionResponse> questions,
        List<EvaluationAssignmentResponse> assignments,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

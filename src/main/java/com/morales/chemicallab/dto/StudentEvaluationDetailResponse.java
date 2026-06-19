package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Vista de detalle de una evaluación asignada para el estudiante: incluye las
 * preguntas con sus alternativas, sin revelar cuáles son correctas.
 */
public record StudentEvaluationDetailResponse(
        Long id,
        String title,
        String description,
        String instructions,
        String topic,
        Integer timeLimitMinutes,
        List<StudentQuestionResponse> questions,
        Long assignmentId
) {}

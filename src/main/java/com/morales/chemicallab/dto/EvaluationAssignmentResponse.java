package com.morales.chemicallab.dto;

import java.time.LocalDateTime;

/**
 * Asignación de una evaluación a un grado/sección, vista por el docente.
 */
public record EvaluationAssignmentResponse(
        Long id,
        String grade,
        String section,
        LocalDateTime startAt,
        LocalDateTime dueAt,
        Boolean active,
        LocalDateTime assignedAt
) {}

package com.morales.chemicallab.dto;

/**
 * Resumen agregado de los resultados de una evaluación (sin la lista de intentos),
 * para cabeceras o tarjetas de estadística en la vista del docente.
 */
public record TeacherEvaluationResultsSummaryResponse(
        Long evaluationId,
        String title,
        String topic,
        Integer maxScore,
        int totalAttempts,
        Double averageScore,
        Double averagePercentage,
        Integer highestScore,
        Integer lowestScore,
        int approvedCount,
        int failedCount
) {}

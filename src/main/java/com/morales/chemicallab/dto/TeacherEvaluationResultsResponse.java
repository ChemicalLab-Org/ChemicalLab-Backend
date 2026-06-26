package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Resultados de una evaluación para el docente: agregados generales más la lista de
 * intentos calificados de sus estudiantes. Solo contempla intentos en estados
 * terminales (enviados/calificados); los intentos en progreso no se incluyen.
 */
public record TeacherEvaluationResultsResponse(
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
        int failedCount,
        List<TeacherStudentResultResponse> results
) {}

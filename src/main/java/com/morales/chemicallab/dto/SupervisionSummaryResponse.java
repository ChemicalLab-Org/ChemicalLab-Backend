package com.morales.chemicallab.dto;

/**
 * Resumen académico para la supervisión institucional del administrador. Agrega
 * únicamente conteos derivados de los registros existentes; es de solo lectura y no
 * expone datos sensibles ni claves de respuesta.
 *
 * @param totalConcepts          total de contenidos conceptuales registrados.
 * @param publishedConcepts      contenidos publicados.
 * @param totalEvaluations       total de evaluaciones registradas.
 * @param publishedEvaluations   evaluaciones publicadas.
 * @param teachersWithActivity   docentes con al menos un contenido o evaluación creados.
 * @param sectionsWithAssignments grados/secciones distintos con asignaciones activas.
 * @param submittedAttempts      intentos enviados o calificados de los estudiantes.
 */
public record SupervisionSummaryResponse(
        long totalConcepts,
        long publishedConcepts,
        long totalEvaluations,
        long publishedEvaluations,
        long teachersWithActivity,
        long sectionsWithAssignments,
        long submittedAttempts
) {}

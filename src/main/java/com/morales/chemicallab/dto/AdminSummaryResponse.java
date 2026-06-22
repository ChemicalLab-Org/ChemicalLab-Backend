package com.morales.chemicallab.dto;

/**
 * Resumen administrativo con métricas generales del sistema. Solo agrega conteos
 * a partir de los registros existentes; no expone datos sensibles ni contraseñas.
 *
 * <p>Las métricas de usuarios (roles y estados) son obligatorias; las de módulos
 * (contenidos, evaluaciones e intentos) son complementarias y se calculan a partir
 * de los datos ya almacenados.</p>
 */
public record AdminSummaryResponse(
        long totalUsers,
        long totalAdmins,
        long totalTeachers,
        long totalStudents,
        long activeUsers,
        long inactiveUsers,
        long activeTeachers,
        long activeStudents,
        long totalConcepts,
        long publishedConcepts,
        long totalEvaluations,
        long publishedEvaluations,
        long submittedAttempts
) {}

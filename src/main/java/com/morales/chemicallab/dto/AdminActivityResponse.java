package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Actividad reciente del sistema basada únicamente en registros existentes
 * (últimos usuarios, evaluaciones y contenidos creados). No constituye un módulo
 * de trazabilidad: es una vista de solo lectura derivada de los datos actuales.
 */
public record AdminActivityResponse(
        List<AdminActivityItem> recentUsers,
        List<AdminActivityItem> recentEvaluations,
        List<AdminActivityItem> recentConcepts
) {}

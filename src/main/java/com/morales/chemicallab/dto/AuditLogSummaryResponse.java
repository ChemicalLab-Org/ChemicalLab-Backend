package com.morales.chemicallab.dto;

/**
 * Resumen agregado de los eventos de trazabilidad. Reúne conteos por severidad y por
 * los módulos más relevantes para alimentar las tarjetas del panel administrativo.
 */
public record AuditLogSummaryResponse(
        long totalLogs,
        long infoCount,
        long warningCount,
        long errorCount,
        long authEvents,
        long userEvents,
        long evaluationEvents,
        long conceptEvents
) {}

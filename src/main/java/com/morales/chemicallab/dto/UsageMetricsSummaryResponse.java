package com.morales.chemicallab.dto;

import java.util.List;

/**
 * Resumen agregado de las métricas de uso para las tarjetas del panel administrativo.
 * Incluye el total de eventos y los desgloses por módulo, por tipo de evento y por rol.
 * Refleja el rango de fechas aplicado (nulo si no se filtró).
 */
public record UsageMetricsSummaryResponse(
        long totalEvents,
        List<UsageCountResponse> byModule,
        List<UsageCountResponse> byEventType,
        List<UsageCountResponse> byRole
) {}

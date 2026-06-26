package com.morales.chemicallab.dto;

/**
 * Conteo de eventos de uso para una clave concreta (un módulo, un tipo de evento o un rol).
 * Se usa en el resumen de métricas del panel administrativo.
 */
public record UsageCountResponse(
        String key,
        long count
) {}

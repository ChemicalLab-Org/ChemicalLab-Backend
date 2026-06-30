package com.morales.chemicallab.entity;

/**
 * Módulo funcional de la plataforma al que pertenece un evento de uso.
 *
 * <p>Se usa para agrupar las métricas de usabilidad por área del sistema (acceso a
 * módulos, frecuencia de uso, etc.). No tiene relación con la categorización de los
 * logs de auditoría: las métricas de uso miden la interacción educativa, no la
 * trazabilidad de seguridad.</p>
 */
public enum UsageModule {
    DASHBOARD,
    PERIODIC_TABLE,
    COMPOUNDS,
    CONCEPTS,
    EVALUATIONS,
    WHITEBOARD,
    RESULTS,
    ADMIN,
    USERS,
    SYSTEM_STATUS
}

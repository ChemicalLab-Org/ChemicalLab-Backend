package com.morales.chemicallab.entity;

/**
 * Módulo del sistema al que pertenece un evento de trazabilidad. Permite agrupar y
 * filtrar los logs por área funcional.
 */
public enum LogCategory {
    AUTH,
    USER_MANAGEMENT,
    CONCEPT_CONTENT,
    EVALUATION,
    RESULTS,
    ADMIN,
    SYSTEM
}

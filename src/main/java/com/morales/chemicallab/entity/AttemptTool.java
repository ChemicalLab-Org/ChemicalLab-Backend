package com.morales.chemicallab.entity;

/**
 * Herramienta de apoyo permitida que el estudiante puede abrir durante un intento. Se usa
 * únicamente como metadata segura de los eventos {@code TOOL_OPENED}/{@code TOOL_RETURNED}:
 * deja constancia de que se usó la herramienta, nunca de qué se consultó o formó.
 *
 * <ul>
 *   <li>PERIODIC_TABLE: módulo de tabla periódica ({@code /periodic-table}).</li>
 *   <li>COMPOUND_FORMATION: módulo de formación de compuestos ({@code /compounds}).</li>
 * </ul>
 */
public enum AttemptTool {
    PERIODIC_TABLE,
    COMPOUND_FORMATION
}
